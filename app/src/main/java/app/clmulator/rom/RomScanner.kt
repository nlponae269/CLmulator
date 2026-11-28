package app.clmulator.rom

import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** Найденный внутри прошивки файл. */
class FileRec(val path: String, val size: Long, val kind: String) {
    val base: String get() = path.substringAfterLast('/')
}

class ScanResult {
    val files = ArrayList<FileRec>()
    /** маленькие служебные файлы: скрипты, prop, xml, метаданные */
    val small = LinkedHashMap<String, ByteArray>()
    /** boot / init_boot / vendor_boot / recovery (распакованные из lz4) */
    val boots = LinkedHashMap<String, ByteArray>()
    var payloadPath: String? = null
    var payload: Payload? = null
    var payloadInStream = false
    val formats = LinkedHashSet<String>()
    var encrypted: String? = null
    var isoPath: String? = null
    /** логические разделы super (из метаданных LP) */
    val superPartitions = ArrayList<Pair<String, Long>>()
    /** разделы из UPDATE.APP / PAC */
    val packedPartitions = ArrayList<Pair<String, Long>>()
    var productName: String? = null
    var productVersion: String? = null
    val verifyLog = ArrayList<String>()
    var verifyErrors = 0

    fun text(pred: (String) -> Boolean): Pair<String, String>? =
        small.entries.firstOrNull { pred(it.key) }?.let { it.key to String(it.value, Charsets.UTF_8) }
    fun textByBase(vararg names: String) = text { k -> names.any { k.substringAfterLast('/').equals(it, true) } }
}

/**
 * Рекурсивный разбор прошивки любого производителя. Тип определяется по сигнатурам, а не по расширению.
 * verify=true — дополнительно проверяет контрольные суммы (CRC zip, MD5 Odin, CRC gzip).
 */
