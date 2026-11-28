package app.clmulator.rom

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** Источник байтов с произвольным доступом (файл, fd от ContentResolver и т.п.). */
interface Source : Closeable {
    val size: Long
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int

    fun readFully(pos: Long, buf: ByteArray, off: Int = 0, len: Int = buf.size) {
        var done = 0
        while (done < len) {
            val n = read(pos + done, buf, off + done, len - done)
            if (n <= 0) throw EOFException("Неожиданный конец файла @${pos + done}")
            done += n
        }
    }

    fun bytes(pos: Long, len: Int): ByteArray = ByteArray(len).also { readFully(pos, it) }

    /** Поток по диапазону [pos, pos+len). */
    fun stream(pos: Long, len: Long): InputStream = RangeStream(this, pos, len)
}

class ChannelSource(private val ch: FileChannel, private val owner: Closeable? = null) : Source {
    override val size: Long = ch.size()
    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int =
        ch.read(ByteBuffer.wrap(buf, off, len), pos)
    override fun close() { ch.close(); owner?.close() }

    companion object {
        fun of(f: File): ChannelSource {
            val raf = RandomAccessFile(f, "r")
            return ChannelSource(raf.channel, raf)
        }
    }
}

/** Кусок другого источника (например payload.bin внутри zip без распаковки). */
class SliceSource(private val parent: Source, private val start: Long, override val size: Long) : Source {
    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= size) return -1
        return parent.read(start + pos, buf, off, minOf(len.toLong(), size - pos).toInt())
    }
    override fun close() {}
}

class ByteSource(private val b: ByteArray) : Source {
    override val size: Long get() = b.size.toLong()
    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= b.size) return -1
        val n = minOf(len.toLong(), b.size - pos).toInt()
        System.arraycopy(b, pos.toInt(), buf, off, n)
        return n
    }
    override fun close() {}
}

class RangeStream(private val src: Source, private var pos: Long, len: Long) : InputStream() {
    private val end = pos + len
    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= end) return -1
        val n = src.read(pos, b, off, minOf(len.toLong(), end - pos).toInt())
        if (n > 0) pos += n
        return n
    }
    override fun skip(n: Long): Long { val k = minOf(n, end - pos).coerceAtLeast(0); pos += k; return k }
    override fun available(): Int = minOf(Int.MAX_VALUE.toLong(), end - pos).toInt()
}

/**
 * Читатель ZIP с поддержкой Zip64 (прошивки бывают по 6–8 ГБ).
 * Не распаковывает архив целиком: читает центральный каталог и даёт доступ
 * к отдельным файлам по смещению — так payload.bin (хранится без сжатия) читается прямо из zip.
 */
class ZipArchive(val src: Source) : Closeable {
    class Entry(
        val name: String,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val localOffset: Long,
    ) {
        @Volatile internal var dataOffset = -1L
        val isDirectory get() = name.endsWith("/")
        override fun toString() = name
    }

    val entries: List<Entry>
    val comment: ByteArray
    private val byName: Map<String, Entry>

