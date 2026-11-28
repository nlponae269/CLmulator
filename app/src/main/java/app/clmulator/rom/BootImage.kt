package app.clmulator.rom

import java.io.ByteArrayOutputStream

/**
 * Образы boot.img / init_boot.img / recovery.img (magic ANDROID!) и vendor_boot.img (VNDRBOOT).
 * Поддерживаются заголовки v0–v4.
 */
class BootImage private constructor(
    val kind: String,
    val headerVersion: Int,
    val pageSize: Int,
    val kernel: ByteArray,
    /** ramdisk как есть (обычно сжат) */
    val ramdisk: ByteArray,
    val cmdline: String,
    val name: String,
    val osVersion: String?,
    val patchLevel: String?,
    val bootconfig: String?,
) {
    val hasKernel get() = kernel.isNotEmpty()

    /** Ядро без сжатия (для QEMU и поиска версии). */
    val kernelRaw: ByteArray by lazy { if (kernel.isEmpty()) kernel else runCatching { Unpack.auto(kernel) }.getOrDefault(kernel) }

    val kernelCompression: String get() = compressionOf(kernel)
    val ramdiskCompression: String get() = compressionOf(ramdisk)

    /** Строка "Linux version …" из ядра */
    val kernelVersion: String? by lazy { findLinuxVersion(kernelRaw) }

    val isArm64Image: Boolean get() = kernelRaw.size > 64 && String(kernelRaw, 56, 4, Charsets.ISO_8859_1) == "ARMd"

    val ramdiskFiles: Map<String, ByteArray> by lazy {
        runCatching { Cpio.read(Unpack.auto(ramdisk, 512 shl 20)) }.getOrDefault(emptyMap())
    }

    companion object {
        fun isBoot(b: ByteArray) = b.size >= 8 && String(b, 0, 8, Charsets.ISO_8859_1) == "ANDROID!"
        fun isVendorBoot(b: ByteArray) = b.size >= 8 && String(b, 0, 8, Charsets.ISO_8859_1) == "VNDRBOOT"

        fun parse(b: ByteArray): BootImage? = when {
            isBoot(b) -> parseBoot(b)
            isVendorBoot(b) -> parseVendor(b)
            else -> null
        }

        private fun u32(b: ByteArray, o: Int) = ZipArchive.le32(b, o).toInt()
        private fun cstr(b: ByteArray, o: Int, n: Int): String {
            var e = o; while (e < o + n && e < b.size && b[e] != 0.toByte()) e++
            return String(b, o, e - o, Charsets.UTF_8).trim()
        }
        private fun align(v: Int, a: Int) = (v + a - 1) / a * a
        private fun slice(b: ByteArray, o: Int, n: Int): ByteArray =
            if (n <= 0 || o >= b.size) ByteArray(0) else b.copyOfRange(o, minOf(b.size, o + n))

        private fun parseBoot(b: ByteArray): BootImage {
            val hv = u32(b, 40)
            if (hv >= 3) {
                val ks = u32(b, 8); val rs = u32(b, 12); val osv = u32(b, 16)
                val page = 4096
                val kOff = page
                val rOff = kOff + align(ks, page)
                val (v, p) = decodeOs(osv)
                return BootImage("boot", hv, page, slice(b, kOff, ks), slice(b, rOff, rs), cstr(b, 44, 1536), "", v, p, null)
            }
            val ks = u32(b, 8); val rs = u32(b, 16); val page = u32(b, 36).let { if (it in 2048..65536) it else 2048 }
            val osv = u32(b, 44)
            val (v, p) = decodeOs(osv)
            val cmd = cstr(b, 64, 512) + cstr(b, 608, 1024).let { if (it.isEmpty()) "" else " $it" }
            val kOff = page
            val rOff = kOff + align(ks, page)
            return BootImage("boot", hv, page, slice(b, kOff, ks), slice(b, rOff, rs), cmd, cstr(b, 48, 16), v, p, null)
        }

        private fun parseVendor(b: ByteArray): BootImage {
            val hv = u32(b, 8); val page = u32(b, 12).let { if (it in 2048..65536) it else 4096 }
            val rs = u32(b, 24)
            val cmd = cstr(b, 28, 2048)
            val name = cstr(b, 2080, 16)
            val hs = u32(b, 2096); val dtb = u32(b, 2100)
            val rOff = align(hs, page)
            var bootconfig: String? = null
            if (hv >= 4) {
                val tableSize = u32(b, 2112); val bcSize = u32(b, 2124)
                val bcOff = rOff + align(rs, page) + align(dtb, page) + align(tableSize, page)
                if (bcSize in 1..(1 shl 20)) bootconfig = String(slice(b, bcOff, bcSize), Charsets.UTF_8).trimEnd('\u0000')
            }
            return BootImage("vendor_boot", hv, page, ByteArray(0), slice(b, rOff, rs), cmd, name, null, null, bootconfig)
        }

        /** os_version: A.B.C и год-месяц патча безопасности */
        fun decodeOs(v: Int): Pair<String?, String?> {
            if (v == 0) return null to null
            val ver = v ushr 11; val patch = v and 0x7ff
            val a = (ver ushr 14) and 0x7f; val bb = (ver ushr 7) and 0x7f; val c = ver and 0x7f
            val y = (patch ushr 4) + 2000; val m = patch and 0xf
            val vs = if (a == 0) null else listOf(a, bb, c).joinToString(".").removeSuffix(".0").removeSuffix(".0")
            val ps = if (m in 1..12) String.format("%04d-%02d", y, m) else null
            return vs to ps
        }

        fun compressionOf(b: ByteArray): String = when {
            b.isEmpty() -> "—"
            b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte() -> "gzip"
            b.size >= 4 && ZipArchive.le32(b, 0) == 0x184C2102L -> "lz4-legacy"
            b.size >= 4 && ZipArchive.le32(b, 0) == 0x184D2204L -> "lz4"
            b.size > 6 && String(b, 0, 6, Charsets.ISO_8859_1) == "070701" -> "cpio"
            b.size > 64 && String(b, 56, 4, Charsets.ISO_8859_1) == "ARMd" -> "Image (без сжатия)"
            b.size > 5 && b[0] == 0xFD.toByte() -> "xz"
            else -> "raw"
        }

        fun findLinuxVersion(b: ByteArray): String? {
            val pat = "Linux version ".toByteArray()
            var i = 0
            outer@ while (i <= b.size - pat.size) {
                for (j in pat.indices) if (b[i + j] != pat[j]) { i++; continue@outer }
                var e = i
                while (e < b.size && e - i < 400 && b[e] != 0.toByte() && b[e] != '\n'.code.toByte()) e++
                val s = String(b, i, e - i, Charsets.ISO_8859_1)
                // пропускаем строку-формат "Linux version %s"
                if (!s.contains('%') && s.length > 20) return s.trim()
                i = e
            }
            return null
        }
    }
}

