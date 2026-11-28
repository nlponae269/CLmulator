package app.clmulator

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CLM Recovery — рекавери виртуального телефона в стиле кастомных рекавери (TWRP/OrangeFox).
 * Прошивки «ставятся» в виртуальное устройство; проверки целостности при этом настоящие.
 */
class RecoveryActivity : ComponentActivity() {
    private lateinit var body: FrameLayout
    private lateinit var headerInfo: TextView
    private lateinit var headerTime: TextView
    private val stack = ArrayDeque<() -> View>()
    private var dev = DeviceState.load()
    private var busy = false
    @Volatile private var cancelFlash = false
    private val storeListener: () -> Unit = { if (!busy) { refreshHeader(); redrawIfInstall() } }
    private val taskListener: () -> Unit = { renderTasks() }
    private var tasksBox: LinearLayout? = null
    private var currentIsInstall = false
    private var pendingExport: Pair<Rom, String>? = null

    private val bg = 0xFF101114.toInt()
    private val panel = 0xFF1C1E22.toInt()
    private val dim = 0xFF9AA0A6.toInt()
    private val accent get() = dev.accent

    private val pickRom = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) { Tasks.import(this, uri); toast("Импорт и анализ прошивки…") }
    }
    private val createFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val p = pendingExport; pendingExport = null
        if (uri != null && p != null) { RomTasks.exportPartition(this, p.first, p.second, uri); toast("Извлечение ${p.second}… (прогресс на главном экране)") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = bg

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        root.addView(header())
        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(navBar())
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, b.top, 0, b.bottom); ins
        }
        setContentView(root)
        show { home() }
        clock()
    }

    override fun onStart() {
        super.onStart()
        RomStore.addListener(storeListener); Tasks.addListener(taskListener)
        dev = DeviceState.load(); refreshHeader()
    }

    override fun onStop() {
        RomStore.removeListener(storeListener); Tasks.removeListener(taskListener)
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (busy) { toast("Идёт операция…"); return }
        if (stack.size > 1) { stack.removeLast(); render() } else super.onBackPressed()
    }

    // ================================================================ каркас

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun header(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF1F2933.toInt(), 0xFF15181C.toInt()))
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply {
            text = "CLM Recovery"; setTextColor(Color.WHITE); textSize = 20f; typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        headerTime = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.MONOSPACE }
        row.addView(headerTime)
        box.addView(row)
        headerInfo = TextView(this).apply { setTextColor(dim); textSize = 12f; setPadding(0, dp(2), 0, 0) }
        box.addView(headerInfo)
        return box
    }

    private fun refreshHeader() {
        val rom = RomStore.get(this, dev.installedRom)
        val os = rom?.info(this)?.let { "${it.title} • Android ${it.androidVersion.ifEmpty { "?" }}" } ?: "ОС не установлена"
        headerInfo.text = "Виртуальный телефон • слот ${dev.slot.uppercase()} • $os"
    }

    private fun clock() {
        val bat = runCatching { getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrDefault(-1)
        headerTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) + if (bat in 0..100) "  🔋$bat%" else ""
        headerTime.postDelayed({ if (!isFinishing) clock() }, 20_000)
    }

    private fun navBar(): View {
        val bar = LinearLayout(this).apply { setBackgroundColor(0xFF0A0B0D.toInt()); gravity = Gravity.CENTER }
        fun b(t: String, click: () -> Unit) = TextView(this).apply {
            text = t; setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10)); setOnClickListener { click() }
            background = rippleBg()
        }
        bar.addView(b("◁") { onBackPressedDispatcherCompat() }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(b("⌂") { if (!busy) { stack.clear(); show { home() } } }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(b("☰") { showLog() }, LinearLayout.LayoutParams(0, -2, 1f))
        return bar
    }

    @Suppress("DEPRECATION")
    private fun onBackPressedDispatcherCompat() = onBackPressed()

    private fun rippleBg() = android.util.TypedValue().let { tv ->
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        getDrawable(tv.resourceId)
    }

    private fun show(screen: () -> View) { stack.addLast(screen); render() }

    private fun render() {
        refreshHeader()
        body.removeAllViews()
        tasksBox = null; currentIsInstall = false
        body.addView(stack.last()())
    }

    private fun redrawIfInstall() { if (currentIsInstall) render() }

    private fun page(title: String, content: LinearLayout.() -> Unit): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(16)) }
        col.addView(TextView(this).apply {
            text = title; setTextColor(accent); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, dp(10))
        })
        col.content()
        return ScrollView(this).apply { addView(col); isFillViewport = true }
    }

    private fun LinearLayout.label(t: String, size: Float = 14f, color: Int = Color.WHITE) = TextView(context).apply {
        text = t; setTextColor(color); textSize = size; setPadding(0, dp(4), 0, dp(4))
    }.also { addView(it) }

    private fun bigButton(t: String, sub: String? = null, click: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        setPadding(dp(8), dp(18), dp(8), dp(18))
        background = GradientDrawable().apply { setColor(panel); cornerRadius = dp(10).toFloat(); setStroke(dp(1), 0xFF2B2F36.toInt()) }
        addView(TextView(context).apply { text = t; setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER })
        if (sub != null) addView(TextView(context).apply { text = sub; setTextColor(dim); textSize = 11f; gravity = Gravity.CENTER })
        isClickable = true; foreground = rippleBg()
        setOnClickListener { if (!busy) click() }
    }

    private fun LinearLayout.listItem(t: String, sub: String? = null, click: (() -> Unit)?) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply { setColor(panel); cornerRadius = dp(8).toFloat() }
        addView(TextView(context).apply { text = t; setTextColor(Color.WHITE); textSize = 15f })
        if (sub != null) addView(TextView(context).apply { text = sub; setTextColor(dim); textSize = 12f })
        if (click != null) { foreground = rippleBg(); setOnClickListener { if (!busy) click() } }
    }.also { addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }) }

    private fun LinearLayout.check(t: String, on: Boolean): CheckBox = CheckBox(context).apply {
        text = t; isChecked = on; setTextColor(Color.WHITE); textSize = 15f
        buttonTintList = android.content.res.ColorStateList.valueOf(accent)
    }.also { addView(it) }

    private fun LinearLayout.swipe(t: String, done: () -> Unit) {
        addView(SwipeBar(context, t, accent, done), LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(18) })
    }

    private fun grid(items: List<View>, cols: Int = 2): View {
        val g = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        items.chunked(cols).forEach { row ->
            val r = LinearLayout(this)
            row.forEachIndexed { i, v ->
                r.addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(10); bottomMargin = dp(10) })
            }
            repeat(cols - row.size) { r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(10) }) }
            g.addView(r)
        }
        return g
    }

    // ================================================================ главный экран

    private fun home(): View = page("Главное меню") {
        addView(grid(listOf(
            bigButton("Установка", "Install") { show { install() } },
            bigButton("Очистка", "Wipe") { show { wipe() } },
            bigButton("Бэкап", "Backup") { show { backup() } },
            bigButton("Восстановить", "Restore") { show { restore() } },
            bigButton("Монтирование", "Mount") { show { mount() } },
            bigButton("Настройки", "Settings") { show { settings() } },
            bigButton("Дополнительно", "Advanced") { show { advanced() } },
            bigButton("Перезагрузка", "Reboot") { show { reboot() } },
        )))
        label("Виртуальный телефон: прошивки «ставятся» в него, а контрольные суммы проверяются по-настоящему. " +
            "Ядро ARM64-прошивок можно реально запустить в «Дополнительно».", 12f, dim)
    }

    // ================================================================ установка

    private fun install(): View = page("Установка — выберите прошивку") {
        currentIsInstall = true
        listItem("＋ Импортировать прошивку…", ".zip, .tgz, .tar.md5, .pac, UPDATE.APP, .img, .iso") { pickRom.launch(arrayOf("*/*")) }
        tasksBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(tasksBox); renderTasks()
        val roms = RomStore.list(context)
        if (roms.isEmpty()) label("В /sdcard пока нет прошивок. Импортируйте файл выше.", 13f, dim)
        roms.forEach { r ->
            val i = r.info(context)
            listItem("/sdcard/${r.name}", "${i.typeTitle} • ${i.title}" + (if (i.androidVersion.isNotEmpty()) " • Android ${i.androidVersion}" else "") +
                " • ${fmtSize(r.size)}") { show { confirmInstall(r) } }
        }
    }

    private fun renderTasks() {
        val box = tasksBox ?: return
        box.removeAllViews()
        Tasks.active().forEach { t ->
            val v = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply { setColor(0xFF22303C.toInt()); cornerRadius = dp(8).toFloat() }
            }
            v.addView(TextView(this).apply { text = "${t.title}: ${t.subtitle}"; setTextColor(Color.WHITE); textSize = 13f })
            v.addView(TextView(this).apply { text = t.status; setTextColor(if (t.error != null) 0xFFFF8A80.toInt() else dim); textSize = 12f })
            if (t.error == null) v.addView(LinearProgressIndicator(this).apply {
                isIndeterminate = t.progress < 0; max = 1000; if (t.progress >= 0) progress = (t.progress * 1000).toInt()
                setIndicatorColor(accent)
            })
            else v.setOnClickListener { Tasks.dismiss(t) }
            box.addView(v, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun confirmInstall(r: Rom): View = page("Установка прошивки") {
        val i = r.info(context)
        label(r.name, 15f)
        label(buildString {
            append(i.typeTitle)
            if (i.vendor.isNotEmpty()) append("\n${i.vendor} ${i.title}") else append("\n${i.title}")
            if (i.androidVersion.isNotEmpty()) append(" • Android ${i.androidVersion}")
            if (i.device.isNotEmpty()) append("\nУстройство: ${i.device}" + (if (i.model.isNotEmpty()) " (${i.model})" else ""))
            if (i.soc.isNotEmpty()) append("\nПроцессор: ${i.soc}" + (if (i.socDetail.isNotEmpty()) " ${i.socDetail}" else ""))
            if (i.flashMethod.isNotEmpty()) append("\nСпособ прошивки: ${i.flashMethod}")
        }, 13f, dim)
        i.notes.forEach { label("⚠ $it", 13f, 0xFFFFCC80.toInt()) }
        val verify = check("Проверять контрольные суммы (SHA-256 / CRC / MD5)", true)
        val rebootAfter = check("Перезагрузка после установки", dev.autoReboot)
        swipe("Свайп для прошивки") { show { flashConsole(r, verify.isChecked, rebootAfter.isChecked) } }
    }

    private fun flashConsole(r: Rom, verify: Boolean, rebootAfter: Boolean): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val title = TextView(this).apply { text = "Установка…"; setTextColor(accent); textSize = 18f; typeface = Typeface.DEFAULT_BOLD }
        col.addView(title)
        val bar = LinearProgressIndicator(this).apply { max = 1000; setIndicatorColor(accent); setPadding(0, dp(8), 0, dp(8)) }
        col.addView(bar)
        val logView = TextView(this).apply { setTextColor(0xFFDDE3EA.toInt()); textSize = 12f; typeface = Typeface.MONOSPACE }
        val scroll = ScrollView(this).apply { addView(logView) }
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val buttons = LinearLayout(this).apply { visibility = View.GONE; setPadding(0, dp(10), 0, 0) }
        val cancel = TextView(this).apply {
            text = "Отмена"; setTextColor(dim); setPadding(0, dp(10), 0, 0); gravity = Gravity.END
            setOnClickListener { cancelFlash = true; text = "Отменяю…" }
        }
        col.addView(cancel); col.addView(buttons)
        val sb = StringBuilder()
        fun log(s: String) = runOnUiThread {
            sb.append(s).append('\n'); logView.text = sb
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        busy = true; cancelFlash = false
        val d = DeviceState.load()
        Thread {
            val ok = runCatching {
                Flasher(this, r, verify, ::log, { p -> runOnUiThread { bar.setProgressCompat((p * 1000).toInt(), true) } }, { cancelFlash }).run(d)
            }.getOrElse { e -> log("E: ${e.message ?: e}"); false }
            d.lastLog = d.lastLog + "\n" + sb.toString()
            d.save()
            runOnUiThread {
                busy = false; dev = DeviceState.load(); refreshHeader()
                cancel.visibility = View.GONE
                title.text = if (ok) "Успешно" else "Ошибка"
                title.setTextColor(if (ok) 0xFF81C784.toInt() else 0xFFFF8A80.toInt())
                buttons.visibility = View.VISIBLE
                buttons.addView(bigButton("Очистить Cache/Dalvik") { wipeQuick() }, LinearLayout.LayoutParams(0, -2, 1f))
                buttons.addView(bigButton("Перезагрузка в систему") { rebootSystem() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
                if (ok && rebootAfter) col.postDelayed({ rebootSystem() }, 1500)
            }
        }.start()
        return col
    }

    private fun wipeQuick() {
        val d = DeviceState.load()
        d.lastLog += "\nWiping Dalvik/ART Cache... done\nWiping Cache... done\n"; d.save()
        toast("Dalvik / ART Cache и Cache очищены")
    }

    // ================================================================ очистка

    private fun wipe(): View = page("Очистка") {
        label("Сброс до заводских: удаляет данные, кэш и модули (внутренняя память не трогается).", 13f, dim)
        swipe("Свайп для сброса") {
            val d = DeviceState.load(); d.dataDirty = false; d.modules.clear()
            d.lastLog += "\nFormatting Data using make_f2fs...\nWiping Cache...\nWiping Dalvik/ART Cache...\nFactory reset complete\n"; d.save()
            dev = d; refreshHeader(); toast("Сброс выполнен")
        }
        label("")
        listItem("Выборочная очистка", "System, Vendor, Data, Cache, Dalvik") { show { advancedWipe() } }
        listItem("Форматировать Data", "удаляет шифрование и всё содержимое /data") { formatData() }
    }

    private fun advancedWipe(): View = page("Выборочная очистка") {
        val dalvik = check("Dalvik / ART Cache", true)
        val cache = check("Cache", true)
        val data = check("Data", false)
        val internal = check("Internal Storage", false)
        val system = check("System", false)
        val vendor = check("Vendor", false)
        swipe("Свайп для очистки") {
            val d = DeviceState.load()
            val sb = StringBuilder()
            listOf(dalvik to "Dalvik / ART Cache", cache to "Cache", data to "Data", internal to "Internal Storage", system to "System", vendor to "Vendor")
                .filter { it.first.isChecked }.forEach { sb.append("Wiping ${it.second}... done\n") }
            if (data.isChecked) { d.dataDirty = false; d.modules.clear() }
            if (system.isChecked || vendor.isChecked) d.installedRom = null
            d.lastLog += "\n" + sb; d.save(); dev = d; refreshHeader()
            toast(if (sb.isEmpty()) "Ничего не выбрано" else "Готово")
        }
    }

    private fun formatData() {
        val e = EditText(this).apply { hint = "yes"; inputType = InputType.TYPE_CLASS_TEXT }
        MaterialAlertDialogBuilder(this).setTitle("Форматировать Data")
            .setMessage("Будут удалены все данные и модули. Введите yes для подтверждения.").setView(e)
            .setPositiveButton("Форматировать") { _, _ ->
                if (e.text.toString().trim().equals("yes", true)) {
                    val d = DeviceState.load(); d.dataDirty = false; d.modules.clear()
                    d.lastLog += "\nFormatting Data using make_f2fs...\nDone.\nYou may need to reboot recovery to be able to use /data again.\n"; d.save()
                    dev = d; toast("Data отформатирована")
                } else toast("Нужно ввести yes")
            }.setNegativeButton("Отмена", null).show()
    }

    // ================================================================ бэкап / восстановление

    private fun backup(): View = page("Бэкап") {
        val rom = RomStore.get(context, dev.installedRom)
        if (rom == null) { label("ОС не установлена — нечего сохранять.", 14f, dim); return@page }
        val info = rom.info(context)
        label("Прошивка: ${info.title}", 14f)
        val parts = listOf("boot", "system", "vendor", "data")
        val boxes = parts.map { p ->
            val sz = info.partitions.firstOrNull { it.name == p }?.size ?: 0L
            check(p.replaceFirstChar { it.uppercase() } + if (sz > 0) " (${fmtSize(sz)})" else "", p != "system" && p != "vendor") to p
        }
        swipe("Свайп для бэкапа") {
            val sel = boxes.filter { it.first.isChecked }.map { it.second }
            if (sel.isEmpty()) { toast("Выберите разделы"); return@swipe }
            val d = DeviceState.load()
            val name = SimpleDateFormat("yyyy-MM-dd--HH-mm-ss", Locale.US).format(Date()) + "_" + info.device.ifEmpty { "virtual" }
            d.backups += JSONObject().put("name", name).put("rom", rom.id).put("slot", d.slot).put("parts", JSONArray(sel))
                .put("modules", JSONArray(d.modules)).put("title", info.title)
            d.lastLog += "\nBacking up ${sel.joinToString(", ")}...\nBackup Completed: /sdcard/CLM/BACKUPS/$name\n"; d.save(); dev = d
            toast("Бэкап $name создан")
        }
    }

    private fun restore(): View = page("Восстановить") {
        val list = DeviceState.load().backups
        if (list.isEmpty()) label("Бэкапов нет.", 14f, dim)
        list.reversed().forEach { b ->
            listItem(b.optString("name"), b.optString("title") + " • " + b.optJSONArray("parts")?.let { a -> (0 until a.length()).joinToString(", ") { a.getString(it) } }) {
                show { confirmRestore(b) }
            }
        }
    }

    private fun confirmRestore(b: JSONObject): View = page("Восстановление") {
        label(b.optString("name"), 15f)
        label(b.optString("title"), 13f, dim)
        swipe("Свайп для восстановления") {
            val d = DeviceState.load()
            if (RomStore.get(context, b.optString("rom")) == null) { toast("Прошивка из этого бэкапа удалена"); return@swipe }
            d.installedRom = b.optString("rom"); d.slot = b.optString("slot", "a"); d.dataDirty = false
            d.modules.clear(); b.optJSONArray("modules")?.let { a -> for (i in 0 until a.length()) d.modules += a.getString(i) }
            d.lastLog += "\nRestoring ${b.optString("name")}...\nRestore Complete\n"; d.save(); dev = d; refreshHeader()
            toast("Восстановлено")
        }
    }

    // ================================================================ монтирование и настройки

    private fun mount(): View = page("Монтирование") {
        val d = DeviceState.load()
        listOf("system", "vendor", "product", "data", "cache", "metadata", "usb_otg").forEach { p ->
            check(p, p in d.mounted).setOnCheckedChangeListener { _, on -> if (on) d.mounted += p else d.mounted -= p; d.save() }
        }
        label("Это виртуальные разделы: монтирование влияет только на лог рекавери.", 12f, dim)
    }

    private fun settings(): View = page("Настройки") {
        label("Цвет темы", 14f)
        val colors = listOf(0xFF2196F3, 0xFF26A69A, 0xFFFF9800, 0xFF9C27B0, 0xFFF44336, 0xFF8BC34A).map { it.toInt() }
        val row = LinearLayout(context)
        colors.forEach { c ->
            row.addView(View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); if (c == dev.accent) setStroke(dp(3), Color.WHITE) }
                setOnClickListener { val d = DeviceState.load(); d.accent = c; d.save(); dev = d; render() }
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        }
        addView(row)
        check("Перезагружаться после установки по умолчанию", dev.autoReboot).setOnCheckedChangeListener { _, on ->
            val d = DeviceState.load(); d.autoReboot = on; d.save(); dev = d
        }
    }

    // ================================================================ дополнительно

    private fun advanced(): View = page("Дополнительно") {
        listItem("Информация о прошивке", "производитель, Android, ядро, разделы, процессор") { pickRomThen("Информация") { showInfo(it) } }
        listItem("Тест-загрузка ядра (ARM64, по-настоящему)", "ядро и ramdisk прошивки в QEMU, живой лог консоли") {
            pickRomThen("Ядро", filter = { it.hasKernelFiles }) { startActivity(Intent(this@RecoveryActivity, KernelBootActivity::class.java).putExtra("rom", it.id)) }
        }
        listItem("Извлечь раздел (payload dumper)", "boot.img, init_boot, vendor_boot, system… в файл") { pickRomThen("Извлечь") { show { partitions(it) } } }
        listItem("Файлы внутри прошивки", null) { pickRomThen("Файлы") { showFiles(it) } }
        listItem("Лог рекавери", null) { showLog() }
        listItem("Удалить прошивку из списка", "файл на телефоне не удаляется") { pickRomThen("Удалить") { r ->
            confirm("Удалить ${r.name} из рекавери?") { RomStore.delete(this@RecoveryActivity, r); dev = DeviceState.load(); render() }
        } }
    }

    private fun pickRomThen(what: String, filter: (Rom) -> Boolean = { true }, act: (Rom) -> Unit) {
        val roms = RomStore.list(this).filter(filter)
        if (roms.isEmpty()) {
            toast(if (what == "Ядро") "Нет прошивок с ядром ARM64 (нужен boot.img с ядром arm64)" else "Сначала импортируйте прошивку")
            return
        }
        if (roms.size == 1) { act(roms[0]); return }
        MaterialAlertDialogBuilder(this).setTitle(what)
            .setItems(roms.map { "${it.info(this).title} — ${it.name}" }.toTypedArray()) { _, w -> act(roms[w]) }.show()
    }

    private fun partitions(r: Rom): View = page("Извлечь раздел") {
        val list = RomTasks.exportablePartitions(context, r)
        if (list.isEmpty()) label("В этой прошивке нечего извлекать по отдельности.", 14f, dim)
        list.forEach { (n, sz) ->
            listItem(n, if (sz > 0) fmtSize(sz) else null) {
                pendingExport = r to n
                createFile.launch(n.substringAfterLast('/').removeSuffix(".lz4").let { if (it.contains('.')) it else "$it.img" })
            }
        }
    }

    private fun showInfo(r: Rom) {
        val i = r.info(this)
        val t = buildString {
            fun row(k: String, v: Any?) { val s = v?.toString().orEmpty(); if (s.isNotEmpty() && s != "0") append("$k: $s\n") }
            row("Файл", "${r.name} (${fmtSize(r.size)})")
            row("Тип пакета", i.typeTitle); row("Формат", i.formats); row("Способ прошивки", i.flashMethod)
            row("Производитель", i.vendor); row("Прошивка", i.title); row("Android", i.androidVersion); row("API", i.sdk)
            row("Устройство", i.device); row("Модель", i.model); row("Процессор", listOf(i.soc, i.socDetail).filter { it.isNotEmpty() }.joinToString(" "))
            row("Сборка", i.buildId); row("Incremental", i.incremental); row("Патч безопасности", i.securityPatch)
            if (i.buildDate > 0) row("Дата сборки", SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(i.buildDate * 1000)))
            row("Fingerprint", i.fingerprint)
            row("Ядро", i.kernelVersion); row("boot.img", i.bootHeader); row("Сжатие ядра", i.bootCompression); row("Сжатие ramdisk", i.ramdiskCompression)
            row("cmdline", i.bootCmdline.take(300))
            row("Динамические разделы", i.dynamicPartitions); row("A/B", i.virtualAB)
            row("Подпись", if (i.signed) "есть" + (if (i.certSubject.isNotEmpty()) " — ${i.certSubject}" else "") else "")
            if (i.moduleName.isNotEmpty()) row("Модуль", "${i.moduleName} ${i.moduleVersion} (${i.moduleAuthor})")
            row("Запуск ядра в QEMU", if (r.hasKernelFiles) "доступен" else "нет (ядро не arm64 или не найдено)")
            if (i.partitions.isNotEmpty()) {
                append("\nРазделы (${i.partitions.size}):\n")
                i.partitions.forEach { p -> append("  ${p.name}" + (if (p.size > 0) "  ${fmtSize(p.size)}" else "") + (if (p.detail.isNotEmpty()) "  ${p.detail}" else "") + "\n") }
            }
            if (i.notes.isNotEmpty()) { append("\nЗамечания:\n"); i.notes.forEach { append("• $it\n") } }
        }
        textDialog(i.title, t)
    }

    private fun showFiles(r: Rom) {
        val i = r.info(this)
        textDialog("Файлы (${i.totalFiles})", i.files.joinToString("\n") + if (i.totalFiles > i.files.size) "\n…" else "")
    }

    private fun showLog() = textDialog("Лог рекавери", DeviceState.load().lastLog.takeLast(30_000).ifBlank { "Лог пуст" })

    private fun textDialog(title: String, text: String) {
        val tv = TextView(this).apply {
            this.text = text; setTextIsSelectable(true); textSize = 12f; typeface = Typeface.MONOSPACE
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        MaterialAlertDialogBuilder(this).setTitle(title).setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("OK", null).show()
    }

    // ================================================================ перезагрузка

    private fun reboot(): View = page("Перезагрузка") {
        addView(grid(listOf(
            bigButton("Система") { rebootSystem() },
            bigButton("Рекавери") { recreate() },
            bigButton("Загрузчик", "fastboot") { startActivity(Intent(this@RecoveryActivity, SimBootActivity::class.java).putExtra("mode", "fastboot")) },
            bigButton("Выключение") { finish() },
        )))
        label("Слот: текущий ${dev.slot.uppercase()}", 13f, dim)
        listItem("Сменить активный слот на ${if (dev.slot == "a") "B" else "A"}", null) {
            val d = DeviceState.load(); d.slot = if (d.slot == "a") "b" else "a"; d.save(); dev = d; render()
        }
    }

    private fun rebootSystem() {
        val d = DeviceState.load()
        val rom = RomStore.get(this, d.installedRom)
        if (rom == null) {
            MaterialAlertDialogBuilder(this).setTitle("ОС не установлена!")
                .setMessage("В виртуальном телефоне нет системы. Сначала установите прошивку через «Установка».")
                .setPositiveButton("К установке") { _, _ -> stack.clear(); show { home() }; show { install() } }
                .setNegativeButton("Отмена", null).show()
            return
        }
        val machine = rom.machineId?.let { MachineStore.get(this, it) }
        if (machine != null) { bootX86(machine); return }
        startActivity(Intent(this, SimBootActivity::class.java).putExtra("mode", "system"))
    }

    private fun bootX86(m: Machine) {
        if (QemuManager.isRunning && QemuManager.machine?.id != m.id) { toast("Сначала выключите «${QemuManager.machine?.name}»"); return }
        Thread {
            val err = runCatching { QemuManager.start(this, m) }.exceptionOrNull()
            runOnUiThread {
                if (err != null) toast(err.message ?: err.toString())
                else startActivity(Intent(this, EmulatorActivity::class.java))
            }
        }.start()
    }

    private fun confirm(msg: String, ok: () -> Unit) {
        MaterialAlertDialogBuilder(this).setTitle(msg).setPositiveButton("Да") { _, _ -> ok() }.setNegativeButton("Отмена", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

/** Полоса «свайп для подтверждения». */
@SuppressLint("ViewConstructor")
class SwipeBar(ctx: Context, private val label: String, private val color: Int, private val done: () -> Unit) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var x = 0f
    private var dragging = false
    private val pad get() = height * 0.08f

    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val w = width.toFloat(); val r = h / 2
        p.color = 0xFF23262B.toInt(); c.drawRoundRect(RectF(0f, 0f, w, h), r, r, p)
        p.color = (color and 0x00FFFFFF) or 0x55000000; c.drawRoundRect(RectF(0f, 0f, (x + h).coerceAtMost(w), h), r, r, p)
        p.color = 0xFFBFC5CC.toInt(); p.textSize = h * 0.3f; p.textAlign = Paint.Align.CENTER
        c.drawText("$label  ›››", w / 2 + h / 3, h / 2 + p.textSize / 3, p)
        p.color = color; c.drawRoundRect(RectF(x + pad, pad, x + h - pad, h - pad), r, r, p)
        p.color = Color.WHITE; p.textSize = h * 0.4f
        c.drawText("›", x + h / 2, h / 2 + p.textSize / 3, p)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val max = width - height.toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragging = e.x < x + height * 1.5f; parent.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> if (dragging) { x = (e.x - height / 2).coerceIn(0f, max); invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging && x > max * 0.85f) { x = max; invalidate(); performClick(); done() }
                x = 0f; dragging = false; invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    init { layoutParams = ViewGroup.LayoutParams(-1, -2) }
}
