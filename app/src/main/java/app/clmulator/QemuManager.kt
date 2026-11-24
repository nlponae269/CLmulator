package app.clmulator

import android.content.Context
import android.content.Intent
import java.io.File

/** Запуск и управление процессом QEMU. Одновременно работает одна машина. */
object QemuManager {
    @Volatile var machine: Machine? = null; private set
    @Volatile private var process: Process? = null
    @Volatile var startedAt = 0L; private set
    @Volatile var lastError: String? = null; private set
    var qmp: QmpClient? = null; private set
    private val listeners = mutableListOf<() -> Unit>()

    val isRunning get() = process != null

    fun vmDir(ctx: Context) = File(ctx.cacheDir, "vm").apply { mkdirs() }
    fun vncSocket(ctx: Context) = File(vmDir(ctx), "vnc.sock").absolutePath
    fun qmpSocket(ctx: Context) = File(vmDir(ctx), "qmp.sock").absolutePath
    fun serialLog(ctx: Context) = File(vmDir(ctx), "serial.log")
    fun qemuLog(ctx: Context) = File(vmDir(ctx), "qemu.log")

    /** Старые Android-x86 (до 4.0) видят только PS/2-мышь, новые — usb-tablet. */
    fun usesTablet(m: Machine) = m.api == 0 || m.api >= 14

