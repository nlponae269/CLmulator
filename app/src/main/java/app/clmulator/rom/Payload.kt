package app.clmulator.rom

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.tukaani.xz.XZInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/** Минимальный читатель protobuf (без генерации классов). */
class Proto(private val b: ByteArray, private var p: Int = 0, private val end: Int = b.size) {
    var field = 0; private set
    var wire = 0; private set
    private var lenStart = 0
    private var lenLen = 0
    private var value = 0L

    fun next(): Boolean {
        if (p >= end) return false
        val tag = varint()
        field = (tag ushr 3).toInt(); wire = (tag and 7).toInt()
        when (wire) {
            0 -> value = varint()
            1 -> { value = ZipArchive.le64(b, p); p += 8 }
            2 -> { lenLen = varint().toInt(); lenStart = p; p += lenLen }
            5 -> { value = ZipArchive.le32(b, p); p += 4 }
            else -> throw IOException("protobuf: wire type $wire")
        }
        return true
    }

    fun long() = value
    fun int() = value.toInt()
    fun bool() = value != 0L
    fun bytes(): ByteArray = b.copyOfRange(lenStart, lenStart + lenLen)
    fun string() = String(b, lenStart, lenLen, Charsets.UTF_8)
    fun sub() = Proto(b, lenStart, lenStart + lenLen)

    private fun varint(): Long {
        var r = 0L; var s = 0
        while (true) {
            val c = b[p++].toInt() and 0xff
            r = r or ((c and 0x7f).toLong() shl s)
            if (c < 0x80) return r
            s += 7
        }
    }
}

/**
 * payload.bin — формат A/B OTA (update_engine), так поставляются HyperOS, MIUI (A/B),
 * Pixel, LineageOS, OxygenOS и большинство современных прошивок.
 */
class Payload(val src: Source) {
    class Op(
        val type: Int,
        val dataOffset: Long,
        val dataLength: Long,
        /** пары (start_block, num_blocks) */
        val dst: LongArray,
        val sha256: ByteArray?,
        val hasSrc: Boolean,
    )

    class Partition(
        val name: String,
        val size: Long,
        val hash: ByteArray?,
        val ops: List<Op>,
        val fsType: String?,
        val version: String?,
        val incremental: Boolean,
    ) {
        val compressedSize: Long get() = ops.sumOf { it.dataLength }
        val methods: Set<String> get() = ops.map { opName(it.type) }.toSet()
    }

    class Group(val name: String, val size: Long, val partitions: List<String>)

    val version: Long
    val manifestSize: Long
    val metadataSignatureSize: Int
    val dataStart: Long
    var blockSize = 4096; private set
    var minorVersion = 0; private set
    var maxTimestamp = 0L; private set
    var partialUpdate = false; private set
    var securityPatch: String? = null; private set
    var snapshotEnabled = false; private set
    var vabcEnabled = false; private set
    var vabcCompression: String? = null; private set
    val partitions = ArrayList<Partition>()
    val groups = ArrayList<Group>()

    val isIncremental get() = minorVersion != 0 || partitions.any { it.incremental }
    val totalSize get() = partitions.sumOf { it.size }

    init {
        val h = src.bytes(0, 24)
        if (String(h, 0, 4, Charsets.US_ASCII) != "CrAU") throw IOException("payload.bin: неверная сигнатура")
        version = be64(h, 4)
        manifestSize = be64(h, 12)
        if (version != 2L) throw IOException("payload.bin: версия формата $version не поддерживается")
        metadataSignatureSize = be32(h, 20).toInt()
        if (manifestSize > 64L shl 20) throw IOException("payload.bin: слишком большой манифест")
        dataStart = 24 + manifestSize + metadataSignatureSize
        parseManifest(src.bytes(24, manifestSize.toInt()))
    }

    private fun parseManifest(m: ByteArray) {
        val r = Proto(m)
        while (r.next()) when (r.field) {
            3 -> blockSize = r.int()
            12 -> minorVersion = r.int()
            13 -> partitions += parsePartition(r.sub())
            14 -> maxTimestamp = r.long()
            15 -> parseDynamic(r.sub())
            16 -> partialUpdate = r.bool()
            18 -> securityPatch = r.string()
        }
    }

    private fun parseDynamic(r: Proto) {
        while (r.next()) when (r.field) {
            1 -> {
                val g = r.sub(); var name = ""; var size = 0L; val parts = ArrayList<String>()
                while (g.next()) when (g.field) { 1 -> name = g.string(); 2 -> size = g.long(); 3 -> parts += g.string() }
                groups += Group(name, size, parts)
            }
            2 -> snapshotEnabled = r.bool()
            3 -> vabcEnabled = r.bool()
            4 -> vabcCompression = r.string()
        }
    }

    private fun parsePartition(r: Proto): Partition {
        var name = ""; var size = -1L; var hash: ByteArray? = null; var fs: String? = null; var ver: String? = null
        var hasOld = false
        val ops = ArrayList<Op>()
        while (r.next()) when (r.field) {
            1 -> name = r.string()
            4 -> fs = r.string()
            6 -> hasOld = true
            7 -> { val i = r.sub(); while (i.next()) when (i.field) { 1 -> size = i.long(); 2 -> hash = i.bytes() } }
            8 -> ops += parseOp(r.sub())
            17 -> ver = r.string()
        }
        if (size < 0) size = ops.maxOfOrNull { op ->
            var mx = 0L; for (i in op.dst.indices step 2) mx = maxOf(mx, op.dst[i] + op.dst[i + 1]); mx
        }?.times(blockSize) ?: 0L
        val inc = hasOld || ops.any { it.hasSrc || it.type in INCREMENTAL_OPS }
        return Partition(name, size, hash, ops, fs, ver, inc)
    }

