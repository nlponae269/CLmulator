package app.clmulator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.nio.ByteBuffer

/** Экран гостевой системы: рисует кадры VNC и превращает касания в мышь/тачскрин. */
class VncView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var input: InputSink? = null
    private var bitmap: Bitmap? = null
    private val lock = Any()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect()
    var frames = 0L; private set

    interface InputSink {
        fun touch(x: Int, y: Int, action: Int)   // action: 0 down, 1 move, 2 up
        fun wheel(up: Boolean)
        fun key(keysym: Int, down: Boolean)
        fun text(s: String)
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun resizeGuest(w: Int, h: Int) {
        synchronized(lock) {
            if (w <= 0 || h <= 0) return
            if (bitmap?.width != w || bitmap?.height != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
            }
        }
        postInvalidate()
    }

    fun frame(fb: ByteArray, w: Int, h: Int) {
        synchronized(lock) {
            val b = bitmap ?: return
            if (b.width != w || b.height != h || fb.size < w * h * 4) return
            b.copyPixelsFromBuffer(ByteBuffer.wrap(fb, 0, w * h * 4))
            frames++
        }
        postInvalidate()
    }

    val guestWidth get() = bitmap?.width ?: 0
    val guestHeight get() = bitmap?.height ?: 0

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF000000.toInt())
        synchronized(lock) {
            val b = bitmap ?: return
            val s = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
            val w = (b.width * s).toInt(); val h = (b.height * s).toInt()
            dst.set((width - w) / 2, (height - h) / 2, (width - w) / 2 + w, (height - h) / 2 + h)
            canvas.drawBitmap(b, null, dst, paint)
        }
    }

    private fun toGuest(x: Float, y: Float): Pair<Int, Int> {
        val b = bitmap ?: return 0 to 0
        if (dst.width() == 0) return 0 to 0
        val gx = ((x - dst.left) * b.width / dst.width()).toInt().coerceIn(0, b.width - 1)
        val gy = ((y - dst.top) * b.height / dst.height()).toInt().coerceIn(0, b.height - 1)
        return gx to gy
    }

    private var scrollY0 = 0f
    private var multi = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val sink = input ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multi = false
                requestFocus()
                val (x, y) = toGuest(e.x, e.y); sink.touch(x, y, 0)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!multi) {
                    multi = true
                    val (x, y) = toGuest(e.getX(0), e.getY(0)); sink.touch(x, y, 2)
                    scrollY0 = (e.getY(0) + e.getY(1)) / 2
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (multi && e.pointerCount >= 2) {
                    val yy = (e.getY(0) + e.getY(1)) / 2
                    val step = 40 * resources.displayMetrics.density
                    while (yy - scrollY0 > step) { sink.wheel(true); scrollY0 += step }
                    while (scrollY0 - yy > step) { sink.wheel(false); scrollY0 -= step }
                } else if (!multi) {
                    val (x, y) = toGuest(e.x, e.y); sink.touch(x, y, 1)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!multi) { val (x, y) = toGuest(e.x, e.y); sink.touch(x, y, 2) }
                multi = false
            }
        }
        return true
    }

    // ------------------------------------------------------------ клавиатура

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                input?.text(text.toString()); return true
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(maxOf(1, beforeLength)) { input?.key(Keys.BACKSPACE, true); input?.key(Keys.BACKSPACE, false) }
                return true
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                val ks = Keys.fromAndroid(event.keyCode)
                if (ks != 0) { input?.key(ks, event.action == KeyEvent.ACTION_DOWN); return true }
                if (event.action == KeyEvent.ACTION_DOWN && event.unicodeChar != 0) input?.text(event.unicodeChar.toChar().toString())
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return super.onKeyDown(keyCode, event)
        val ks = Keys.fromAndroid(keyCode)
        if (ks != 0) { input?.key(ks, true); return true }
        val u = event.unicodeChar
        if (u != 0) { input?.text(u.toChar().toString()); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val ks = Keys.fromAndroid(keyCode)
        if (ks != 0 && keyCode != KeyEvent.KEYCODE_BACK) { input?.key(ks, false); return true }
        return super.onKeyUp(keyCode, event)
    }
}

object Keys {
    const val BACKSPACE = 0xff08
    const val SHIFT = 0xffe1

    fun fromAndroid(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_DEL -> 0xff08
        KeyEvent.KEYCODE_FORWARD_DEL -> 0xffff
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 0xff0d
        KeyEvent.KEYCODE_TAB -> 0xff09
        KeyEvent.KEYCODE_ESCAPE -> 0xff1b
        KeyEvent.KEYCODE_DPAD_LEFT -> 0xff51
        KeyEvent.KEYCODE_DPAD_UP -> 0xff52
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0xff53
        KeyEvent.KEYCODE_DPAD_DOWN -> 0xff54
        KeyEvent.KEYCODE_MOVE_HOME -> 0xff50
        KeyEvent.KEYCODE_MOVE_END -> 0xff57
        KeyEvent.KEYCODE_PAGE_UP -> 0xff55
        KeyEvent.KEYCODE_PAGE_DOWN -> 0xff56
        KeyEvent.KEYCODE_SHIFT_LEFT -> 0xffe1
        KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xffe2
        KeyEvent.KEYCODE_CTRL_LEFT -> 0xffe3
        KeyEvent.KEYCODE_CTRL_RIGHT -> 0xffe4
        KeyEvent.KEYCODE_ALT_LEFT -> 0xffe9
        KeyEvent.KEYCODE_ALT_RIGHT -> 0xffea
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 0xffbe + (code - KeyEvent.KEYCODE_F1)
        else -> 0
    }

    private const val SHIFTED = "~!@#$%^&*()_+{}|:\"<>?"

    /** Символ -> (keysym, нужен ли Shift). Гостевая раскладка — en-us. */
    fun forChar(c: Char): Pair<Int, Boolean>? = when {
        c == '\n' -> 0xff0d to false
        c == '\t' -> 0xff09 to false
        c in 'A'..'Z' -> c.code to true
        c in SHIFTED -> c.code to true
        c.code in 0x20..0x7e -> c.code to false
        else -> null
    }
}