/** Чтение cpio (newc) — содержимое ramdisk. Возвращает только обычные файлы. */
object Cpio {
    fun read(b: ByteArray, maxFiles: Int = 20000): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var p = 0
        while (p + 110 <= b.size && out.size < maxFiles) {
            val magic = String(b, p, 6, Charsets.ISO_8859_1)
            if (magic != "070701" && magic != "070702") {
                // несколько склеенных архивов (vendor ramdisk + generic): ищем следующий заголовок
                val n = indexOf(b, "070701".toByteArray(), p + 1); if (n < 0) break; p = n; continue
            }
            fun hex(o: Int) = String(b, p + o, 8, Charsets.ISO_8859_1).toLong(16)
            val mode = hex(14); val fsize = hex(54).toInt(); val nsize = hex(94).toInt()
            val name = String(b, p + 110, maxOf(0, nsize - 1), Charsets.UTF_8)
            var d = (p + 110 + nsize + 3) and 3.inv()
            if (name == "TRAILER!!!") { p = (d + 3) and 3.inv(); continue }
            if (d + fsize > b.size) break
            if ((mode and 0xF000L) == 0x8000L) out[name] = b.copyOfRange(d, d + fsize)
            d = (d + fsize + 3) and 3.inv()
            p = d
        }
        return out
    }

    private fun indexOf(b: ByteArray, pat: ByteArray, from: Int): Int {
        var i = from
        outer@ while (i <= b.size - pat.size) {
            for (j in pat.indices) if (b[i + j] != pat[j]) { i++; continue@outer }
            return i
        }
        return -1
    }

    /** Склеить несколько ramdisk (как делает загрузчик: vendor ramdisk, затем generic). */
    fun concat(parts: List<ByteArray>): ByteArray {
        val o = ByteArrayOutputStream()
        parts.forEach { o.write(it) }
        return o.toByteArray()
    }
}
