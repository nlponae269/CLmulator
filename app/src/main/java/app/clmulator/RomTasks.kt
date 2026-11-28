package app.clmulator

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.clmulator.rom.Cpio
import app.clmulator.rom.Lz4InputStream
import app.clmulator.rom.Payload
import app.clmulator.rom.RomAnalyzer
import app.clmulator.rom.RomInfo
import app.clmulator.rom.RomScanner
import app.clmulator.rom.ScanResult
import app.clmulator.rom.Source
import app.clmulator.rom.ZipArchive
import java.io.File
import java.io.FileOutputStream

object RomTasks {

    /** Похоже ли начало файла на прошивку телефона (а не на ISO/диск x86). */
    fun looksLikePhoneRom(ctx: Context, uri: Uri, name: String): Boolean {
        val lower = name.lowercase()
        if (lower.endsWith(".iso") || lower.endsWith(".qcow2") || lower.endsWith(".vdi") || lower.endsWith(".vmdk")) return false
        val head = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readNBytes(4200) } }.getOrDefault(ByteArray(0))
        fun at(o: Int, s: String) = head.size >= o + s.length && String(head, o, s.length, Charsets.ISO_8859_1) == s
        if (lower.endsWith(".img") || lower.endsWith(".raw")) {
            return at(0, "ANDROID!") || at(0, "VNDRBOOT") || (head.size >= 4 && ZipArchive.le32(head, 0) == 0xED26FF3AL) ||
                (head.size > 1081 && head[1080] == 0x53.toByte() && head[1081] == 0xEF.toByte()) ||
                (head.size >= 1028 && ZipArchive.le32(head, 1024) == 0xE0F5E1E2L) || (head.size >= 4100 && ZipArchive.le32(head, 4096) == 0x616C4467L)
        }
        return true
    }

    // ------------------------------------------------------------------ импорт

    fun importRom(ctx: Context, uri: Uri, name: String) {
        val app = ctx.applicationContext
        val t = BgTask("rom-" + System.currentTimeMillis(), "Импорт прошивки", name)
        Tasks.start(app, t) {
            runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val size = Tasks.fileSize(app, uri)
            val rom = Rom(Rom.newId(), uri.toString(), name, size, System.currentTimeMillis())
            rom.dir(app).mkdirs()
            try {
                var src: Source? = runCatching { rom.open(app).also { s -> check(s.size > 0); s.bytes(s.size - 1, 1) } }.getOrNull()
                if (src == null) {
                    // провайдер не даёт произвольный доступ (облако и т.п.) — копируем к себе
                    t.status = "Копирование…"; Tasks.changed(true)
                    app.contentResolver.openInputStream(uri)!!.use { Tasks.copyWithProgress(t, it, rom.localCopy(app), size, "Копирование") }
                    src = rom.open(app)
                }
                val info: RomInfo
                src.use { s ->
                    t.status = "Анализ прошивки…"; t.progress = 0f; Tasks.changed(true)
                    val sc = RomScanner(progress = { d, tot ->
                        t.progress = if (tot > 0) d.toFloat() / tot else -1f
                        t.status = "Анализ: " + fmtSize(d) + " из " + fmtSize(tot); Tasks.changed()
                    }, cancelled = { t.cancelled })
                    val r = try { sc.scan(s, name) } catch (e: InterruptedException) { throw CancelledException() }
                    t.status = "Чтение boot.img и метаданных…"; t.progress = -1f; Tasks.changed(true)
                    val comment = runCatching { ZipArchive(s).comment }.getOrNull()
                    info = RomAnalyzer.analyze(r, name, s.size, comment)
                    t.status = "Подготовка ядра…"; Tasks.changed(true)
                    runCatching { prepareKernel(app, rom, r, info) }.onFailure { Log.w("kernel prep", it) }
                    if (info.type == "X86") setupX86(app, rom, s, info, t)
                }
                RomStore.save(app, rom, info)
                t.status = "Готово: ${info.title}"; t.progress = 1f; Tasks.changed(true)
            } catch (e: Throwable) {
                rom.dir(app).deleteRecursively()
                throw e
            }
        }
    }

    /** Ядро + ramdisk для настоящей тест-загрузки в QEMU ARM64. */
    private fun prepareKernel(ctx: Context, rom: Rom, r: ScanResult, info: RomInfo) {
        val boots = r.parsedBoots
        val kb = boots.firstOrNull { it.second.hasKernel && it.first.substringAfterLast('/').startsWith("boot") }
            ?: boots.firstOrNull { it.second.hasKernel && !it.first.contains("recovery") }
            ?: boots.firstOrNull { it.second.hasKernel } ?: return
        if (!kb.second.isArm64Image) return
        rom.kernel(ctx).writeBytes(kb.second.kernelRaw)
        val parts = ArrayList<ByteArray>()
        boots.firstOrNull { it.second.kind == "vendor_boot" }?.second?.ramdisk?.takeIf { it.isNotEmpty() }?.let { parts += it }
        val generic = boots.firstOrNull { it.first.substringAfterLast('/').startsWith("init_boot") }?.second?.ramdisk?.takeIf { it.isNotEmpty() }
            ?: kb.second.ramdisk.takeIf { it.isNotEmpty() }
        generic?.let { parts += it }
        if (parts.isNotEmpty()) rom.ramdisk(ctx).writeBytes(Cpio.concat(parts))
    }

    /** x86-прошивка внутри zip: достаём ISO и создаём машину QEMU (полноценный запуск). */
    private fun setupX86(ctx: Context, rom: Rom, s: Source, info: RomInfo, t: BgTask) {
        val inner = info.isoPath.substringAfter('/', "")
        val iso = File(MachineStore.images(ctx), info.isoPath.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_"))
        if (!iso.exists()) {
            val z = ZipArchive(s)
            val e = z[inner] ?: throw IllegalStateException("ISO внутри архива вложен слишком глубоко — распакуйте его и импортируйте ISO")
            z.open(e).use { Tasks.copyWithProgress(t, it, File(iso.path + ".part"), e.size, "Распаковка ISO") }
            File(iso.path + ".part").renameTo(iso)
        }
        val ver = Regex("""(\d)\.(\d)""").find(iso.name)?.value ?: "?"
        val api = Catalog.apiForVersion(ver)
        val m = Machine(
            name = info.title.take(40), label = "x86 из .zip", version = ver, api = api,
            color1 = 0xFF26A69A.toInt(), color2 = 0xFF004D40.toInt(), image = iso.name,
            ramMb = if (api in 1..10) 512 else 2048, cpus = if (api in 1..10) 1 else 2, cpu = if (api >= 21 || api == 0) "max" else "qemu64",
        )
        Tasks.prepareMachine(ctx, m, t)
        MachineStore.save(ctx, m)
        rom.machineId = m.id
    }

    // ------------------------------------------------------------------ извлечение раздела

    fun exportablePartitions(ctx: Context, rom: Rom): List<Pair<String, Long>> {
        val info = rom.info(ctx)
        if (info.payloadPath.isNotEmpty()) return info.partitions.map { it.name to it.size }
        return info.files.filter { p ->
            val b = p.substringAfterLast('/').lowercase()
            p.count { it == '/' } == 1 && (b.endsWith(".img") || b.endsWith(".img.lz4") || b.endsWith(".bin") || b.endsWith(".dat.br"))
        }.map { it.substringAfter('/') to 0L }
    }

    fun exportPartition(ctx: Context, rom: Rom, part: String, out: Uri) {
        val app = ctx.applicationContext
        val t = BgTask("exp-" + System.currentTimeMillis(), "Извлечение раздела", "$part — ${rom.info(app).title}")
        Tasks.start(app, t) {
            rom.open(app).use { s ->
                val pfd = app.contentResolver.openFileDescriptor(out, "rwt") ?: throw IllegalStateException("Не удалось создать файл")
                pfd.use { fd ->
                    FileOutputStream(fd.fileDescriptor).use { fos ->
                        val info = rom.info(app)
                        if (info.payloadPath.isNotEmpty()) {
                            val pl = openPayload(s, info)
                            val p = pl.partition(part) ?: throw IllegalStateException("Раздел $part не найден")
                            try {
                                pl.extract(p, fos.channel, { d, tot ->
                                    t.progress = d.toFloat() / maxOf(1, tot); t.status = "Распаковка: ${fmtSize(d)} из ${fmtSize(tot)}"; Tasks.changed()
                                }, { t.cancelled })
                            } catch (e: InterruptedException) { throw CancelledException() }
                        } else {
                            val z = ZipArchive(s)
                            val e = z[part] ?: throw IllegalStateException("$part не найден")
                            val inp = z.open(e).let { if (part.endsWith(".lz4")) Lz4InputStream(it.buffered()) else it }
                            inp.use { i ->
                                val buf = ByteArray(1 shl 16); var got = 0L
                                while (true) {
                                    if (t.cancelled) throw CancelledException()
                                    val n = i.read(buf); if (n < 0) break
                                    fos.write(buf, 0, n); got += n
                                    t.progress = if (e.size > 0) got.toFloat() / e.size else -1f; t.status = "Запись: ${fmtSize(got)}"; Tasks.changed()
                                }
                            }
                        }
                    }
                }
            }
            t.status = "Готово"; t.progress = 1f
        }
    }

    fun openPayload(s: Source, info: RomInfo): Payload {
        val inner = info.payloadPath.substringAfter('/', "")
        val z = ZipArchive(s)
        val e = z[inner] ?: z.find { it.name.endsWith("payload.bin") } ?: throw IllegalStateException("payload.bin не найден")
        return Payload(z.slice(e))
    }
}