    init {
        val tailLen = minOf(src.size, 65557L).toInt()
        val tailPos = src.size - tailLen
        val tail = src.bytes(tailPos, tailLen)
        var eocd = -1
        for (i in tailLen - 22 downTo 0) {
            if (le32(tail, i) == 0x06054b50L) {
                val cl = le16(tail, i + 20)
                if (i + 22 + cl <= tailLen) { eocd = i; break }
            }
        }
        if (eocd < 0) throw IOException("Это не ZIP-архив (нет каталога)")
        val commentLen = le16(tail, eocd + 20)
        comment = tail.copyOfRange(eocd + 22, eocd + 22 + commentLen)
        var count = le16(tail, eocd + 10).toLong()
        var cdSize = le32(tail, eocd + 12)
        var cdOffset = le32(tail, eocd + 16)
        // Zip64
        val locPos = tailPos + eocd - 20
        if (locPos >= 0) {
            val loc = src.bytes(locPos, 20)
            if (le32(loc, 0) == 0x07064b50L) {
                val z64 = src.bytes(le64(loc, 8), 56)
                if (le32(z64, 0) == 0x06064b50L) {
                    count = le64(z64, 32); cdSize = le64(z64, 40); cdOffset = le64(z64, 48)
                }
            }
        }
        if (cdSize > 256L shl 20) throw IOException("Слишком большой каталог ZIP")
        val cd = src.bytes(cdOffset, cdSize.toInt())
        val list = ArrayList<Entry>(count.toInt().coerceAtMost(100000))
        var p = 0
        while (p + 46 <= cd.size && le32(cd, p) == 0x02014b50L) {
            val flags = le16(cd, p + 8)
            val method = le16(cd, p + 10)
            val crc = le32(cd, p + 16)
            var csize = le32(cd, p + 20)
            var usize = le32(cd, p + 24)
            val nl = le16(cd, p + 28); val xl = le16(cd, p + 30); val kl = le16(cd, p + 32)
            var lofs = le32(cd, p + 42)
            val name = String(cd, p + 46, nl, if (flags and 0x800 != 0) Charsets.UTF_8 else Charsets.UTF_8)
            var x = p + 46 + nl
            val xe = x + xl
            while (x + 4 <= xe) {
                val id = le16(cd, x); val sz = le16(cd, x + 2)
                if (id == 1) {
                    var q = x + 4
                    if (usize == 0xFFFFFFFFL) { usize = le64(cd, q); q += 8 }
                    if (csize == 0xFFFFFFFFL) { csize = le64(cd, q); q += 8 }
                    if (lofs == 0xFFFFFFFFL) { lofs = le64(cd, q) }
                }
                x += 4 + sz
            }
            list += Entry(name, method, crc, csize, usize, lofs)
            p += 46 + nl + xl + kl
        }
        entries = list
        byName = list.associateBy { it.name }
    }

    operator fun get(name: String): Entry? = byName[name]

    fun find(pred: (Entry) -> Boolean): Entry? = entries.firstOrNull(pred)

    /** Смещение несжатых данных записи внутри архива. */
    fun dataOffset(e: Entry): Long {
        if (e.dataOffset >= 0) return e.dataOffset
        val h = src.bytes(e.localOffset, 30)
        if (le32(h, 0) != 0x04034b50L) throw IOException("Повреждён заголовок ${e.name}")
        e.dataOffset = e.localOffset + 30 + le16(h, 26) + le16(h, 28)
        return e.dataOffset
    }

    fun open(e: Entry): InputStream {
        val raw = src.stream(dataOffset(e), e.compressedSize)
        return when (e.method) {
            0 -> raw
            8 -> InflaterInputStream(raw, Inflater(true), 1 shl 16)
            else -> throw IOException("Метод сжатия ${e.method} не поддерживается (${e.name})")
        }
    }

    /** Несжатая запись как отдельный источник с произвольным доступом. */
    fun slice(e: Entry): Source {
        require(e.method == 0) { "${e.name} сжат внутри zip" }
        return SliceSource(src, dataOffset(e), e.size)
    }

    fun readBytes(e: Entry, limit: Int = 16 shl 20): ByteArray {
        if (e.size > limit) throw IOException("${e.name} слишком большой (${e.size} Б)")
        return open(e).use { it.readNBytes(e.size.toInt()) }
    }

    fun readText(name: String, limit: Int = 4 shl 20): String? =
        get(name)?.let { String(readBytes(it, limit), Charsets.UTF_8) }

    override fun close() = src.close()

    companion object {
        fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
        fun le32(b: ByteArray, o: Int): Long =
            (le16(b, o).toLong()) or (le16(b, o + 2).toLong() shl 16)
        fun le64(b: ByteArray, o: Int): Long = le32(b, o) or (le32(b, o + 4) shl 32)
    }
}
