package app.clmulator

import android.content.Context
import android.net.Uri
import app.clmulator.rom.ChannelSource
import app.clmulator.rom.RomInfo
import app.clmulator.rom.Source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.UUID

/** Импортированная прошивка телефона. Сам файл остаётся там, где лежал (доступ по URI), если он читается с произвольным доступом. */
class Rom(
    val id: String,
    val uri: String,
    val name: String,
    val size: Long,
    val imported: Long,
    /** id машины QEMU для x86-прошивок */
    var machineId: String? = null,
) {
    fun dir(ctx: Context) = File(RomStore.root(ctx), id)
    fun localCopy(ctx: Context) = File(dir(ctx), "rom.bin")
    fun kernel(ctx: Context) = File(dir(ctx), "kernel.img")
    fun ramdisk(ctx: Context) = File(dir(ctx), "ramdisk.img")
    fun kernelLog(ctx: Context) = File(dir(ctx), "kernel.log")
    val hasKernelFiles get() = File(RomStore.root(App.instance), "$id/kernel.img").exists()

    @Volatile private var cached: RomInfo? = null
    fun info(ctx: Context): RomInfo = cached ?: runCatching {
        RomInfo.fromJson(JSONObject(File(dir(ctx), "info.json").readText()))
    }.getOrElse { RomInfo().apply { fileName = name; fileSize = size; typeTitle = "Нет данных анализа" } }.also { cached = it }

    fun toJson() = JSONObject().put("id", id).put("uri", uri).put("name", name).put("size", size)
        .put("imported", imported).put("machineId", machineId ?: "")

    /** Открывает прошивку для чтения с произвольным доступом. */
    fun open(ctx: Context): Source {
        val local = localCopy(ctx)
        if (local.exists()) return ChannelSource.of(local)
        val pfd = ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r")
            ?: throw IllegalStateException("Файл прошивки недоступен")
        val fis = FileInputStream(pfd.fileDescriptor)
        return ChannelSource(fis.channel, object : java.io.Closeable {
            override fun close() { runCatching { fis.close() }; runCatching { pfd.close() } }
        })
    }

    companion object {
        fun fromJson(o: JSONObject) = Rom(
            o.getString("id"), o.optString("uri"), o.optString("name"), o.optLong("size"), o.optLong("imported"),
            o.optString("machineId").ifEmpty { null },
        )
        fun newId() = UUID.randomUUID().toString().take(8)
    }
}

object RomStore {
    private val listeners = mutableListOf<() -> Unit>()

    fun root(ctx: Context) = File(ctx.filesDir, "roms").apply { mkdirs() }

    @Synchronized
    fun list(ctx: Context): List<Rom> = root(ctx).listFiles().orEmpty().mapNotNull { d ->
        val f = File(d, "rom.json")
        if (!f.exists()) null else runCatching { Rom.fromJson(JSONObject(f.readText())) }.getOrNull()
    }.sortedByDescending { it.imported }

    fun get(ctx: Context, id: String?): Rom? = id?.let { i -> list(ctx).firstOrNull { it.id == i } }

    @Synchronized
    fun save(ctx: Context, r: Rom, info: RomInfo?) {
        r.dir(ctx).mkdirs()
        if (info != null) File(r.dir(ctx), "info.json").writeText(info.toJson().toString())
        File(r.dir(ctx), "rom.json").writeText(r.toJson().toString(1))
        notifyChanged()
    }

    @Synchronized
    fun delete(ctx: Context, r: Rom) {
        runCatching {
            ctx.contentResolver.releasePersistableUriPermission(Uri.parse(r.uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        r.machineId?.let { mid -> MachineStore.get(ctx, mid)?.let { MachineStore.delete(ctx, it) } }
        r.dir(ctx).deleteRecursively()
        val d = DeviceState.load()
        if (d.installedRom == r.id) { d.installedRom = null; d.save() }
        notifyChanged()
    }

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners += l } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners -= l } }
    fun notifyChanged() {
        val ls = synchronized(listeners) { listeners.toList() }
        App.main.post { ls.forEach { it() } }
    }
}

/** Состояние виртуального телефона, в который «прошиваются» прошивки в рекавери. */
class DeviceState {
    var installedRom: String? = null
    var slot = "a"
    var dataDirty = false
    var bootCount = 0
    var accent = 0xFF2196F3.toInt()
    var autoReboot = false
    val modules = ArrayList<String>()
    val backups = ArrayList<JSONObject>()
    val mounted = LinkedHashSet<String>(listOf("system", "vendor", "data", "cache"))
    var lastLog = ""

    fun save() {
        val o = JSONObject().put("installedRom", installedRom ?: "").put("slot", slot).put("dataDirty", dataDirty)
            .put("bootCount", bootCount).put("accent", accent).put("autoReboot", autoReboot)
            .put("modules", JSONArray(modules)).put("backups", JSONArray(backups)).put("mounted", JSONArray(mounted.toList()))
        App.prefs.edit().putString("device", o.toString()).apply()
        File(App.instance.filesDir, "recovery.log").writeText(lastLog.takeLast(200_000))
    }

    companion object {
        fun load(): DeviceState = DeviceState().apply {
            val s = App.prefs.getString("device", null) ?: return@apply
            runCatching {
                val o = JSONObject(s)
                installedRom = o.optString("installedRom").ifEmpty { null }
                slot = o.optString("slot", "a"); dataDirty = o.optBoolean("dataDirty")
                bootCount = o.optInt("bootCount"); accent = o.optInt("accent", accent); autoReboot = o.optBoolean("autoReboot")
                o.optJSONArray("modules")?.let { a -> for (i in 0 until a.length()) modules += a.getString(i) }
                o.optJSONArray("backups")?.let { a -> for (i in 0 until a.length()) backups += a.getJSONObject(i) }
                o.optJSONArray("mounted")?.let { a -> mounted.clear(); for (i in 0 until a.length()) mounted += a.getString(i) }
            }
            lastLog = runCatching { File(App.instance.filesDir, "recovery.log").readText() }.getOrDefault("")
        }
    }
}