/**
 * «Прошивка» в виртуальный телефон. Сама запись идёт в виртуальное устройство,
 * а проверки — настоящие: SHA-256 блоков payload.bin, CRC32 zip, MD5 Odin, CRC gzip.
 */
class Flasher(
    private val ctx: Context,
    private val rom: Rom,
    private val verify: Boolean,
    private val log: (String) -> Unit,
    private val progress: (Float) -> Unit,
    private val cancelled: () -> Boolean,
) {
    private val info = rom.info(ctx)

    fun run(dev: DeviceState): Boolean {
        log("Installing zip file '/sdcard/${rom.name}'")
        log("Checking for Digest file...")
        if (info.signed) log("Подпись пакета: найдена" + (info.certSubject.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""))
        if (info.fingerprint.isNotEmpty()) log("Target: ${info.fingerprint}")
        when (info.type) {
            "ENCRYPTED", "UNKNOWN" -> {
                info.notes.forEach { log("E: $it") }
                log("E: Неподдерживаемый пакет: ${info.typeTitle}")
                return fail()
            }
            "AB_INCREMENTAL" -> {
                log("Detected A/B OTA (payload.bin)")
                log("E: Это инкрементальное обновление (delta). Оно ставится только поверх предыдущей версии — нужна полная прошивка.")
                return fail()
            }
        }
        val ok = rom.open(ctx).use { s ->
            when (info.type) {
                "AB_OTA" -> flashPayload(s, dev)
                "MAGISK" -> flashModule(dev)
                else -> flashGeneric(s)
            }
        }
        if (!ok) return fail()
        if (info.type != "MAGISK" && info.type != "ANYKERNEL") {
            if (dev.installedRom != null && dev.installedRom != rom.id) {
                dev.dataDirty = true
                log("")
                log("ВНИМАНИЕ: установлена другая прошивка. Если система не запустится — сделайте Wipe → Format Data.")
            }
            dev.installedRom = rom.id
            dev.modules.clear()
        }
        log("Updating partition details...")
        log("...done")
        progress(1f)
        return true
    }

    private fun fail(): Boolean {
        log("Updater process ended with ERROR: 1")
        log("Error installing zip file '/sdcard/${rom.name}'")
        return false
    }

    private fun flashPayload(s: Source, dev: DeviceState): Boolean {
        val pl = RomTasks.openPayload(s, info)
        val target = if (dev.slot == "a") "b" else "a"
        log("Detected A/B OTA, payload v${pl.version}, ${pl.partitions.size} разделов, ${fmtSize(pl.totalSize)}")
        if (info.virtualAB.isNotEmpty()) log(info.virtualAB)
        log("Current slot: ${dev.slot.uppercase()}, установка в неактивный слот ${target.uppercase()}")
        val total = pl.partitions.sumOf { it.compressedSize }.coerceAtLeast(1)
        var base = 0L
        var bad = 0
        for (p in pl.partitions) {
            if (cancelled()) { log("E: Отменено"); return false }
            log("- ${p.name}_$target (${fmtSize(p.size)}, ${p.methods.joinToString("+")})")
            if (verify) {
                val b0 = base
                val n = try { pl.verify(p, { d, _ -> progress((b0 + d).toFloat() / total) }, cancelled) } catch (e: InterruptedException) { return false }
                if (n > 0) { bad += n; log("  E: повреждено блоков: $n") }
            } else {
                Thread.sleep(minOf(600L, 40L + p.size / (40L shl 20)))
            }
            base += p.compressedSize
            progress(base.toFloat() / total)
        }
        if (bad > 0) { log("E: Проверка SHA-256 не пройдена — файл прошивки повреждён (перекачайте его)"); return false }
        if (verify) log("Проверка SHA-256 всех блоков: OK")
        log("Switching active slot to ${target.uppercase()}")
        dev.slot = target
        return true
    }

    private fun flashModule(dev: DeviceState): Boolean {
        log("- Magisk module installer")
        log("- Установка ${info.moduleName} ${info.moduleVersion}" + (info.moduleAuthor.takeIf { it.isNotEmpty() }?.let { " (автор $it)" } ?: ""))
        if (dev.installedRom == null) { log("E: Сначала установите прошивку"); return false }
        log("- Extracting module files")
        Thread.sleep(500)
        val tag = "${info.moduleName} ${info.moduleVersion}".trim()
        if (tag !in dev.modules) dev.modules += tag
        log("- Done")
        return true
    }

    private fun flashGeneric(s: Source): Boolean {
        log("Пакет: ${info.typeTitle}")
        if (info.flashMethod.isNotEmpty() && info.type !in setOf("RECOVERY", "ANYKERNEL", "IMAGE"))
            log("Обычно ставится через: ${info.flashMethod} — эмулирую эту прошивку")
        if (info.asserts.isNotEmpty()) log("assert: устройство ${info.asserts.joinToString(" / ")} — виртуальный телефон принимает любую модель")
        val prints = ArrayList(info.uiPrint)
        var errors = 0
        if (verify) {
            log("Проверка целостности…")
            val sc = RomScanner(verify = true, log = { l -> if (l.contains("✗") || l.contains("MD5") || l.contains("Проверка")) log(l) },
                progress = { d, tot -> progress(if (tot > 0) d.toFloat() / tot * 0.9f else 0f) }, cancelled = cancelled)
            try { errors = sc.scan(s, rom.name).verifyErrors } catch (e: InterruptedException) { log("E: Отменено"); return false }
            if (errors > 0) { log("E: Найдено повреждённых файлов: $errors"); return false }
            log("Контрольные суммы: OK")
        }
        val parts = info.partitions.take(80)
        val step = maxOf(1, parts.size)
        var printed = 0
        parts.forEachIndexed { i, p ->
            if (cancelled()) return false
            // настоящие сообщения updater-script выводим вперемешку с разделами
            val upTo = prints.size * i / step
            while (printed < upTo) log(prints[printed++])
            log("Flashing ${p.name}" + (if (p.size > 0) " (${fmtSize(p.size)})" else "") + "...")
            Thread.sleep(minOf(500L, 60L + p.size / (60L shl 20)))
            progress(0.9f + 0.1f * (i + 1) / step)
        }
        while (printed < prints.size) log(prints[printed++])
        if (info.type == "X86") log("x86-прошивка: после перезагрузки она запустится по-настоящему в QEMU")
        return true
    }
}

