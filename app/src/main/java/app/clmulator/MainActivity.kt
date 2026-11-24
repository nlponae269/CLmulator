package app.clmulator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

class MainActivity : AppCompatActivity() {
    private lateinit var machinesBox: LinearLayout
    private lateinit var tasksBox: LinearLayout
    private lateinit var emptyView: TextView
    private val refresh: () -> Unit = { render() }
    private val refreshTasks: () -> Unit = { renderTasks() }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) Tasks.import(this, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        val content = findViewById<View>(R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }
        machinesBox = findViewById(R.id.machines)
        tasksBox = findViewById(R.id.tasks)
        emptyView = findViewById(R.id.empty)
        findViewById<View>(R.id.fabImport).setOnClickListener { pickFile.launch(arrayOf("*/*")) }
        findViewById<View>(R.id.btnInfo).setOnClickListener { showInfo() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { showGlobalSettings() }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        renderCatalog()
    }

    override fun onStart() {
        super.onStart()
        MachineStore.addListener(refresh)
        QemuManager.addListener(refresh)
        Tasks.addListener(refreshTasks)
        render(); renderTasks()
    }

    override fun onStop() {
        MachineStore.removeListener(refresh)
        QemuManager.removeListener(refresh)
        Tasks.removeListener(refreshTasks)
        super.onStop()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun gradient(c1: Int, c2: Int, radius: Float) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)
    ).apply { cornerRadius = radius }

    // ------------------------------------------------------------ мои прошивки

    private fun render() {
        val list = MachineStore.list(this)
        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        machinesBox.removeAllViews()
        val cols = if (resources.configuration.screenWidthDp >= 600) 3 else 2
        list.chunked(cols).forEach { row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { line.addView(machineCard(it, line)) }
            repeat(cols - row.size) { line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            machinesBox.addView(line)
        }
        renderCatalog()
    }

    private fun machineCard(m: Machine, parent: LinearLayout): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_machine, parent, false)
        v.findViewById<View>(R.id.tile).background = gradient(m.color1, m.color2, dp(26).toFloat())
        v.findViewById<TextView>(R.id.label).text = m.label
        v.findViewById<TextView>(R.id.version).text = m.version
        v.findViewById<TextView>(R.id.api).text = if (m.api > 0) "API ${m.api}" else "образ"
        v.findViewById<TextView>(R.id.name).text = m.name
        val running = QemuManager.isRunning && QemuManager.machine?.id == m.id
        v.findViewById<View>(R.id.running).visibility = if (running) View.VISIBLE else View.GONE
        val size = MachineStore.diskUsage(this, m)
        val res = if (m.bootMode == Machine.MODE_ANDROID_X86) m.resolution else "BIOS"
        v.findViewById<TextView>(R.id.details).text = "$res · ${m.ramMb} МБ ОЗУ · ${fmtSize(size)}"
        v.findViewById<ImageView>(R.id.playIcon).setColorFilter(m.color2)
        v.findViewById<View>(R.id.play).setOnClickListener { launch(m) }
        v.setOnClickListener { launch(m) }
        v.findViewById<ImageButton>(R.id.more).setOnClickListener { b -> machineMenu(m, b) }
        return v
    }

    private fun launch(m: Machine) {
        if (QemuManager.isRunning) {
            if (QemuManager.machine?.id == m.id) {
                startActivity(Intent(this, EmulatorActivity::class.java)); return
            }
            MaterialAlertDialogBuilder(this).setTitle("Уже работает «${QemuManager.machine?.name}»")
                .setMessage("Одновременно можно запустить только одну прошивку. Выключить текущую?")
                .setPositiveButton("Выключить") { _, _ -> QemuManager.stop() }
                .setNegativeButton("Отмена", null).show()
            return
        }
        if (!m.imageFile(this).exists()) {
            Toast.makeText(this, "Образ прошивки не найден, удалите и установите заново", Toast.LENGTH_LONG).show()
            return
        }
        val progress = MaterialAlertDialogBuilder(this).setTitle("Запуск…")
            .setMessage(if (m.persistent && !m.dataDisk(this).exists()) "Создаю диск данных" else "Готовлю QEMU")
            .setCancelable(false).show()
        Thread {
            val err = runCatching { QemuManager.start(this, m) }.exceptionOrNull()
            runOnUiThread {
                progress.dismiss()
                if (err != null) {
                    MaterialAlertDialogBuilder(this).setTitle("Не удалось запустить")
                        .setMessage(err.message ?: err.toString()).setPositiveButton("OK", null).show()
                } else startActivity(Intent(this, EmulatorActivity::class.java))
            }
        }.start()
    }

    private fun machineMenu(m: Machine, anchor: View) {
        val p = PopupMenu(this, anchor)
        p.menu.add(0, 1, 0, "Настройки")
        p.menu.add(0, 2, 0, "Сбросить данные")
        p.menu.add(0, 3, 0, "Лог QEMU")
        p.menu.add(0, 4, 0, "Удалить")
        p.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> machineSettings(m)
                2 -> confirm("Сбросить данные «${m.name}»?", "Будут удалены установленные приложения и настройки гостевой системы.") {
                    if (QemuManager.machine?.id == m.id && QemuManager.isRunning) toast("Сначала выключите машину")
                    else { m.dataDisk(this).delete(); MachineStore.notifyChanged(); toast("Данные сброшены") }
                }
                3 -> showLog()
                4 -> confirm("Удалить «${m.name}»?", "Прошивка и все её данные будут удалены с телефона.") {
                    if (QemuManager.machine?.id == m.id && QemuManager.isRunning) toast("Сначала выключите машину")
                    else MachineStore.delete(this, m)
                }
            }
            true
        }
        p.show()
    }

    private fun machineSettings(m: Machine) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        fun label(t: String) = TextView(this).apply {
            text = t; setPadding(0, dp(14), 0, dp(2))
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        }
        val ramLabel = label("Оперативная память: ${m.ramMb} МБ")
        val ram = Slider(this).apply {
            valueFrom = 256f; valueTo = 4096f; stepSize = 256f
            value = m.ramMb.toFloat().coerceIn(256f, 4096f).let { (Math.round(it / 256f) * 256f).coerceIn(256f, 4096f) }
            addOnChangeListener { _, v, _ -> ramLabel.text = "Оперативная память: ${v.toInt()} МБ" }
        }
        val cpuLabel = label("Ядра процессора: ${m.cpus}")
        val cpus = Slider(this).apply {
            valueFrom = 1f; valueTo = 4f; stepSize = 1f; value = m.cpus.toFloat().coerceIn(1f, 4f)
            addOnChangeListener { _, v, _ -> cpuLabel.text = "Ядра процессора: ${v.toInt()}" }
        }
        box.addView(ramLabel); box.addView(ram); box.addView(cpuLabel); box.addView(cpus)

        box.addView(label("Разрешение экрана"))
        val modes = listOf(785 to "640×480", 788 to "800×600", 791 to "1024×768", 794 to "1280×1024")
        val resGroup = ChipGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        modes.forEach { (code, t) ->
            resGroup.addView(Chip(this).apply {
                text = t; isCheckable = true; id = code; isChecked = m.vgaMode == code
            })
        }
        box.addView(resGroup)

        box.addView(label("Плотность (DPI)"))
        val dpiGroup = ChipGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        listOf(0 to "авто", 120 to "120", 160 to "160", 240 to "240").forEach { (d, t) ->
            dpiGroup.addView(Chip(this).apply { text = t; isCheckable = true; id = 10000 + d; isChecked = m.dpi == d })
        }
        box.addView(dpiGroup)

        box.addView(label("Модель процессора"))
        val cpuGroup = ChipGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        val cpuModels = listOf("qemu64", "core2duo", "Nehalem", "max")
        cpuModels.forEachIndexed { i, c ->
            cpuGroup.addView(Chip(this).apply { text = c; isCheckable = true; id = 20000 + i; isChecked = m.cpu == c })
        }
        box.addView(cpuGroup)

        val persist = MaterialSwitch(this).apply {
            text = "Сохранять данные между запусками"; isChecked = m.persistent
            setPadding(0, dp(12), 0, 0)
        }
        val net = MaterialSwitch(this).apply { text = "Сеть (интернет в гостевой системе)"; isChecked = m.network }
        box.addView(persist); box.addView(net)

        val scroll = androidx.core.widget.NestedScrollView(this).apply { addView(box) }
        MaterialAlertDialogBuilder(this).setTitle(m.name).setView(scroll)
            .setPositiveButton("Сохранить") { _, _ ->
                m.ramMb = ram.value.toInt()
                m.cpus = cpus.value.toInt()
                m.vgaMode = resGroup.checkedChipId.takeIf { it > 0 } ?: m.vgaMode
                m.dpi = (dpiGroup.checkedChipId - 10000).takeIf { it >= 0 } ?: m.dpi
                m.cpu = cpuModels.getOrNull(cpuGroup.checkedChipId - 20000) ?: m.cpu
                m.persistent = persist.isChecked
                m.network = net.isChecked
                MachineStore.save(this, m)
                if (QemuManager.isRunning && QemuManager.machine?.id == m.id) toast("Изменения применятся после перезапуска")
            }
            .setNegativeButton("Отмена", null).show()
    }

    // ------------------------------------------------------------ каталог

    private fun renderCatalog() {
        val installed = MachineStore.list(this).mapNotNull { it.catalogId }.toSet()
        val downloading = Tasks.active().filter { !it.done && it.error == null }.map { it.subtitle }.toSet()
        fill(findViewById(R.id.catalogCustom), Catalog.custom, installed, downloading)
        fill(findViewById(R.id.catalogOfficial), Catalog.official, installed, downloading)
    }

    private fun fill(box: LinearLayout, list: List<CatalogEntry>, installed: Set<String>, downloading: Set<String>) {
        box.removeAllViews()
        for (e in list) {
            val v = LayoutInflater.from(this).inflate(R.layout.item_catalog, box, false)
            v.findViewById<TextView>(R.id.badge).apply {
                text = e.version
                background = gradient(e.color1, e.color2, dp(20).toFloat())
            }
            v.findViewById<TextView>(R.id.title).text = e.title
            v.findViewById<TextView>(R.id.note).apply {
                text = e.note.ifEmpty { "Android ${e.version} ${e.codename}" }
            }
            val meta = buildString {
                append("API ${e.api} · ~${e.approxMb} МБ · ${e.ramMb} МБ ОЗУ")
                if (e.isCustom) append(" · ROOT")
                if (e.heavy) append(" · тяжёлая")
            }
            v.findViewById<TextView>(R.id.meta).text = meta
            val btn = v.findViewById<MaterialButton>(R.id.action)
            when {
                e.title in downloading -> { btn.text = "…"; btn.isEnabled = false }
                e.id in installed -> {
                    btn.text = "Запуск"
                    btn.setOnClickListener {
                        MachineStore.list(this).firstOrNull { it.catalogId == e.id }?.let { launch(it) }
                    }
                }
                else -> {
                    btn.text = "Скачать"
                    btn.setOnClickListener { download(e) }
                }
            }
            box.addView(v)
        }
    }

    private fun download(e: CatalogEntry) {
        val msg = buildString {
            append("Будет скачан официальный образ ${e.isoName} (~${e.approxMb} МБ) с SourceForge.")
            if (e.isCustom) append("\n\nПоверх него при загрузке накладываются изменения CLM: root, своя модель, бутанимация, обои и приложение CLM Center. Сам образ не меняется.")
            if (e.heavy) append("\n\n⚠️ Это тяжёлая версия: на эмуляции x86 она грузится долго (несколько минут) и работает медленно. Для старта лучше 1.6–4.4.")
        }
        MaterialAlertDialogBuilder(this).setTitle(e.title).setMessage(msg)
            .setPositiveButton("Скачать") { _, _ -> Tasks.install(this, e); renderCatalog() }
            .setNegativeButton("Отмена", null).show()
    }

    // ------------------------------------------------------------ задачи

    private fun renderTasks() {
        val list = Tasks.active()
        // переиспользуем карточки, чтобы прогресс не мигал
        if (tasksBox.childCount != list.size || list.indices.any { tasksBox.getChildAt(it).tag != list[it].id }) {
            tasksBox.removeAllViews()
            list.forEach { t ->
                val v = LayoutInflater.from(this).inflate(R.layout.item_task, tasksBox, false)
                v.tag = t.id
                tasksBox.addView(v)
            }
            renderCatalog()
        }
        list.forEachIndexed { i, t ->
            val v = tasksBox.getChildAt(i)
            v.findViewById<TextView>(R.id.title).text = t.title
            v.findViewById<TextView>(R.id.subtitle).text = t.subtitle
            v.findViewById<TextView>(R.id.status).text = t.status
            val bar = v.findViewById<LinearProgressIndicator>(R.id.progress)
            val spin = v.findViewById<CircularProgressIndicator>(R.id.spinner)
            if (t.error != null) {
                bar.visibility = View.GONE; spin.visibility = View.GONE
            } else if (t.progress < 0) {
                if (!bar.isIndeterminate) { bar.visibility = View.INVISIBLE; bar.isIndeterminate = true; bar.visibility = View.VISIBLE }
            } else {
                if (bar.isIndeterminate) { bar.visibility = View.INVISIBLE; bar.isIndeterminate = false; bar.visibility = View.VISIBLE }
                bar.setProgressCompat((t.progress * 1000).toInt(), true); bar.max = 1000
            }
            v.findViewById<MaterialButton>(R.id.cancel).apply {
                text = if (t.error != null) "Закрыть" else "Отмена"
                setOnClickListener {
                    if (t.error != null) Tasks.dismiss(t) else { t.cancelled = true; t.status = "Отмена…" }
                }
            }
        }
        if (list.isEmpty()) renderCatalog()
    }

    // ------------------------------------------------------------ диалоги

    private fun confirm(title: String, msg: String, ok: () -> Unit) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(msg)
            .setPositiveButton("Да") { _, _ -> ok() }.setNegativeButton("Отмена", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun showLog() {
        val f = QemuManager.qemuLog(this)
        val text = if (f.exists()) f.readText().takeLast(8000) else "Лог пуст — машина ещё не запускалась."
        val tv = TextView(this).apply {
            this.text = text; setTextIsSelectable(true); textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE; setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        MaterialAlertDialogBuilder(this).setTitle("Лог QEMU")
            .setView(androidx.core.widget.NestedScrollView(this).apply { addView(tv) })
            .setPositiveButton("OK", null).show()
    }

    private fun showInfo() {
        MaterialAlertDialogBuilder(this).setTitle("О CLmulator")
            .setMessage(
                "CLmulator ${BuildConfig.VERSION_NAME}\n\n" +
                    "Запускает старые версии Android (1.6 – 9.0) внутри эмулятора QEMU прямо на телефоне. " +
                    "Используются сборки проекта Android-x86: процессор x86 эмулируется программно (TCG), " +
                    "поэтому root и KVM на телефоне не нужны, но скорость ниже, чем у настоящего устройства.\n\n" +
                    "Управление: касание — нажатие; два пальца вверх/вниз — прокрутка; кнопки справа — Назад, Домой, Меню, клавиатура, питание.\n\n" +
                    "Прошивки реальных телефонов (Samsung .tar.md5, Xiaomi .zip, recovery.img и т.п.) запустить нельзя: " +
                    "они собраны под конкретный процессор и железо. Импортируйте ISO или образ диска x86.\n\n" +
                    "Компоненты: QEMU (GPL-2.0, сборка Termux), Android-x86 (Apache-2.0 / GPL)."
            )
            .setPositiveButton("OK", null).show()
    }

    private fun showGlobalSettings() {
        val prefs = App.prefs
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val mttcg = MaterialSwitch(this).apply {
            text = "Многопоточная эмуляция (MTTCG)"; isChecked = prefs.getBoolean("mttcg", true)
        }
        val hint = TextView(this).apply {
            text = "Если машина с несколькими ядрами зависает или падает — выключите."
            setPadding(0, 0, 0, dp(12))
        }
        val tbLabel = TextView(this).apply { text = "Кэш трансляции кода: ${prefs.getInt("tb", 256)} МБ" }
        val tb = Slider(this).apply {
            valueFrom = 64f; valueTo = 1024f; stepSize = 64f
            value = prefs.getInt("tb", 256).toFloat().coerceIn(64f, 1024f)
            addOnChangeListener { _, v, _ -> tbLabel.text = "Кэш трансляции кода: ${v.toInt()} МБ" }
        }
        box.addView(mttcg); box.addView(hint); box.addView(tbLabel); box.addView(tb)
        MaterialAlertDialogBuilder(this).setTitle("Настройки").setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                prefs.edit().putBoolean("mttcg", mttcg.isChecked).putInt("tb", tb.value.toInt()).apply()
            }
            .setNegativeButton("Отмена", null).show()
    }
}
