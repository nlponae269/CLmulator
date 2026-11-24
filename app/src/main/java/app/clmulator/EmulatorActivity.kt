package app.clmulator

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class EmulatorActivity : Activity(), VncClient.Listener, VncView.InputSink {
    private lateinit var screen: VncView
    private lateinit var overlay: LinearLayout
    private lateinit var overlayLog: TextView
    private lateinit var overlayTitle: TextView
    private var vnc: VncClient? = null
    private var machine: Machine? = null
    private var tablet = true
    private val qmpListener: () -> Unit = { onVmState() }

    // позиция курсора PS/2-мыши (режим относительной мыши для Android < 4.0)
    private var curX = 0
    private var curY = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val m = QemuManager.machine
        if (m == null || !QemuManager.isRunning) { finish(); return }
        machine = m
        tablet = QemuManager.usesTablet(m)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
        }
        val stage = FrameLayout(this)
        screen = VncView(this).also { it.input = this }
        stage.addView(screen, FrameLayout.LayoutParams(-1, -1))

        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6000000.toInt())
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        overlayTitle = TextView(this).apply {
            text = "Запуск ${m.name}…"
            setTextColor(Color.WHITE); textSize = 20f; typeface = Typeface.DEFAULT_BOLD
        }
        val hint = TextView(this).apply {
            text = "Эмуляция x86 на ARM: первая загрузка занимает от 30 секунд до нескольких минут."
            setTextColor(0xFFB0BEC5.toInt()); textSize = 13f
            setPadding(0, dp(4), 0, dp(12))
        }
        overlayLog = TextView(this).apply {
            setTextColor(0xFF9CCC65.toInt()); textSize = 11f; typeface = Typeface.MONOSPACE
            gravity = Gravity.BOTTOM
        }
        val hide = TextView(this).apply {
            text = "Скрыть лог"; setTextColor(0xFF90CAF9.toInt()); textSize = 14f
            setPadding(0, dp(12), 0, 0)
            setOnClickListener { this@EmulatorActivity.overlay.visibility = View.GONE }
        }
        overlay.addView(overlayTitle); overlay.addView(hint)
        overlay.addView(overlayLog, LinearLayout.LayoutParams(-1, 0, 1f)); overlay.addView(hide)
        stage.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(stage, LinearLayout.LayoutParams(0, -1, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF15191C.toInt())
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        fun btn(icon: Int, desc: String, click: (View) -> Unit) {
            bar.addView(ImageButton(this).apply {
                setImageResource(icon); contentDescription = desc
                setColorFilter(0xFFE0E0E0.toInt())
                background = null
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener(click)
                setOnLongClickListener { android.widget.Toast.makeText(context, desc, android.widget.Toast.LENGTH_SHORT).show(); true }
            }, LinearLayout.LayoutParams(dp(56), 0, 1f))
        }
        btn(R.drawable.ic_back, "Назад") { sendKey("esc") }
        btn(R.drawable.ic_home, "Домой") { sendKey(if (m.api in 1..13) "home" else "ac_home") }
        btn(R.drawable.ic_menu, "Меню") { sendKey("menu") }
        btn(R.drawable.ic_keyboard, "Клавиатура") { toggleKeyboard() }
        btn(R.drawable.ic_power, "Питание") { sendKey("power", hold = 150) }
        btn(R.drawable.ic_more, "Ещё") { v -> moreMenu(v) }
        root.addView(bar, LinearLayout.LayoutParams(dp(64), -1))
        setContentView(root)

        QemuManager.addListener(qmpListener)
        vnc = VncClient(QemuManager.vncSocket(this), this).also { it.start() }
        pollLog()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun pollLog() {
        if (isFinishing || isDestroyed) return
        if (overlay.visibility == View.VISIBLE) {
            val log = runCatching {
                val f = QemuManager.serialLog(this)
                if (f.exists()) {
                    val len = f.length()
                    f.inputStream().use { s ->
                        if (len > 6000) s.skip(len - 6000)
                        String(s.readBytes(), Charsets.UTF_8)
                    }
                } else ""
            }.getOrDefault("")
            overlayLog.text = log.lines().takeLast(18).joinToString("\n")
                .replace(Regex("\u001B\\[[0-9;?]*[A-Za-z]"), "")
            // графический режим включился — убираем лог
            if (screen.guestWidth >= 640 && screen.guestHeight >= 480 && screen.frames > 20 &&
                System.currentTimeMillis() - QemuManager.startedAt > 8000
            ) overlay.visibility = View.GONE
            overlayTitle.text = "Запуск ${machine?.name}… " + ((System.currentTimeMillis() - QemuManager.startedAt) / 1000) + " с"
        }
        screen.postDelayed({ pollLog() }, 700)
    }

    private fun onVmState() {
        if (!QemuManager.isRunning) {
            val err = QemuManager.lastError
            if (err != null && !isFinishing) {
                MaterialAlertDialogBuilder(this).setTitle("Машина не запустилась")
                    .setMessage(err).setPositiveButton("OK") { _, _ -> finish() }
                    .setOnDismissListener { finish() }.show()
            } else finish()
        }
    }

    private fun moreMenu(v: View) {
        val p = PopupMenu(this, v)
        p.menu.add(0, 1, 0, "Показать лог загрузки")
        p.menu.add(0, 2, 0, "Кнопка питания ACPI")
        p.menu.add(0, 3, 0, "Перезагрузить (reset)")
        p.menu.add(0, 4, 0, "Выключить машину")
        p.menu.add(0, 5, 0, "Alt+F1 (консоль)")
        p.menu.add(0, 6, 0, "Alt+F7 (Android)")
        p.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> overlay.visibility = View.VISIBLE
                2 -> QemuManager.powerButton()
                3 -> QemuManager.reset()
                4 -> confirmStop()
                5 -> sendKeys("alt", "f1")
                6 -> sendKeys("alt", "f7")
            }
            true
        }
        p.show()
    }

    private fun confirmStop() {
        MaterialAlertDialogBuilder(this).setTitle("Выключить ${machine?.name}?")
            .setMessage("Несохранённые данные внутри гостевой системы могут потеряться.")
            .setPositiveButton("Выключить") { _, _ -> QemuManager.stop() }
            .setNegativeButton("Отмена", null).show()
    }

    private fun toggleKeyboard() {
        screen.requestFocus()
        val imm = getSystemService(InputMethodManager::class.java)
        imm.showSoftInput(screen, InputMethodManager.SHOW_FORCED)
    }

    private fun sendKey(qcode: String, hold: Int = 80) = IoThread.post {
        QemuManager.qmp?.let { if (it.connect()) it.sendKeys(qcode, holdMs = hold) }
    }

    private fun sendKeys(vararg q: String) = IoThread.post {
        QemuManager.qmp?.let { if (it.connect()) it.sendKeys(*q) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // системная «назад» = Back в гостевой системе
        sendKey("esc")
    }

    override fun onDestroy() {
        QemuManager.removeListener(qmpListener)
        vnc?.stop()
        super.onDestroy()
    }

    // ------------------------------------------------------------ VncClient.Listener

    override fun onResize(w: Int, h: Int) = screen.resizeGuest(w, h)
    override fun onFrame(fb: ByteArray, w: Int, h: Int) = screen.frame(fb, w, h)
    override fun onDisconnected(error: String?) {
        // QEMU мог перезапустить VNC (reset) — переподключаемся, пока машина жива
        runOnUiThread {
            if (!isFinishing && QemuManager.isRunning) {
                screen.postDelayed({
                    if (!isFinishing && QemuManager.isRunning) {
                        vnc = VncClient(QemuManager.vncSocket(this), this).also { it.start() }
                    }
                }, 1000)
            }
        }
    }

    // ------------------------------------------------------------ ввод

    override fun touch(x: Int, y: Int, action: Int) {
        if (tablet) {
            vnc?.pointer(x, y, if (action == 2) 0 else 1)
            return
        }
        IoThread.post {
            val q = QemuManager.qmp ?: return@post
            if (!q.connect()) return@post
            when (action) {
                0 -> {
                    // уводим курсор в угол (там он упирается), затем ставим точно в точку касания
                    repeat(8) { q.inputEvents(QmpClient.rel(-250, -250)) }
                    curX = 0; curY = 0
                    moveTo(q, x, y)
                    q.inputEvents(QmpClient.btn("left", true))
                }
                1 -> moveTo(q, x, y)
                2 -> { moveTo(q, x, y); q.inputEvents(QmpClient.btn("left", false)) }
            }
        }
    }

    private fun moveTo(q: QmpClient, x: Int, y: Int) {
        var dx = x - curX; var dy = y - curY
        while (dx != 0 || dy != 0) {
            val sx = dx.coerceIn(-120, 120); val sy = dy.coerceIn(-120, 120)
            q.inputEvents(QmpClient.rel(sx, sy))
            dx -= sx; dy -= sy
        }
        curX = x; curY = y
    }

    override fun wheel(up: Boolean) {
        if (tablet) {
            val m = if (up) 8 else 16
            vnc?.pointer(lastX(), lastY(), m); vnc?.pointer(lastX(), lastY(), 0)
        } else IoThread.post {
            QemuManager.qmp?.let {
                if (it.connect()) {
                    it.inputEvents(QmpClient.btn(if (up) "wheel-up" else "wheel-down", true))
                    it.inputEvents(QmpClient.btn(if (up) "wheel-up" else "wheel-down", false))
                }
            }
        }
    }

    private fun lastX() = screen.guestWidth / 2
    private fun lastY() = screen.guestHeight / 2

    override fun key(keysym: Int, down: Boolean) { vnc?.key(keysym, down) }

    override fun text(s: String) {
        for (c in s) {
            val (ks, shift) = Keys.forChar(c) ?: continue
            if (shift) vnc?.key(Keys.SHIFT, true)
            vnc?.key(ks, true); vnc?.key(ks, false)
            if (shift) vnc?.key(Keys.SHIFT, false)
        }
    }
}
