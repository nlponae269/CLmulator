package app.clmulator

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.io.DataInputStream
import java.io.RandomAccessFile

/** Фоновая задача (загрузка / импорт) с прогрессом для карточки на главном экране. */
class BgTask(val id: String, val title: String, var subtitle: String) {
    @Volatile var status: String = "Подготовка…"
    @Volatile var progress: Float = -1f   // <0 — неопределённый
    @Volatile var cancelled = false
    @Volatile var done = false
    @Volatile var error: String? = null
}

class CancelledException : Exception("Отменено")

object Tasks {
    private val tasks = LinkedHashMap<String, BgTask>()
    private val listeners = mutableListOf<() -> Unit>()

    fun active(): List<BgTask> = synchronized(tasks) { tasks.values.toList() }

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners += l } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners -= l } }

    private var lastNotify = 0L
    fun changed(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < 150) return
        lastNotify = now
        val ls = synchronized(listeners) { listeners.toList() }
        App.main.post { ls.forEach { it() } }
    }

    fun dismiss(t: BgTask) {
        synchronized(tasks) { tasks.remove(t.id) }
        changed(true)
    }

    private fun start(ctx: Context, t: BgTask, body: (BgTask) -> Unit) {
        synchronized(tasks) { tasks[t.id] = t }
        changed(true)
        ctx.startForegroundService(Intent(ctx, TaskService::class.java))
        Thread({
            try {
                body(t)
                t.done = true
                synchronized(tasks) { tasks.remove(t.id) }
            } catch (e: CancelledException) {
                synchronized(tasks) { tasks.remove(t.id) }
            } catch (e: Throwable) {
                t.error = e.message ?: e.toString()
                t.status = "Ошибка: " + t.error
                Log.e("task ${t.title} failed", e)
            }
            changed(true)
            MachineStore.notifyChanged()
        }, "task-" + t.id).start()
    }

    // ---------------------------------------------------------------- каталог

    fun install(ctx: Context, e: CatalogEntry) {
        val t = BgTask("dl-" + e.id + "-" + System.currentTimeMillis(), "Загрузка прошивки", e.title)
        start(ctx, t) {
            val iso = File(MachineStore.images(ctx), e.isoName)
            if (!iso.exists()) download(t, e.isoUrl, iso)
            t.status = "Распаковка ядра…"; t.progress = -1f; changed(true)
            val m = Machine(
                name = e.title, label = if (e.isCustom) "CLM • ROOT" else "Android-x86",
                version = e.version, api = e.api, color1 = e.color1, color2 = e.color2,
                image = iso.name, edition = e.edition, catalogId = e.id,
                ramMb = e.ramMb, cpus = e.cpus, cpu = e.cpu, dpi = e.dpi,
            )
            prepareMachine(ctx, m, t)
            MachineStore.save(ctx, m)
        }
    }

    private fun download(t: BgTask, url: String, dst: File) {
        val part = File(dst.path + ".part")
        var attempt = 0
        while (true) {
            try {
                downloadOnce(t, url, part)
                break
            } catch (e: CancelledException) {
                throw e
            } catch (e: Exception) {
                if (++attempt >= 5) throw e
                t.status = "Сеть оборвалась, повтор ${attempt}/4…"; changed(true)
                Thread.sleep(2000L * attempt)
            }
        }
        if (!part.renameTo(dst)) throw IllegalStateException("Не удалось сохранить файл")
    }

    private fun downloadOnce(t: BgTask, url: String, part: File) {
        var u = URL(url)
        var conn: HttpURLConnection
        var redirects = 0
        val have = if (part.exists()) part.length() else 0L
        while (true) {
            conn = u.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "CLmulator/1.0")
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw IllegalStateException("Пустой редирект")
                u = URL(u, loc)
                conn.disconnect()
                if (++redirects > 10) throw IllegalStateException("Слишком много редиректов")
                continue
            }
            if (code != 200 && code != 206) throw IllegalStateException("HTTP $code")
            break
        }
        val append = conn.responseCode == 206
        val len = conn.getHeaderFieldLong("Content-Length", -1L)
        val total = if (len > 0) len + (if (append) have else 0) else -1L
        var got = if (append) have else 0L
        conn.inputStream.use { inp ->
            java.io.FileOutputStream(part, append).use { os ->
                val buf = ByteArray(1 shl 16)
                var lastT = System.currentTimeMillis(); var lastB = got; var speed = 0.0
                while (true) {
                    if (t.cancelled) throw CancelledException()
                    val n = inp.read(buf)
                    if (n < 0) break
                    os.write(buf, 0, n)
                    got += n
                    val now = System.currentTimeMillis()
                    if (now - lastT > 500) {
                        speed = (got - lastB) * 1000.0 / (now - lastT)
                        lastT = now; lastB = got
                        t.progress = if (total > 0) got.toFloat() / total else -1f
                        t.status = "Загрузка: " + fmtSize(got) + (if (total > 0) " из " + fmtSize(total) else "") +
                            " • " + fmtSize(speed.toLong()) + "/с"
                        changed()
                    }
                }
            }
        }
        if (total > 0 && got < total) throw java.io.IOException("Файл скачан не полностью")
    }

    // ---------------------------------------------------------------- импорт

    fun import(ctx: Context, uri: Uri) {
        val name = displayName(ctx, uri) ?: "image.iso"
        val t = BgTask("imp-" + System.currentTimeMillis(), "Импорт прошивки", name)
        start(ctx, t) {
            val lower = name.lowercase()
            if (lower.endsWith(".tar") || lower.endsWith(".md5") || lower.endsWith(".zip") || lower.endsWith(".lz4") ||
                lower.endsWith(".7z") || lower.endsWith(".rar") || lower.endsWith(".ozip") || lower.endsWith(".kdz")
            ) {
                throw IllegalArgumentException(
                    "Это прошивка реального телефона (Odin/Recovery/OTA). Она собрана под конкретный процессор " +
                        "(Exynos/Snapdragon/MediaTek) и в эмуляторе не запустится. Нужен ISO или образ диска x86 " +
                        "(например, Android-x86)."
                )
            }
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dst = File(MachineStore.images(ctx), safe)
            if (!dst.exists()) {
                val total = fileSize(ctx, uri)
                val part = File(dst.path + ".part")
                ctx.contentResolver.openInputStream(uri)!!.use { copyWithProgress(t, it, part, total, "Копирование") }
                part.renameTo(dst)
            }
            val isDisk = lower.endsWith(".img") || lower.endsWith(".qcow2") || lower.endsWith(".vdi") ||
                lower.endsWith(".vmdk") || lower.endsWith(".raw")
            val ver = Regex("""(\d)\.(\d)""").find(name)?.value ?: "?"
            val api = Catalog.apiForVersion(ver)
            val m = Machine(
                name = name.substringBeforeLast('.').replace('_', ' ').take(40),
                label = "Импорт", version = ver, api = api,
                color1 = 0xFF78909C.toInt(), color2 = 0xFF37474F.toInt(), image = dst.name,
                bootMode = if (isDisk) Machine.MODE_DISK else Machine.MODE_ANDROID_X86,
                ramMb = if (api in 1..10) 512 else 1024, cpus = if (api in 1..10) 1 else 2,
                cpu = if (api >= 21) "max" else "qemu64",
            )
            prepareMachine(ctx, m, t)
            MachineStore.save(ctx, m)
        }
    }

    /** Достаёт ядро/initrd из ISO, создаёт диск данных. */
    fun prepareMachine(ctx: Context, m: Machine, t: BgTask?) {
        m.dir(ctx).mkdirs()
        if (m.bootMode == Machine.MODE_ANDROID_X86) {
            t?.status = "Анализ образа…"; changed(true)
            val iso = try { Iso9660(m.imageFile(ctx)) } catch (e: Exception) { null }
            val boot = iso?.let { runCatching { Iso9660.probeAndroidX86(it) }.getOrNull() }
            if (iso == null || boot == null) {
                iso?.close()
                m.bootMode = Machine.MODE_CDROM
            } else {
                iso.use {
                    t?.status = "Извлечение ядра…"; changed(true)
                    it.extract(it.find("kernel")!!, m.kernel(ctx))
                    t?.status = "Извлечение initrd…"; changed(true)
                    it.extract(it.find("initrd.img")!!, File(m.dir(ctx), "initrd.img"))
                }
                m.append = boot.append
                if (m.version == "?" && boot.version != null) {
                    m.version = boot.version; m.api = Catalog.apiForVersion(boot.version)
                }
            }
        }
        m.edition?.let { ed ->
            ctx.assets.open("testroms/$ed/initrd.img").use { inp ->
                m.initrd(ctx).outputStream().use { inp.copyTo(it) }
            }
        }
        if (m.version != "?" && m.api > 0 && m.name.isBlank()) m.name = "Android " + m.version
        t?.status = "Готово"; t?.progress = 1f; changed(true)
    }

    /** Разворачивает шаблон ext2 (assets/data-ext2.clm) в разрежённый файл. */
    fun createDataDisk(ctx: Context, dst: File) {
        val tmp = File(dst.path + ".tmp")
        DataInputStream(GZIPInputStream(ctx.assets.open("data-ext2.clm"), 1 shl 16)).use { inp ->
            val magic = ByteArray(8); inp.readFully(magic)
            require(String(magic) == "CLMSPRS1")
            val size = inp.readLong(); val bs = inp.readInt(); val count = inp.readInt()
            RandomAccessFile(tmp, "rw").use { raf ->
                raf.setLength(size)
                val buf = ByteArray(bs)
                repeat(count) {
                    val idx = inp.readInt()
                    inp.readFully(buf)
                    raf.seek(idx.toLong() * bs)
                    raf.write(buf)
                }
            }
        }
        tmp.renameTo(dst)
    }

    private fun copyWithProgress(t: BgTask, inp: InputStream, dst: File, total: Long, what: String) {
        dst.outputStream().use { os ->
            val buf = ByteArray(1 shl 16)
            var got = 0L
            var last = 0L
            while (true) {
                if (t.cancelled) { dst.delete(); throw CancelledException() }
                val n = inp.read(buf)
                if (n < 0) break
                os.write(buf, 0, n)
                got += n
                val now = System.currentTimeMillis()
                if (now - last > 300) {
                    last = now
                    t.progress = if (total > 0) got.toFloat() / total else -1f
                    t.status = "$what: " + fmtSize(got) + if (total > 0) " из " + fmtSize(total) else ""
                    changed()
                }
            }
        }
    }

    private fun displayName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun fileSize(ctx: Context, uri: Uri): Long = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) it.getLong(0) else -1L
        }
    }.getOrNull() ?: -1L
}

fun fmtSize(b: Long): String = when {
    b >= 1L shl 30 -> String.format("%.1f ГБ", b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> String.format("%.0f МБ", b / (1024.0 * 1024))
    b >= 1L shl 10 -> String.format("%.0f КБ", b / 1024.0)
    else -> "$b Б"
}
