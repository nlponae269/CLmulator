package app.clmulator

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Симуляция загрузки виртуального телефона с установленной прошивкой и режим fastboot.
 * Прошивка телефона не может стартовать на чужом железе, поэтому здесь — честно подписанная симуляция
 * с настоящими данными прошивки, а реальный запуск ядра — в KernelBootActivity.
 */
class SimBootActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private val h = App.main
    private var step = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        if (intent.getStringExtra("mode") == "fastboot") fastboot() else bootSequence()
    }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun text(t: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        typeface = if (mono) Typeface.MONOSPACE else if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun button(t: String, click: () -> Unit) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(22).toFloat() }
        setOnClickListener { click() }
    }

    private fun simTag() = text("СИМУЛЯЦИЯ", 10f, 0x99FFFFFF.toInt()).apply {
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background = GradientDrawable().apply { setStroke(dp(1), 0x66FFFFFF); cornerRadius = dp(4).toFloat() }
    }

    // ------------------------------------------------------------------ загрузка

    private fun bootSequence() {
        val d = DeviceState.load()
        val rom = RomStore.get(this, d.installedRom) ?: run { finish(); return }
        val info = rom.info(this)
        d.bootCount++; d.save()

        // 1. предупреждение о разблокированном загрузчике
        root.removeAllViews()
        val warn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(32), 0, dp(32), 0) }
        warn.addView(text("⚠", 42f, 0xFFFF9800.toInt()).apply { gravity = Gravity.CENTER })
        warn.addView(text("Загрузчик разблокирован. Целостность ПО не гарантируется.\nВиртуальный телефон CLM • слот ${d.slot.uppercase()}", 14f, 0xFFFFB74D.toInt()).apply { gravity = Gravity.CENTER })
        root.addView(warn, FrameLayout.LayoutParams(-1, -1))
        root.addView(simTag(), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(16), dp(16), 0) })

        // 2. логотип
        h.postDelayed({
            root.removeAllViews()
            val logo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            logo.addView(text(info.vendor.ifEmpty { "Android" }, 40f, bold = true).apply { gravity = Gravity.CENTER })
            root.addView(logo, FrameLayout.LayoutParams(-1, -1))
            root.addView(text("Powered by Android", 13f, 0xAAFFFFFF.toInt()), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(40) })
            root.addView(simTag(), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(16), dp(16), 0) })
        }, 2500)

        // 3. «бутанимация» + лог
        h.postDelayed({ bootAnim(rom, info, d) }, 4500)
    }

    private fun bootAnim(rom: Rom, info: app.clmulator.rom.RomInfo, d: DeviceState) {
        root.removeAllViews()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        col.addView(text(info.osName, 28f, bold = true).apply { gravity = Gravity.CENTER })
        val dots = text("●  ○  ○", 18f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) }
        col.addView(dots)
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        val logTv = text("", 10f, 0x8881C784.toInt(), mono = true).apply { gravity = Gravity.BOTTOM }
        root.addView(logTv, FrameLayout.LayoutParams(-1, dp(150), Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), dp(12)) })
        root.addView(simTag(), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(16), dp(16), 0) })

        val real = rom.kernelLog(this).takeIf { it.exists() && it.length() > 200 }?.readLines()?.filter { it.isNotBlank() }?.take(60)
        val lines = real?.map { it.take(110) } ?: buildList {
            add("[sim] ${info.kernelVersion.ifEmpty { "Linux version (неизвестно)" }.take(100)}")
            add("[sim] Machine model: ${info.vendor} ${info.device}")
            add("[sim] androidboot.slot_suffix=_${d.slot}")
            info.partitions.take(25).forEach { add("[sim] mount ${it.name}" + if (it.size > 0) " (${fmtSize(it.size)})" else "") }
            add("[sim] init: Starting service 'zygote'...")
            add("[sim] SystemServer: Entered the Android system server!")
        }
        val sb = StringBuilder()
        var i = 0
        val tick = object : Runnable {
            override fun run() {
                if (isFinishing) return
                step++
                dots.text = listOf("●  ○  ○", "○  ●  ○", "○  ○  ●")[step % 3]
                if (i < lines.size) {
                    sb.append(lines[i++]).append('\n')
                    val all = sb.lines(); logTv.text = all.takeLast(10).joinToString("\n")
                    h.postDelayed(this, 120)
                } else {
                    h.postDelayed({ if (d.dataDirty) bootloop(info) else phone(rom, info) }, 900)
                }
            }
        }
        h.post(tick)
    }

    private fun bootloop(info: app.clmulator.rom.RomInfo) {
        root.removeAllViews()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(32), 0, dp(32), 0) }
        col.addView(text("Не удаётся загрузить систему Android", 20f, bold = true).apply { gravity = Gravity.CENTER })
        col.addView(text("\nВ /data остались данные от другой прошивки — ${info.title} на них не стартует (бутлуп). " +
            "Зайдите в рекавери и сделайте Очистка → Форматировать Data или сброс до заводских.\n", 14f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER })
        col.addView(button("Перезагрузка в рекавери") { finish() })
        col.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))
        col.addView(button("Попробовать ещё раз") { recreate() })
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        root.addView(simTag(), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(16), dp(16), 0) })
    }

    // ------------------------------------------------------------------ «телефон»

    private fun phone(rom: Rom, info: app.clmulator.rom.RomInfo) {
        root.removeAllViews()
        root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1A237E.toInt(), 0xFF000000.toInt(), 0xFF004D40.toInt()))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(28)) }
        val now = Date()
        col.addView(text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), 64f).apply { gravity = Gravity.CENTER })
        col.addView(text(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now), 16f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(20)) })

        val banner = text("Это симуляция. Прошивка телефона (${info.soc.ifEmpty { "своё железо" }}) полностью запускается только на своём устройстве: " +
            "в ней драйверы конкретного процессора, GPU, модема и камер. Реально эмулировать можно ядро — кнопка ниже.", 13f, 0xFFFFE0B2.toInt()).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(0x33FF9800); cornerRadius = dp(12).toFloat() }
        }
        col.addView(banner)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(0x22FFFFFF); cornerRadius = dp(16).toFloat() }
        }
        card.addView(text("О телефоне", 17f, bold = true))
        fun row(k: String, v: String) { if (v.isNotEmpty()) card.addView(text("$k: $v", 13f, 0xDDFFFFFF.toInt()).apply { setPadding(0, dp(3), 0, 0) }) }
        row("Модель", listOf(info.vendor, info.model.ifEmpty { info.device }).filter { it.isNotEmpty() }.joinToString(" "))
        row("Кодовое имя", info.device)
        row("Прошивка", info.title)
        row("Версия Android", info.androidVersion)
        row("Патч безопасности", info.securityPatch)
        row("Процессор", listOf(info.soc, info.socDetail).filter { it.isNotEmpty() }.joinToString(" "))
        row("Ядро", info.kernelVersion.removePrefix("Linux version ").substringBefore(' '))
        row("Сборка", info.buildId)
        row("Активный слот", DeviceState.load().slot.uppercase())
        row("Размер прошивки", fmtSize(rom.size))
        val mods = DeviceState.load().modules
        if (mods.isNotEmpty()) row("Модули", mods.joinToString(", "))
        col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        if (rom.hasKernelFiles) col.addView(button("▶ Запустить ядро по-настоящему (QEMU ARM64)") {
            startActivity(Intent(this, KernelBootActivity::class.java).putExtra("rom", rom.id))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        col.addView(button("Перезагрузка в рекавери") { finish() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        col.addView(button("Выключить") { finishAffinity(); startActivity(Intent(this, MainActivity::class.java)) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        root.addView(ScrollView(this).apply { addView(col) }, FrameLayout.LayoutParams(-1, -1))
        root.addView(simTag(), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(12), dp(12), 0) })
    }

    // ------------------------------------------------------------------ fastboot

    private fun fastboot() {
        val d = DeviceState.load()
        val info = RomStore.get(this, d.installedRom)?.info(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(32), dp(24), dp(24)) }
        col.addView(text("FASTBOOT MODE", 24f, 0xFFFF5252.toInt(), bold = true, mono = true))
        val lines = listOf(
            "PRODUCT_NAME - ${info?.device?.ifEmpty { null } ?: "clm_virtual"}",
            "VARIANT - CLM virtual",
            "BOOTLOADER VERSION - CLM-1.0",
            "BASEBAND VERSION - none",
            "SERIAL NUMBER - CLM${(d.bootCount * 7919 + 1000).toString().padStart(6, '0')}",
            "SECURE BOOT - disabled",
            "DEVICE STATE - unlocked",
            "CURRENT SLOT - ${d.slot}",
            "OS - ${info?.title ?: "не установлена"}",
        )
        lines.forEach { col.addView(text(it, 13f, 0xFFB0BEC5.toInt(), mono = true).apply { setPadding(0, dp(3), 0, 0) }) }
        col.addView(View(this), LinearLayout.LayoutParams(1, dp(24)))
        col.addView(button("START") {
            if (info == null) finish() else { startActivity(Intent(this, SimBootActivity::class.java).putExtra("mode", "system")); finish() }
        })
        col.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))
        col.addView(button("Recovery mode") { finish() })
        col.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))
        col.addView(button("Power off") { finishAffinity(); startActivity(Intent(this, MainActivity::class.java)) })
        root.addView(ScrollView(this).apply { addView(col) }, FrameLayout.LayoutParams(-1, -1))
        root.addView(simTag(), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(12), dp(12), 0) })
    }
}