/** Настоящий запуск ядра прошивки в qemu-system-aarch64 (машина virt). */
object ArmKernelRunner {
    @Volatile var process: Process? = null; private set
    @Volatile var rom: Rom? = null; private set
    val isRunning get() = process?.isAlive == true

    fun start(ctx: Context, r: Rom): File {
        stop()
        val app = ctx.applicationContext
        val log = r.kernelLog(app)
        val qemu = File(app.applicationInfo.nativeLibraryDir, "libqemu_aarch64.so").absolutePath
        if (!File(qemu).exists()) throw IllegalStateException("В этой сборке нет QEMU ARM64")
        val cpus = 2
        val cmd = mutableListOf(
            qemu, "-M", "virt", "-cpu", "max", "-smp", cpus.toString(), "-m", "2048",
            "-accel", "tcg,thread=multi,tb-size=256",
            "-display", "none", "-monitor", "none", "-no-reboot", "-nodefaults",
            "-serial", "file:" + log.absolutePath,
            "-kernel", r.kernel(app).absolutePath,
            "-append", "console=ttyAMA0 earlycon=pl011,0x9000000 printk.devkmsg=on loglevel=7 " +
                "androidboot.hardware=clmvirt androidboot.selinux=permissive androidboot.boot_devices=clm firmware_class.path=/vendor/firmware",
        )
        if (r.ramdisk(app).exists()) cmd += listOf("-initrd", r.ramdisk(app).absolutePath)
        log.writeText("")
        Log.i("arm: " + cmd.joinToString(" "))
        val p = ProcessBuilder(cmd).directory(app.cacheDir).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(File(app.cacheDir, "qemu-arm.out")))
            .also { it.environment()["LD_LIBRARY_PATH"] = app.applicationInfo.nativeLibraryDir; it.environment()["TMPDIR"] = app.cacheDir.absolutePath }
            .start()
        process = p; rom = r
        return log
    }

    fun stop() {
        process?.let { if (it.isAlive) it.destroy() }
        process = null
    }

    fun stderr(ctx: Context): String = runCatching { File(ctx.cacheDir, "qemu-arm.out").readText().takeLast(2000) }.getOrDefault("")
}

