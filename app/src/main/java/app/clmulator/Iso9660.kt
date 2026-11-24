package app.clmulator

import java.io.File
import java.io.RandomAccessFile

/**
 * Минимальный читатель ISO9660 (+ имена Rock Ridge, если есть).
 * Нужен, чтобы достать из ISO Android-x86 ядро, initrd и isolinux.cfg
 * и грузиться напрямую (без загрузочного меню и с нашими параметрами).
 */
class Iso9660(file: File) : AutoCloseable {
    private val raf = RandomAccessFile(file, "r")

    class Entry(val name: String, val lba: Long, val size: Long, val isDir: Boolean)

    private val rootLba: Long
    private val rootSize: Long

    init {
        val pvd = ByteArray(2048)
        raf.seek(16L * 2048)
        raf.readFully(pvd)
        require(pvd[0].toInt() == 1 && String(pvd, 1, 5, Charsets.US_ASCII) == "CD001") { "Это не ISO9660-образ" }
        rootLba = le32(pvd, 156 + 2)
        rootSize = le32(pvd, 156 + 10)
    }

    fun list(lba: Long = rootLba, size: Long = rootSize): List<Entry> {
        val out = ArrayList<Entry>()
        val buf = ByteArray(size.toInt().coerceAtMost(1 shl 20))
        raf.seek(lba * 2048)
        raf.readFully(buf)
        var off = 0
        while (off < buf.size) {
            val len = buf[off].toInt() and 0xff
            if (len == 0) {
                // переход на следующий сектор
                off = ((off / 2048) + 1) * 2048
                continue
            }
            val extLba = le32(buf, off + 2)
            val extSize = le32(buf, off + 10)
            val flags = buf[off + 25].toInt()
            val nameLen = buf[off + 32].toInt() and 0xff
            var name = String(buf, off + 33, nameLen, Charsets.ISO_8859_1)
            if (!(nameLen == 1 && (buf[off + 33].toInt() == 0 || buf[off + 33].toInt() == 1))) {
                // Rock Ridge "NM"
                var su = off + 33 + nameLen + (if (nameLen % 2 == 0) 1 else 0)
                var rr: String? = null
                while (su + 4 <= off + len) {
                    val sig = String(buf, su, 2, Charsets.ISO_8859_1)
                    val sl = buf[su + 2].toInt() and 0xff
                    if (sl < 4) break
                    if (sig == "NM" && sl > 5) rr = (rr ?: "") + String(buf, su + 5, sl - 5, Charsets.UTF_8)
                    su += sl
                }
                if (rr != null) name = rr
                else name = name.substringBefore(';').trimEnd('.')
                out += Entry(name, extLba, extSize, flags and 2 != 0)
            }
            off += len
        }
        return out
    }

    fun find(path: String): Entry? {
        var lba = rootLba
        var size = rootSize
        val parts = path.trim('/').split('/')
        for ((i, p) in parts.withIndex()) {
            val e = list(lba, size).firstOrNull { it.name.equals(p, ignoreCase = true) } ?: return null
            if (i == parts.lastIndex) return e
            if (!e.isDir) return null
            lba = e.lba; size = e.size
        }
        return null
    }

    fun extract(e: Entry, dst: File) {
        dst.parentFile?.mkdirs()
        val tmp = File(dst.path + ".tmp")
        tmp.outputStream().use { os ->
            raf.seek(e.lba * 2048)
            val buf = ByteArray(1 shl 16)
            var left = e.size
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                os.write(buf, 0, n)
                left -= n
            }
        }
        tmp.renameTo(dst)
    }

    fun readText(e: Entry): String {
        val b = ByteArray(e.size.toInt().coerceAtMost(1 shl 20))
        raf.seek(e.lba * 2048)
        raf.readFully(b)
        return String(b, Charsets.UTF_8)
    }

    override fun close() = raf.close()

    private fun le32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xff) or ((b[o + 1].toLong() and 0xff) shl 8) or
            ((b[o + 2].toLong() and 0xff) shl 16) or ((b[o + 3].toLong() and 0xff) shl 24))

    companion object {
        /** Описание загрузки Android-x86, найденное внутри ISO */
        data class AndroidX86Boot(val append: String, val version: String?)

        /**
         * Ищет kernel/initrd.img/system.* и берёт строку append из первого пункта isolinux.cfg.
         * Возвращает null, если ISO не похож на Android-x86.
         */
        fun probeAndroidX86(iso: Iso9660): AndroidX86Boot? {
            val root = iso.list()
            val names = root.map { it.name.lowercase() }
            if ("kernel" !in names || "initrd.img" !in names) return null
            if (names.none { it.startsWith("system") } && "ramdisk.img" !in names) return null
            val cfg = iso.find("isolinux/isolinux.cfg")?.let { iso.readText(it) } ?: ""
            var append = cfg.lines().map { it.trim() }
                .firstOrNull { it.startsWith("append", true) && "INSTALL=1" !in it && "DEBUG=" !in it.replace("DEBUG= ", "") }
                ?.substringAfter(' ')?.trim()
                ?: "root=/dev/ram0 androidboot.hardware=android_x86 quiet SRC= DATA="
            append = append.split(Regex("\\s+")).filterNot { it.startsWith("initrd=") }.joinToString(" ")
            val ver = Regex("""(?:Android-x86|android-x86)[ _-]?(\d\.\d)""").find(cfg)?.groupValues?.get(1)
            return AndroidX86Boot(append, ver)
        }
    }
}