    fun stateText(): String {
        val m = machine ?: return "Остановлена"
        if (!isRunning) return "Остановлена"
        val s = (System.currentTimeMillis() - startedAt) / 1000
        return "Работает • ${m.ramMb} МБ ОЗУ • ${s / 60}:${String.format("%02d", s % 60)}"
    }

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners += l } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners -= l } }
    private fun changed() {
        val ls = synchronized(listeners) { listeners.toList() }
        App.main.post { ls.forEach { it() } }
        MachineStore.notifyChanged()
    }

    @Synchronized
    fun start(ctx: Context, m: Machine) {
        if (isRunning) {
            if (machine?.id == m.id) return
            throw IllegalStateException("Сначала выключите «${machine?.name}»")
        }
        val app = ctx.applicationContext
        val bios = prepareBios(app)
        if (m.persistent && m.bootMode == Machine.MODE_ANDROID_X86 && !m.dataDisk(app).exists()) {
            Tasks.createDataDisk(app, m.dataDisk(app))
        }
        File(app.filesDir, "resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        File(vncSocket(app)).delete(); File(qmpSocket(app)).delete()
        serialLog(app).delete()

        val cmd = buildCommand(app, m, bios)
        Log.i("qemu: " + cmd.joinToString(" "))
        val env = mapOf(
            "LD_LIBRARY_PATH" to app.applicationInfo.nativeLibraryDir,
            "TMPDIR" to app.cacheDir.absolutePath,
            "HOME" to app.filesDir.absolutePath,
        )
        qemuLog(app).writeText("$ " + cmd.joinToString(" ") + "\n\n")
        val p = ProcessBuilder(cmd).directory(vmDir(app)).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(qemuLog(app)))
            .also { it.environment().putAll(env) }
            .start()
        process = p
        machine = m
        startedAt = System.currentTimeMillis()
        lastError = null
        qmp = QmpClient(qmpSocket(app))
        m.lastBootMs = startedAt
        MachineStore.save(app, m)
        ctx.startForegroundService(Intent(ctx, VmService::class.java))
        Thread({
            val code = p.waitFor()
            val log = runCatching { qemuLog(app).readLines().takeLast(6).joinToString("\n") }.getOrDefault("")
            if (code != 0 && System.currentTimeMillis() - startedAt < 15000) {
                lastError = "QEMU завершился с кодом $code\n$log"
            }
            Log.i("qemu exited $code")
            synchronized(this) {
                if (process === p) {
                    process = null
                    qmp?.close(); qmp = null
                }
            }
            changed()
        }, "qemu-wait").start()
        changed()
    }

    fun stop() {
        val p = process ?: return
        IoThread.post {
            runCatching { qmp?.exec("quit") }
            Thread.sleep(1500)
            if (p.isAlive) p.destroy()
        }
    }

    /** Мягкое выключение: кнопка питания ACPI */
    fun powerButton() = IoThread.post { qmp?.let { if (it.connect()) it.exec("system_powerdown") } }

    fun reset() = IoThread.post { qmp?.let { if (it.connect()) it.exec("system_reset") } }

    private fun buildCommand(ctx: Context, m: Machine, bios: File): List<String> {
        val prefs = App.prefs
        val qemu = File(ctx.applicationInfo.nativeLibraryDir, "libqemu_x86_64.so").absolutePath
        val mttcg = prefs.getBoolean("mttcg", true) && m.cpus > 1
        val tb = prefs.getInt("tb", 256)
        val c = mutableListOf(
            qemu, "-L", bios.absolutePath, "-name", m.name.replace(",", " "),
            "-machine", "pc", "-m", m.ramMb.toString(), "-smp", m.cpus.toString(), "-cpu", m.cpu,
            "-accel", "tcg,thread=${if (mttcg) "multi" else "single"},tb-size=$tb",
            "-vga", "std", "-global", "PIIX4_PM.disable_s3=1", "-rtc", "base=localtime",
            "-audio", "none", "-k", "en-us",
            "-display", "none", "-vnc", "unix:" + vncSocket(ctx),
            "-qmp", "unix:" + qmpSocket(ctx) + ",server=on,wait=off",
            "-monitor", "none", "-serial", "file:" + serialLog(ctx).absolutePath,
        )
        if (usesTablet(m)) c += listOf("-usb", "-device", "usb-tablet")
        c += if (m.network) listOf("-nic", "user,model=e1000") else listOf("-nic", "none")
        val img = m.imageFile(ctx).absolutePath
        when (m.bootMode) {
            Machine.MODE_ANDROID_X86 -> {
                if (m.persistent) c += listOf("-drive", "file=${m.dataDisk(ctx).absolutePath},format=raw,if=ide,index=0,cache=writeback")
                c += listOf("-cdrom", img, "-kernel", m.kernel(ctx).absolutePath,
                    "-initrd", m.initrd(ctx).absolutePath, "-append", buildAppend(m))
            }
            Machine.MODE_CDROM -> c += listOf("-cdrom", img, "-boot", "d")
            else -> {
                val fmt = if (img.endsWith(".qcow2")) "qcow2" else if (img.endsWith(".vdi")) "vdi" else if (img.endsWith(".vmdk")) "vmdk" else "raw"
                c += listOf("-drive", "file=$img,format=$fmt,if=ide,index=0", "-boot", "c")
            }
        }
        return c
    }

    fun buildAppend(m: Machine): String {
        val skip = listOf("vga=", "DPI=", "DATA=", "console=", "SRC=")
        val t = m.append.split(Regex("\\s+")).filter { it.isNotBlank() && skip.none { s -> it.startsWith(s) } }.toMutableList()
        if (m.vgaMode > 0) t += "vga=${m.vgaMode}"
        if (m.dpi > 0) t += "DPI=${m.dpi}"
        t += "SRC="
        t += if (m.persistent) "DATA=sda" else "DATA="
        t += "console=ttyS0"
        return t.joinToString(" ")
    }

    /** BIOS и ROM-ы QEMU лежат в assets/qemu — копируем в files/qemu при обновлении приложения. */
    private fun prepareBios(ctx: Context): File {
        val dir = File(ctx.filesDir, "qemu")
        val stamp = File(dir, ".stamp")
        val ver = BuildConfig.VERSION_CODE.toString() + "-" + ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
        if (stamp.exists() && stamp.readText() == ver) return dir
        dir.deleteRecursively(); dir.mkdirs()
        fun copy(asset: String, dst: File) {
            val list = ctx.assets.list(asset).orEmpty()
            if (list.isEmpty()) {
                ctx.assets.open(asset).use { i -> dst.outputStream().use { i.copyTo(it) } }
            } else {
                dst.mkdirs(); list.forEach { copy("$asset/$it", File(dst, it)) }
            }
        }
        ctx.assets.list("qemu").orEmpty().forEach { copy("qemu/$it", File(dir, it)) }
        stamp.writeText(ver)
        return dir
    }
}
