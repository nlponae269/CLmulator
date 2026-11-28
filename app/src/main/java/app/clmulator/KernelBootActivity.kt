package app.clmulator

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.RandomAccessFile

/**
 * Настоящая эмуляция: ядро Linux из boot.img прошивки + её ramdisk стартуют в qemu-system-aarch64 (машина virt).
 * Ядро и первый этап init Android реально исполняются; дальше система останавливается,
 * потому что нет драйверов и разделов настоящего телефона.
 */
class KernelBootActivity : ComponentActivity() {
    private lateinit var logTv: TextView
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var stopBtn: TextView
    private var rom: Rom? = null
    private var logFile: File? = null
    private var readPos = 0L
    private var started = 0L
    private val sb = StringBuilder()
    private val milestones = LinkedHashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val r = RomStore.get(this, intent.getStringExtra("rom")) ?: run { finish(); return }
        rom = r
        val info = r.info(this)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(dp(12), dp(8), dp(12), dp(8)) }
        col.addView(TextView(this).apply {
            text = "Запуск ядра: ${info.title}"; setTextColor(Color.WHITE); textSize = 17f; typeface = Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(this).apply {
            text = "QEMU ARM64 (virt, 2 ядра, 2 ГБ). Это настоящее исполнение ядра и init из прошивки. " +
                "До рабочего стола оно не дойдёт: нет драйверов и разделов настоящего телефона."
            setTextColor(0xFF9AA0A6.toInt()); textSize = 12f
        })
        status = TextView(this).apply { setTextColor(0xFFFFD54F.toInt()); textSize = 13f; setPadding(0, dp(6), 0, dp(6)) }
        col.addView(status)
        logTv = TextView(this).apply { setTextColor(0xFF9CCC65.toInt()); textSize = 10.5f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        scroll = ScrollView(this).apply { addView(logTv) }
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        stopBtn = TextView(this).apply {
            text = "■ Остановить"; setTextColor(Color.WHITE); textSize = 15f; setPadding(0, dp(12), 0, dp(6))
            setOnClickListener { if (ArmKernelRunner.isRunning) ArmKernelRunner.stop() else start() }
        }
        col.addView(stopBtn)
        ViewCompat.setOnApplyWindowInsetsListener(col) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(dp(12), b.top + dp(8), dp(12), b.bottom + dp(8)); ins
        }
        setContentView(col)
        start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun start() {
        val r = rom ?: return
        sb.clear(); milestones.clear(); readPos = 0
        try {
            logFile = ArmKernelRunner.start(this, r)
            started = System.currentTimeMillis()
            stopBtn.text = "■ Остановить"
            poll()
        } catch (e: Exception) {
            status.text = "Не удалось запустить: ${e.message}"
        }
    }

    private fun poll() {
        if (isFinishing) return
        val f = logFile ?: return
        runCatching {
            RandomAccessFile(f, "r").use { raf ->
                if (raf.length() > readPos) {
                    raf.seek(readPos)
                    val b = ByteArray(minOf(256 * 1024L, raf.length() - readPos).toInt())
                    raf.readFully(b); readPos += b.size
                    val s = String(b, Charsets.UTF_8).replace("\r", "")
                    sb.append(s)
                    detect(s)
                    if (sb.length > 120_000) sb.delete(0, sb.length - 100_000)
                    logTv.text = sb
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
        val sec = (System.currentTimeMillis() - started) / 1000
        val running = ArmKernelRunner.isRunning
        status.text = (if (running) "Работает ${sec} с" else "Остановлено") +
            (if (milestones.isNotEmpty()) " • " + milestones.joinToString(" → ") else "") +
            (if (!running && sb.isEmpty()) "\n" + ArmKernelRunner.stderr(this).ifBlank { "Ядро ничего не вывело (возможно, в нём нет драйвера PL011 для консоли QEMU)" } else "")
        if (!running) stopBtn.text = "↻ Запустить снова"
        if (running || readPos < f.length()) logTv.postDelayed({ poll() }, 400)
    }

    private fun detect(s: String) {
        if ("Linux version" in s) milestones += "ядро стартовало"
        if ("Run /init" in s || "Freeing unused kernel" in s) milestones += "init из ramdisk"
        if ("init: init first stage started" in s) milestones += "Android init (1-й этап)"
        if ("Kernel panic" in s) milestones += "kernel panic"
        if ("InitFatalReboot" in s || "reboot: Restarting" in s || "Reboot failed" in s) milestones += "init остановился"
    }

    override fun onDestroy() {
        if (isFinishing) ArmKernelRunner.stop()
        super.onDestroy()
    }
}
