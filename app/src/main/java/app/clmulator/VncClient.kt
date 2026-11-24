package app.clmulator

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Минимальный клиент RFB 3.8 (VNC) для встроенного VNC-сервера QEMU.
 * Подключается к unix-сокету, получает кадры в формате RGBX (как Bitmap ARGB_8888 в памяти).
 */
class VncClient(
    private val socketPath: String,
    private val listener: Listener,
) {
    interface Listener {
        /** Новый размер экрана гостя */
        fun onResize(w: Int, h: Int)
        /** Обновлён кадр; fb — RGBA-байты w*h*4 (alpha=255). Вызывается из потока VNC. */
        fun onFrame(fb: ByteArray, w: Int, h: Int)
        fun onDisconnected(error: String?)
    }

    @Volatile private var running = false
    private var socket: LocalSocket? = null
    private var out: DataOutputStream? = null
    private val outLock = Any()
    var width = 0; private set
    var height = 0; private set
    private var fb = ByteArray(0)

    fun start() {
        running = true
        Thread({ loop() }, "vnc").start()
    }

    fun stop() {
        running = false
        runCatching { socket?.shutdownInput() }
        runCatching { socket?.close() }
    }

    private fun loop() {
        var err: String? = null
        try {
            // QEMU может создать сокет не сразу
            var s: LocalSocket? = null
            for (i in 0 until 100) {
                if (!running) return
                try {
                    s = LocalSocket().apply { connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM)) }
                    break
                } catch (e: IOException) {
                    Thread.sleep(200)
                }
            }
            if (s == null) throw IOException("VNC-сервер не отвечает")
            socket = s
            val inp = DataInputStream(BufferedInputStream(s.inputStream, 1 shl 16))
            out = DataOutputStream(BufferedOutputStream(s.outputStream, 4096))
            handshake(inp)
            requestUpdate(false)
            var lastReq = 0L
            while (running) {
                when (val type = inp.readUnsignedByte()) {
                    0 -> {
                        readUpdate(inp)
                        // ~40 кадров/с максимум
                        val now = System.currentTimeMillis()
                        val wait = 25 - (now - lastReq)
                        if (wait > 0) Thread.sleep(wait)
                        lastReq = System.currentTimeMillis()
                        requestUpdate(true)
                    }
                    1 -> { // SetColourMapEntries
                        inp.readUnsignedByte(); inp.readUnsignedShort()
                        val n = inp.readUnsignedShort(); inp.skipFully(n * 6)
                    }
                    2 -> Unit // Bell
                    3 -> { // ServerCutText
                        inp.skipFully(3); val n = inp.readInt(); inp.skipFully(n)
                    }
                    else -> throw IOException("Неизвестное сообщение VNC $type")
                }
            }
        } catch (e: Exception) {
            if (running) err = e.message ?: e.toString()
        } finally {
            runCatching { socket?.close() }
            val wasRunning = running
            running = false
            if (wasRunning || err != null) listener.onDisconnected(err)
        }
    }

    private fun DataInputStream.skipFully(n: Int) {
        var left = n
        while (left > 0) {
            val s = skipBytes(left)
            if (s <= 0) { readByte(); left-- } else left -= s
        }
    }

    private fun handshake(inp: DataInputStream) {
        val ver = ByteArray(12); inp.readFully(ver)
        val o = out!!
        o.write("RFB 003.008\n".toByteArray()); o.flush()
        val n = inp.readUnsignedByte()
        if (n == 0) {
            val len = inp.readInt(); val b = ByteArray(len); inp.readFully(b)
            throw IOException("VNC отказал: " + String(b))
        }
        val types = ByteArray(n); inp.readFully(types)
        if (types.none { it.toInt() == 1 }) throw IOException("VNC требует пароль")
        o.writeByte(1); o.flush()
        val res = inp.readInt()
        if (res != 0) throw IOException("VNC: ошибка авторизации")
        o.writeByte(1); o.flush() // ClientInit shared
        width = inp.readUnsignedShort(); height = inp.readUnsignedShort()
        inp.skipFully(16) // pixel format
        val nameLen = inp.readInt(); inp.skipFully(nameLen)
        // SetPixelFormat: 32bpp, depth 24, little endian, true colour, R@0 G@8 B@16 -> байты R,G,B,X
        synchronized(outLock) {
            o.writeByte(0); o.write(ByteArray(3))
            o.writeByte(32); o.writeByte(24); o.writeByte(0); o.writeByte(1)
            o.writeShort(255); o.writeShort(255); o.writeShort(255)
            o.writeByte(0); o.writeByte(8); o.writeByte(16)
            o.write(ByteArray(3))
            // SetEncodings: CopyRect, Raw, DesktopSize
            val enc = intArrayOf(1, 0, -223)
            o.writeByte(2); o.writeByte(0); o.writeShort(enc.size)
            enc.forEach { o.writeInt(it) }
            o.flush()
        }
        resize(width, height)
    }

    private fun resize(w: Int, h: Int) {
        width = w; height = h
        fb = ByteArray(w * h * 4)
        listener.onResize(w, h)
    }

    private fun readUpdate(inp: DataInputStream) {
        inp.readUnsignedByte()
        val rects = inp.readUnsignedShort()
        var resized = false
        for (r in 0 until rects) {
            val x = inp.readUnsignedShort(); val y = inp.readUnsignedShort()
            val w = inp.readUnsignedShort(); val h = inp.readUnsignedShort()
            when (val enc = inp.readInt()) {
                0 -> { // Raw
                    val stride = width * 4
                    val row = w * 4
                    for (yy in 0 until h) {
                        val off = (y + yy) * stride + x * 4
                        if (off + row <= fb.size) inp.readFully(fb, off, row) else inp.skipFully(row)
                        var i = off + 3
                        val end = minOf(off + row, fb.size)
                        while (i < end) { fb[i] = -1; i += 4 }
                    }
                }
                1 -> { // CopyRect
                    val sx = inp.readUnsignedShort(); val sy = inp.readUnsignedShort()
                    val stride = width * 4
                    val tmp = ByteArray(w * 4 * h)
                    for (yy in 0 until h) System.arraycopy(fb, (sy + yy) * stride + sx * 4, tmp, yy * w * 4, w * 4)
                    for (yy in 0 until h) System.arraycopy(tmp, yy * w * 4, fb, (y + yy) * stride + x * 4, w * 4)
                }
                -223 -> { resize(w, h); resized = true }
                else -> throw IOException("Неподдерживаемая кодировка $enc")
            }
        }
        listener.onFrame(fb, width, height)
        if (resized) requestUpdate(false)
    }

    private fun requestUpdate(incremental: Boolean) {
        val o = out ?: return
        synchronized(outLock) {
            o.writeByte(3); o.writeByte(if (incremental) 1 else 0)
            o.writeShort(0); o.writeShort(0); o.writeShort(width); o.writeShort(height)
            o.flush()
        }
    }

    /** Абсолютный указатель (для usb-tablet). mask: 1 — левая, 4 — правая, 8/16 — колесо */
    fun pointer(x: Int, y: Int, mask: Int) = send {
        it.writeByte(5); it.writeByte(mask)
        it.writeShort(x.coerceIn(0, maxOf(0, width - 1))); it.writeShort(y.coerceIn(0, maxOf(0, height - 1)))
    }

    fun key(keysym: Int, down: Boolean) = send {
        it.writeByte(4); it.writeByte(if (down) 1 else 0); it.writeShort(0); it.writeInt(keysym)
    }

    private fun send(block: (DataOutputStream) -> Unit) {
        val o = out ?: return
        IoThread.post {
            try {
                synchronized(outLock) { block(o); o.flush() }
            } catch (_: IOException) {
            }
        }
    }
}

/** Один фоновый поток для отправки ввода (нельзя писать в сокет из UI-потока). */
object IoThread {
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "clm-io") }
    fun post(r: () -> Unit) { executor.execute(r) }
}
