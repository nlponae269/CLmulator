package app.clmulator.rom

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Распаковка LZ4: формат кадров (Samsung *.img.lz4, magic 04 22 4D 18)
 * и legacy-формат ядра Linux (ядра/ramdisk в boot.img, magic 02 21 4C 18).
 */
class Lz4InputStream(input: InputStream) : InputStream() {
    private val inp = DataInputStream(input)
    // история (64 КБ) + текущий блок
    private var win = ByteArray(0)
    private var winLen = 0
    private var outPos = 0
    private var mode = 0           // 0 — нужен заголовок, 1 — frame, 2 — legacy, 3 — конец
    private var blockChecksum = false
    private var contentChecksum = false
    private var independent = true
    private var maxBlock = 4 shl 20
    private var cbuf = ByteArray(0)

    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        while (outPos >= winLen) if (!fill()) return -1
        val n = minOf(len, winLen - outPos)
        System.arraycopy(win, outPos, b, off, n)
        outPos += n
        return n
    }

    private fun readMagic(): Boolean {
        val m = try { Integer.reverseBytes(inp.readInt()) } catch (e: EOFException) { mode = 3; return false }
        return handleMagic(m)
    }

    private fun handleMagic(m: Int): Boolean {
        when {
            m == 0x184D2204 -> {
                val flg = inp.readUnsignedByte(); val bd = inp.readUnsignedByte()
                independent = flg and 0x20 != 0
                blockChecksum = flg and 0x10 != 0
                contentChecksum = flg and 0x04 != 0
                maxBlock = when ((bd shr 4) and 7) { 4 -> 64 shl 10; 5 -> 256 shl 10; 6 -> 1 shl 20; else -> 4 shl 20 }
                if (flg and 0x08 != 0) inp.skipNBytes(8)
                if (flg and 0x01 != 0) inp.skipNBytes(4)
                inp.skipNBytes(1) // HC
                mode = 1
            }
            m == 0x184C2102 -> { mode = 2; maxBlock = 8 shl 20; independent = true }
            (m and 0xFFFFFFF0.toInt()) == 0x184D2A50 -> { // skippable frame
                val sz = Integer.reverseBytes(inp.readInt()); inp.skipNBytes(sz.toLong() and 0xFFFFFFFFL)
            }
            else -> { mode = 3; return false }
        }
        return true
    }

    private fun fill(): Boolean {
        while (true) {
            if (mode == 3) return false
            if (mode == 0 && !readMagic()) return false
            if (mode == 0) continue
            val raw = try { Integer.reverseBytes(inp.readInt()) } catch (e: EOFException) { mode = 3; return false }
            if (mode == 1 && raw == 0) {
                if (contentChecksum) inp.skipNBytes(4)
                mode = 0; continue
            }
            if (mode == 2 && (raw == 0x184C2102 || raw == 0x184D2204)) {
                // следующий поток подряд (ramdisk из нескольких частей)
                handleMagic(raw); continue
            }
            val uncompressed = mode == 1 && raw < 0
            val size = raw and 0x7FFFFFFF
            if (size > (maxBlock + (maxBlock shr 6) + 64) || size < 0) {
                if (mode == 2) { mode = 3; return false }
                throw IOException("LZ4: повреждённый блок")
            }
            if (cbuf.size < size) cbuf = ByteArray(size)
            inp.readFully(cbuf, 0, size)
            if (blockChecksum && mode == 1) inp.skipNBytes(4)
            // переносим хвост истории в начало
            val keep = if (independent) 0 else minOf(winLen, 65536)
            val need = keep + maxBlock
            val nw = if (win.size >= need) win else ByteArray(need)
            if (keep > 0) System.arraycopy(win, winLen - keep, nw, 0, keep)
            win = nw
            winLen = keep; outPos = keep
            if (uncompressed) {
                System.arraycopy(cbuf, 0, win, winLen, size); winLen += size
            } else {
                winLen = decodeBlock(cbuf, size, win, winLen)
            }
            if (winLen > outPos) return true
        }
    }

    companion object {
        /** Распаковка одного LZ4-блока; возвращает новую длину выходного буфера. */
        fun decodeBlock(src: ByteArray, srcLen: Int, dst: ByteArray, dstStart: Int): Int {
            var s = 0; var d = dstStart
            while (s < srcLen) {
                val token = src[s++].toInt() and 0xff
                var lit = token ushr 4
                if (lit == 15) { while (true) { val c = src[s++].toInt() and 0xff; lit += c; if (c != 255) break } }
                if (d + lit > dst.size) throw IOException("LZ4: выход за буфер")
                System.arraycopy(src, s, dst, d, lit); s += lit; d += lit
                if (s >= srcLen) break
                val offset = (src[s].toInt() and 0xff) or ((src[s + 1].toInt() and 0xff) shl 8); s += 2
                if (offset == 0 || offset > d) throw IOException("LZ4: неверное смещение")
                var ml = token and 15
                if (ml == 15) { while (true) { val c = src[s++].toInt() and 0xff; ml += c; if (c != 255) break } }
                ml += 4
                if (d + ml > dst.size) throw IOException("LZ4: выход за буфер")
                var m = d - offset
                if (offset >= ml) { System.arraycopy(dst, m, dst, d, ml); d += ml }
                else repeat(ml) { dst[d++] = dst[m++] }
            }
            return d
        }

        fun isLz4(b: ByteArray, o: Int = 0) = b.size >= o + 4 &&
            (ZipArchive.le32(b, o) == 0x184D2204L || ZipArchive.le32(b, o) == 0x184C2102L)
    }
}

/** Распаковывает данные, если они сжаты gzip / lz4 / xz; иначе возвращает как есть. */
object Unpack {
    fun auto(b: ByteArray, limit: Int = 256 shl 20): ByteArray {
        val s = stream(b) ?: return b
        return s.use { readLimited(it, limit) }
    }

    fun stream(b: ByteArray): InputStream? = when {
        b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte() -> java.util.zip.GZIPInputStream(b.inputStream(), 1 shl 16)
        Lz4InputStream.isLz4(b) -> Lz4InputStream(b.inputStream())
        b.size > 6 && b[0] == 0xFD.toByte() && String(b, 1, 4, Charsets.ISO_8859_1) == "7zXZ" -> org.tukaani.xz.XZInputStream(b.inputStream())
        else -> null
    }

    fun readLimited(inp: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = try { inp.read(buf) } catch (e: IOException) { if (out.size() > 0) -1 else throw e }
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw IOException("Распакованные данные слишком большие")
        }
        return out.toByteArray()
    }
}