    private fun parseOp(r: Proto): Op {
        var type = 0; var off = 0L; var len = 0L; var sha: ByteArray? = null; var src = false
        val dst = ArrayList<Long>()
        while (r.next()) when (r.field) {
            1 -> type = r.int()
            2 -> off = r.long()
            3 -> len = r.long()
            4 -> src = true
            6 -> { val e = r.sub(); var s = 0L; var n = 0L; while (e.next()) when (e.field) { 1 -> s = e.long(); 2 -> n = e.long() }; dst += s; dst += n }
            8 -> sha = r.bytes()
        }
        return Op(type, off, len, dst.toLongArray(), sha, src)
    }

    fun partition(name: String) = partitions.firstOrNull { it.name == name }

    /** Распаковка раздела в файл (FileChannel). progress(сделано, всего) — в байтах несжатых данных. */
    fun extract(p: Partition, out: FileChannel, progress: (Long, Long) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false }) {
        if (p.incremental) throw IOException("${p.name}: это инкрементальное обновление — нужна предыдущая версия прошивки")
        // нулевые блоки не пишем: файл разрежённый, достаточно задать длину
        if (p.size > 0) out.write(ByteBuffer.wrap(ByteArray(1)), p.size - 1)
        val buf = ByteArray(1 shl 16)
        var done = 0L
        for (op in p.ops) {
            if (cancelled()) throw InterruptedException()
            when (op.type) {
                OP_ZERO, OP_DISCARD -> {}
                OP_REPLACE, OP_REPLACE_BZ, OP_REPLACE_XZ -> {
                    decoder(op).use { inp ->
                        var i = 0
                        while (i < op.dst.size) {
                            var left = op.dst[i + 1] * blockSize
                            var pos = op.dst[i] * blockSize
                            while (left > 0) {
                                val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n <= 0) break
                                val bb = ByteBuffer.wrap(buf, 0, n)
                                while (bb.hasRemaining()) pos += out.write(bb, pos)
                                left -= n
                            }
                            i += 2
                        }
                    }
                }
                else -> throw IOException("${p.name}: операция ${opName(op.type)} не поддерживается")
            }
            for (i in op.dst.indices step 2) done += op.dst[i + 1] * blockSize
            progress(done, p.size)
        }
    }

    /** Поток распакованных данных одной операции. */
    fun decoder(op: Op): InputStream {
        val raw = src.stream(dataStart + op.dataOffset, op.dataLength).buffered(1 shl 16)
        return when (op.type) {
            OP_REPLACE -> raw
            OP_REPLACE_XZ -> XZInputStream(raw)
            OP_REPLACE_BZ -> BZip2CompressorInputStream(raw)
            else -> throw IOException("операция ${opName(op.type)} не поддерживается")
        }
    }

    /** Распаковывает раздел в память (только для маленьких: boot, init_boot, vendor_boot). */
    fun extractBytes(p: Partition, limit: Long = 160L shl 20): ByteArray {
        if (p.size > limit) throw IOException("${p.name} слишком большой")
        val out = ByteArray(p.size.toInt())
        for (op in p.ops) {
            if (op.type == OP_ZERO || op.type == OP_DISCARD) continue
            decoder(op).use { inp ->
                var i = 0
                while (i < op.dst.size) {
                    val pos = (op.dst[i] * blockSize).toInt()
                    val len = (op.dst[i + 1] * blockSize).toInt()
                    var got = 0
                    while (got < len) { val n = inp.read(out, pos + got, len - got); if (n <= 0) break; got += n }
                    i += 2
                }
            }
        }
        return out
    }

    /**
     * Проверка целостности: SHA-256 каждого блока данных сравнивается с манифестом.
     * Возвращает число повреждённых операций.
     */
    fun verify(p: Partition, progress: (Long, Long) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false }): Int {
        var bad = 0
        val total = p.compressedSize
        var done = 0L
        val buf = ByteArray(1 shl 16)
        for (op in p.ops) {
            if (cancelled()) throw InterruptedException()
            val sha = op.sha256 ?: continue
            if (op.dataLength == 0L) continue
            val md = MessageDigest.getInstance("SHA-256")
            var pos = dataStart + op.dataOffset
            var left = op.dataLength
            while (left > 0) {
                val n = src.read(pos, buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) throw IOException("payload.bin обрезан")
                md.update(buf, 0, n); pos += n; left -= n; done += n
                if (done and 0xFFFFF < n) progress(done, total)
            }
            if (!md.digest().contentEquals(sha)) bad++
        }
        progress(total, total)
        return bad
    }

    companion object {
        const val OP_REPLACE = 0
        const val OP_REPLACE_BZ = 1
        const val OP_ZERO = 6
        const val OP_DISCARD = 7
        const val OP_REPLACE_XZ = 8
        const val OP_ZSTD = 14
        val INCREMENTAL_OPS = setOf(2, 3, 4, 5, 9, 10, 11, 12, 13)

        fun opName(t: Int) = when (t) {
            0 -> "RAW"; 1 -> "BZIP2"; 2 -> "MOVE"; 3 -> "BSDIFF"; 4 -> "SOURCE_COPY"; 5 -> "SOURCE_BSDIFF"
            6 -> "ZERO"; 7 -> "DISCARD"; 8 -> "XZ"; 9 -> "PUFFDIFF"; 10 -> "BROTLI_BSDIFF"; 11 -> "ZUCCHINI"
            12, 13 -> "LZ4DIFF"; 14 -> "ZSTD"; else -> "op$t"
        }

        fun be32(b: ByteArray, o: Int): Long =
            ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or
                ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)
        fun be64(b: ByteArray, o: Int): Long = (be32(b, o) shl 32) or be32(b, o + 4)
    }
}