class RomScanner(
    private val verify: Boolean = false,
    private val log: (String) -> Unit = {},
    private val progress: (Long, Long) -> Unit = { _, _ -> },
    private val cancelled: () -> Boolean = { false },
) {
    val r = ScanResult()
    private var topSize = 0L
    private var lastProgress = 0L

    fun scan(src: Source, name: String): ScanResult {
        topSize = src.size
        scanSource("", src, name, depth = 0)
        return r
    }

    private fun check() { if (cancelled()) throw InterruptedException("Отменено") }

    private fun report(pos: Long) {
        val now = System.currentTimeMillis()
        if (now - lastProgress > 200) { lastProgress = now; progress(pos, topSize) }
    }

    // ------------------------------------------------------------------ сигнатуры

    private fun sniff(h: ByteArray, name: String): String {
        fun at(o: Int, s: String) = h.size >= o + s.length && String(h, o, s.length, Charsets.ISO_8859_1) == s
        fun le(o: Int) = if (h.size >= o + 4) ZipArchive.le32(h, o) else -1L
        val lower = name.lowercase()
        return when {
            at(0, "PK\u0003\u0004") || at(0, "PK\u0005\u0006") -> "zip"
            at(257, "ustar") -> "tar"
            h.size > 2 && h[0] == 0x1f.toByte() && h[1] == 0x8b.toByte() -> "gzip"
            le(0) == 0x184D2204L -> "lz4"
            at(0, "CrAU") -> "payload"
            at(0, "ANDROID!") -> "boot"
            at(0, "VNDRBOOT") -> "vendor_boot"
            le(0) == 0xED26FF3AL -> "sparse"
            le(92) == 0xA55AAA55L -> "huawei_app"
            at(0, "B\u0000P\u0000_\u0000R\u0000") -> "pac"
            at(0, "OPPOENCRYPT!") -> "ozip"
            le(1024) == 0xE0F5E1E2L -> "erofs"
            h.size > 1081 && h[1080] == 0x53.toByte() && h[1081] == 0xEF.toByte() -> "ext4"
            le(4096) == 0x616C4467L -> "super"
            at(32769, "CD001") -> "iso"
            h.size > 6 && h[0] == 0xFD.toByte() && at(1, "7zXZ") -> "xz"
            at(0, "7z¼¯") -> "7z"
            at(0, "Rar!") -> "rar"
            lower.endsWith(".kdz") || lower.endsWith(".dz") -> "kdz"
            lower.endsWith(".ops") -> "ops"
            else -> "file"
        }
    }

    private fun isInteresting(base: String): Boolean {
        val b = base.lowercase()
        return b == "metadata" || b == "updater-script" || b == "payload_properties.txt" ||
            b == "module.prop" || b.endsWith(".prop") || b == "flash_all.sh" || b == "flash-all.sh" ||
            b == "flash_all_except_storage.sh" || b == "android-info.txt" || b == "flashfile.xml" || b == "servicefile.xml" ||
            b.contains("scatter") && b.endsWith(".txt") || b.startsWith("rawprogram") && b.endsWith(".xml") ||
            b == "misc_info.txt" || b == "otacert" || b == "boot_version.txt" || b == "version.txt" ||
            b == "vendor_version.txt" || b == "sw_version.txt" || b == "ota_metadata" || b == "metadata.pb" ||
            b == "product_name.txt" || b == "info.txt" || b == "anykernel.sh" || b.endsWith(".cfg") && b.length < 40
    }

    private fun isBootName(base: String): Boolean {
        val b = base.lowercase().removeSuffix(".lz4")
        return b.endsWith(".img") && (b.startsWith("boot") || b.startsWith("init_boot") || b.startsWith("vendor_boot") ||
            b.startsWith("recovery")) || b in setOf("boot", "init_boot", "vendor_boot", "recovery", "kernel", "boot.bin")
    }

    // ------------------------------------------------------------------ произвольный доступ

    private fun scanSource(prefix: String, src: Source, name: String, depth: Int) {
        check()
        val path = prefix + name
        val headSize = minOf(src.size, 32780L).toInt()
        val h = src.bytes(0, headSize)
        val kind = sniff(h, name)
        r.files += FileRec(path, src.size, kind)
        if (depth > 6) return
        when (kind) {
            "zip" -> { r.formats += "zip"; scanZip(path + "/", src) }
            "tar" -> { r.formats += if (name.lowercase().endsWith(".md5")) "tar.md5" else "tar"; scanTarRandom(path + "/", src, name) }
            "gzip" -> { r.formats += "gzip"; scanStream("$path/", GZIPInputStream(src.stream(0, src.size).counting(0), 1 shl 16), name.removeSuffixes(".gz", ".tgz") + if (name.endsWith(".tgz")) ".tar" else "", depth + 1, -1, sniffOnly = false) }
            "lz4" -> handleLz4(prefix, name, src.stream(0, src.size), src.size, depth)
            "payload" -> {
                r.formats += "payload.bin"
                if (r.payload == null) {
                    r.payload = Payload(src); r.payloadPath = path
                }
            }
            "boot", "vendor_boot" -> if (src.size <= BOOT_LIMIT) r.boots[path] = src.bytes(0, src.size.toInt())
            "huawei_app" -> { r.formats += "UPDATE.APP"; scanHuaweiApp(path + "/", src, depth) }
            "pac" -> { r.formats += "pac"; scanPac(path + "/", src, depth) }
            "super" -> { r.formats += "super"; parseSuper(src.bytes(0, minOf(src.size, 65536L).toInt())) }
            "sparse" -> sparseHead(src.stream(0, src.size))?.let { inner -> sniffInnerImage(path, inner) }
            "iso" -> { r.formats += "iso"; if (r.isoPath == null) r.isoPath = path }
            "ozip", "ops", "kdz" -> { r.encrypted = kind; r.formats += kind }
            "7z", "rar", "xz" -> r.formats += kind
            else -> if (isInteresting(name) && src.size <= SMALL_LIMIT) r.small[path] = src.bytes(0, src.size.toInt())
        }
    }

    private fun scanZip(prefix: String, src: Source) {
        val z = ZipArchive(src)
        if (verify) log("Проверка архива: ${z.entries.size} файлов")
        for (e in z.entries) {
            check()
            if (e.isDirectory) continue
            val base = e.name.substringAfterLast('/')
            val off = runCatching { z.dataOffset(e) }.getOrNull() ?: continue
            report(off)
            if (e.method == 0) {
                val slice = z.slice(e)
                scanSource(prefix + e.name.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }, slice, base, prefix.count { it == '/' } + 1)
                if (verify && sniff(slice.bytes(0, minOf(slice.size, 300L).toInt()), base) != "payload") verifyCrc(z, e, prefix)
            } else {
                // сжатая запись: смотрим начало, интересное читаем целиком, вложенные архивы — потоком
                val head = z.open(e).use { it.readNBytes(minOf(e.size, 32780L).toInt()) }
                val kind = sniff(head, base)
                val p = prefix + e.name
                when {
                    kind in setOf("zip", "tar", "gzip", "lz4", "huawei_app") -> {
                        r.formats += kind
                        scanStream(prefix + e.name.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" },
                            z.open(e), base, prefix.count { it == '/' } + 1, e.size, crc = if (verify) e.crc else null)
                        continue
                    }
                    (kind == "boot" || kind == "vendor_boot") && e.size <= BOOT_LIMIT -> {
                        r.files += FileRec(p, e.size, kind); r.boots[p] = z.readBytes(e, BOOT_LIMIT.toInt())
                    }
                    else -> {
                        r.files += FileRec(p, e.size, kind)
                        if (kind == "payload") r.payloadInStream = true
                        if (kind in setOf("ozip", "ops", "kdz")) r.encrypted = kind
                        if (kind == "super") parseSuper(head)
                        if (kind == "iso" && r.isoPath == null) r.isoPath = p
                        if (isInteresting(base) && e.size <= SMALL_LIMIT) r.small[p] = z.readBytes(e, SMALL_LIMIT.toInt())
                    }
                }
                if (verify) verifyCrc(z, e, prefix)
            }
        }
    }

    private fun verifyCrc(z: ZipArchive, e: ZipArchive.Entry, prefix: String) {
        val crc = CRC32()
        val buf = ByteArray(1 shl 16)
        val off = z.dataOffset(e)
        z.open(e).use { inp ->
            var got = 0L
            while (true) {
                check()
                val n = inp.read(buf); if (n < 0) break
                crc.update(buf, 0, n); got += n
                if (got and 0x3FFFFF < n) report(off + got * e.compressedSize / maxOf(1, e.size))
            }
        }
        val ok = crc.value == e.crc
        if (!ok) r.verifyErrors++
        if (!ok || e.size > (8 shl 20)) log((if (ok) "  ✓ " else "  ✗ ОШИБКА CRC: ") + prefix + e.name)
    }

    private fun scanTarRandom(prefix: String, src: Source, name: String) {
        var pos = 0L
        var longName: String? = null
        while (pos + 512 <= src.size) {
            check(); report(pos)
            val h = src.bytes(pos, 512)
            if (h.all { it == 0.toByte() }) break
            val size = tarSize(h)
            val type = h[156].toInt().toChar()
            val n = longName ?: tarName(h)
            longName = null
            val dataPos = pos + 512
            when (type) {
                'L' -> longName = String(src.bytes(dataPos, size.toInt().coerceAtMost(4096)), Charsets.UTF_8).trimEnd('\u0000')
                '0', '\u0000', '7' -> if (!n.endsWith("/")) {
                    val dir = n.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                    scanSource(prefix + dir, SliceSource(src, dataPos, size), n.substringAfterLast('/'), prefix.count { it == '/' } + 1)
                }
            }
            pos = dataPos + (size + 511) / 512 * 512
        }
        if (name.lowercase().endsWith(".md5")) checkOdinMd5(src, prefix.removeSuffix("/"))
    }

    /** Odin-архив: в конце .tar.md5 дописан MD5 самого tar. */
    private fun checkOdinMd5(src: Source, path: String) {
        val tailLen = minOf(src.size, 512L).toInt()
        val tail = String(src.bytes(src.size - tailLen, tailLen), Charsets.ISO_8859_1)
        val m = Regex("([0-9a-fA-F]{32})\\s+\\S+\\s*$").find(tail) ?: return
        val tarLen = src.size - tailLen + m.range.first
        if (!verify) return
        log("Проверка MD5: ${path.substringAfterLast('/')}")
        val md = MessageDigest.getInstance("MD5")
        val buf = ByteArray(1 shl 16)
        var p = 0L
        while (p < tarLen) {
            check()
            val n = src.read(p, buf, 0, minOf(buf.size.toLong(), tarLen - p).toInt())
            if (n <= 0) break
            md.update(buf, 0, n); p += n
            report(p)
        }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        val ok = got.equals(m.groupValues[1], true)
        if (!ok) r.verifyErrors++
        log(if (ok) "  ✓ MD5 совпадает" else "  ✗ MD5 НЕ совпадает (файл повреждён)")
    }

    private fun scanHuaweiApp(prefix: String, src: Source, depth: Int) {
        var pos = 92L
        while (pos + 98 <= src.size) {
            check(); report(pos)
            val h = src.bytes(pos, 98)
            if (ZipArchive.le32(h, 0) != 0xA55AAA55L) break
            val hlen = ZipArchive.le32(h, 4)
            val fsize = ZipArchive.le32(h, 24)
            var e = 28 + 32; while (e < 28 + 32 + 16 && h[e] != 0.toByte()) e++
            val pname = String(h, 60, e - 60, Charsets.ISO_8859_1).trim().ifEmpty { "part" }
            r.packedPartitions += pname to fsize
            val dataPos = pos + hlen
            if (fsize in 1..65536 && pname.uppercase() in setOf("VERSION", "CURVER", "VERLIST", "PACKAGE_TYPE", "BASE_VERSION", "CUST_VERSION", "PRELOAD_VERSION"))
                r.small[prefix + pname.lowercase() + ".txt"] = src.bytes(dataPos, fsize.toInt())
            scanSource(prefix, SliceSource(src, dataPos, fsize), pname.lowercase() + ".img", depth + 1)
            pos = dataPos + fsize
            pos = (pos + 3) / 4 * 4
        }
    }

    private fun scanPac(prefix: String, src: Source, depth: Int) {
        val h = src.bytes(0, 2124)
        r.productName = utf16(h, 52, 256)
        r.productVersion = utf16(h, 564, 256)
        val count = ZipArchive.le32(h, 1076).toInt()
        var off = ZipArchive.le32(h, 1080)
        repeat(count.coerceAtMost(512)) {
            check()
            val fe = src.bytes(off, 2580)
            val esz = ZipArchive.le32(fe, 0).let { if (it in 1..65536) it else 2580L }
            val id = utf16(fe, 4, 256)
            val fname = utf16(fe, 516, 256)
            val fsize = ZipArchive.le32(fe, 1540)
            val doff = ZipArchive.le32(fe, 1552)
            if (fsize > 0 && doff + fsize <= src.size) {
                r.packedPartitions += id to fsize
                scanSource(prefix, SliceSource(src, doff, fsize), fname.ifEmpty { "$id.img" }, depth + 1)
            }
            off += esz
        }
    }

    private fun utf16(b: ByteArray, o: Int, chars: Int): String {
        val sb = StringBuilder()
        for (i in 0 until chars) {
            if (o + i * 2 + 1 >= b.size) break
            val c = ZipArchive.le16(b, o + i * 2); if (c == 0) break; sb.append(c.toChar())
        }
        return sb.toString().trim()
    }

    /** Метаданные динамических разделов (super): имена и размеры логических разделов. */
    private fun parseSuper(b: ByteArray) {
        if (r.superPartitions.isNotEmpty()) return
        val mh = 4096 + 4096 * 2
        if (b.size < mh + 256 || ZipArchive.le32(b, mh) != 0x414C5030L) return
        val hsize = ZipArchive.le32(b, mh + 8).toInt()
        val tables = mh + hsize
        val pOff = ZipArchive.le32(b, mh + 80).toInt(); val pNum = ZipArchive.le32(b, mh + 84).toInt(); val pEs = ZipArchive.le32(b, mh + 88).toInt()
        val eOff = ZipArchive.le32(b, mh + 92).toInt(); val eNum = ZipArchive.le32(b, mh + 96).toInt(); val eEs = ZipArchive.le32(b, mh + 100).toInt()
        for (i in 0 until pNum.coerceAtMost(256)) {
            val o = tables + pOff + i * pEs
            if (o + 52 > b.size) break
            var e = o; while (e < o + 36 && b[e] != 0.toByte()) e++
            val name = String(b, o, e - o, Charsets.US_ASCII)
            val first = ZipArchive.le32(b, o + 40).toInt(); val num = ZipArchive.le32(b, o + 44).toInt()
            var sectors = 0L
            for (k in first until first + num) {
                val xo = tables + eOff + k * eEs
                if (k >= eNum || xo + 8 > b.size) break
                sectors += ZipArchive.le64(b, xo)
            }
            r.superPartitions += name to sectors * 512
        }
    }

    /** Первые блоки sparse-образа (чтобы узнать, что внутри: super/ext4/erofs). */
    private fun sparseHead(inp: InputStream, want: Int = 16384): ByteArray? = runCatching {
        inp.use { s ->
            val h = s.readNBytes(28)
            val hdrSz = ZipArchive.le16(h, 8); val chSz = ZipArchive.le16(h, 10); val blk = ZipArchive.le32(h, 12).toInt()
            s.skipNBytes((hdrSz - 28).toLong())
            val out = java.io.ByteArrayOutputStream()
            while (out.size() < want) {
                val c = s.readNBytes(chSz); if (c.size < 12) break
                val type = ZipArchive.le16(c, 0); val blocks = ZipArchive.le32(c, 4); val total = ZipArchive.le32(c, 8)
                val bytes = blocks * blk
                when (type) {
                    0xCAC1 -> {
                        val take = minOf(bytes, (want - out.size()).toLong()).toInt()
                        out.write(s.readNBytes(take))
                        if (take < bytes) break
                    }
                    0xCAC2 -> { val f = s.readNBytes(4); val n = minOf(bytes, (want - out.size()).toLong()).toInt(); repeat(n / 4) { out.write(f) } }
                    0xCAC3 -> { val n = minOf(bytes, (want - out.size()).toLong()).toInt(); out.write(ByteArray(n)) }
                    else -> s.skipNBytes(total - chSz)
                }
            }
            out.toByteArray()
        }
    }.getOrNull()

    private fun sniffInnerImage(path: String, inner: ByteArray) {
        val k = sniff(inner, path)
        r.files[r.files.lastIndex] = FileRec(path, r.files.last().size, "sparse:$k")
        if (k == "super") { r.formats += "super"; parseSuper(inner) }
    }

    private fun handleLz4(prefix: String, name: String, inp: InputStream, size: Long, depth: Int) {
        val inner = name.removeSuffix(".lz4")
        val lz = BufferedInputStream(Lz4InputStream(inp), 1 shl 16)
        lz.mark(40000)
        val head = lz.readNBytes(32780)
        lz.reset()
        val kind = sniff(head, inner)
        val path = prefix + name
        r.files[r.files.lastIndex] = FileRec(path, size, "lz4:$kind")
        r.formats += "lz4"
        when {
            (kind == "boot" || kind == "vendor_boot") -> {
                val data = Unpack.readLimited(lz, BOOT_LIMIT.toInt()); r.boots[prefix + inner] = data
            }
            kind == "super" -> parseSuper(head)
            kind == "sparse" -> sparseHead(lz)?.let { sniffInnerImage(path, it) }
            kind == "tar" || kind == "zip" -> scanStream(prefix, lz, inner, depth + 1, -1)
            isInteresting(inner) -> r.small[prefix + inner] = Unpack.readLimited(lz, SMALL_LIMIT.toInt())
        }
        lz.close()
    }

    // ------------------------------------------------------------------ потоковый разбор

    private fun scanStream(prefix: String, raw: InputStream, name: String, depth: Int, size: Long,
                           sniffOnly: Boolean = false, crc: Long? = null) {
        check()
        val crcIn = if (crc != null) CrcStream(raw) else null
        val inp = BufferedInputStream(crcIn ?: raw, 1 shl 16)
        inp.mark(40000)
        val head = inp.readNBytes(32780)
        inp.reset()
        val kind = sniff(head, name)
        val path = prefix + name
        if (r.files.none { it.path == path }) r.files += FileRec(path, size, kind)
        if (depth > 6 || sniffOnly) { inp.close(); return }
        try {
            when (kind) {
                "tar" -> { r.formats += if (name.lowercase().endsWith(".md5")) "tar.md5" else "tar"; scanTarStream("$path/", inp, depth) }
                "gzip" -> { r.formats += "gzip"; scanStream("$path/", GZIPInputStream(inp, 1 shl 16), name.removeSuffixes(".gz", ".tgz") + if (name.endsWith(".tgz")) ".tar" else "", depth + 1, -1) }
                "zip" -> scanZipStream("$path/", inp, depth)
                "lz4" -> handleLz4(prefix, name, inp, size, depth)
                "boot", "vendor_boot" -> r.boots[path] = Unpack.readLimited(inp, BOOT_LIMIT.toInt())
                "payload" -> r.payloadInStream = true
                "super" -> parseSuper(head)
                "sparse" -> sparseHead(inp)?.let { sniffInnerImage(path, it) }
                "iso" -> if (r.isoPath == null) r.isoPath = path
                "ozip", "ops", "kdz" -> r.encrypted = kind
                else -> if (isInteresting(name) && (size in 0..SMALL_LIMIT || size < 0)) r.small[path] = Unpack.readLimited(inp, SMALL_LIMIT.toInt())
            }
            if (crcIn != null) {
                // дочитываем, чтобы посчитать CRC целиком
                val buf = ByteArray(1 shl 16); while (inp.read(buf) >= 0) check()
                val ok = crcIn.crc.value == crc
                if (!ok) r.verifyErrors++
                log((if (ok) "  ✓ " else "  ✗ ОШИБКА CRC: ") + path)
            }
        } finally { runCatching { inp.close() } }
    }

    private fun scanTarStream(prefix: String, inp: InputStream, depth: Int) {
        var longName: String? = null
        while (true) {
            check()
            val h = inp.readNBytes(512)
            if (h.size < 512 || h.all { it == 0.toByte() }) break
            val size = tarSize(h)
            val type = h[156].toInt().toChar()
            val n = longName ?: tarName(h)
            longName = null
            val body = BoundedStream(inp, size)
            when (type) {
                'L' -> longName = String(body.readNBytes(size.toInt().coerceAtMost(4096)), Charsets.UTF_8).trimEnd('\u0000')
                '0', '\u0000', '7' -> if (!n.endsWith("/")) {
                    val dir = n.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                    scanStream(prefix + dir, NoClose(body), n.substringAfterLast('/'), depth + 1, size)
                }
            }
            body.drain()
            val pad = (512 - size % 512) % 512
            inp.skipNBytes(pad)
        }
    }

    private fun scanZipStream(prefix: String, inp: InputStream, depth: Int) {
        r.formats += "zip"
        val z = ZipInputStream(inp)
        while (true) {
            check()
            val e = z.nextEntry ?: break
            if (e.isDirectory) continue
            val dir = e.name.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            scanStream(prefix + dir, NoClose(z), e.name.substringAfterLast('/'), depth + 1, e.size)
        }
    }

    private fun tarSize(h: ByteArray): Long {
        if (h[124].toInt() and 0x80 != 0) {
            var v = 0L; for (i in 125 until 136) v = (v shl 8) or (h[i].toLong() and 0xff); return v
        }
        val s = String(h, 124, 12, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
        return if (s.isEmpty()) 0 else s.toLong(8)
    }

    private fun tarName(h: ByteArray): String {
        fun z(o: Int, n: Int): String { var e = o; while (e < o + n && h[e] != 0.toByte()) e++; return String(h, o, e - o, Charsets.UTF_8) }
        val name = z(0, 100)
        val pre = if (String(h, 257, 5, Charsets.ISO_8859_1) == "ustar") z(345, 155) else ""
        return (if (pre.isNotEmpty()) "$pre/$name" else name).removePrefix("./")
    }

    private fun InputStream.counting(start: Long): InputStream = object : FilterInputStream(this) {
        var pos = start
        override fun read(): Int { val c = super.read(); if (c >= 0) { pos++; report(pos) }; return c }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len); if (n > 0) { pos += n; report(pos) }; return n
        }
    }

    private class BoundedStream(private val inp: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int { if (left <= 0) return -1; val c = inp.read(); if (c >= 0) left--; return c }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = inp.read(b, off, minOf(len.toLong(), left).toInt()); if (n > 0) left -= n; return n
        }
        fun drain() { val buf = ByteArray(1 shl 16); while (left > 0) { if (read(buf) < 0) break } }
        override fun close() {}
    }

    private class NoClose(i: InputStream) : FilterInputStream(i) { override fun close() {} }

    private class CrcStream(i: InputStream) : FilterInputStream(i) {
        val crc = CRC32()
        override fun read(): Int { val c = super.read(); if (c >= 0) crc.update(c); return c }
        override fun read(b: ByteArray, off: Int, len: Int): Int { val n = super.read(b, off, len); if (n > 0) crc.update(b, off, n); return n }
    }

    companion object {
        const val SMALL_LIMIT = 4L shl 20
        const val BOOT_LIMIT = 160L shl 20
    }
}

private fun String.removeSuffixes(vararg s: String): String { var r = this; s.forEach { r = r.removeSuffix(it) }; return r }
