package app.clmulator

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Установленная виртуальная машина (прошивка). Хранится в files/machines/<id>/machine.json */
data class Machine(
    val id: String = UUID.randomUUID().toString().take(8),
    var name: String,
    var label: String,
    var version: String,
    var api: Int,
    var color1: Int,
    var color2: Int,
    /** имя файла образа в files/images */
    var image: String,
    /** ANDROID_X86 — ядро+initrd из ISO, CDROM — загрузка с диска через BIOS, DISK — образ жёсткого диска */
    var bootMode: String = MODE_ANDROID_X86,
    var append: String = "",
    var edition: String? = null,
    var catalogId: String? = null,
    var ramMb: Int = 1024,
    var cpus: Int = 1,
    var cpu: String = "qemu64",
    var vgaMode: Int = 788,
    var dpi: Int = 0,
    var persistent: Boolean = true,
    var network: Boolean = true,
    var lastBootMs: Long = 0,
    var created: Long = System.currentTimeMillis(),
) {
    fun dir(ctx: Context) = File(MachineStore.root(ctx), id)
    fun kernel(ctx: Context) = File(dir(ctx), "kernel")
    fun initrd(ctx: Context): File =
        edition?.let { File(dir(ctx), "initrd-$it.img") } ?: File(dir(ctx), "initrd.img")
    fun dataDisk(ctx: Context) = File(dir(ctx), "data.img")
    fun imageFile(ctx: Context) = File(MachineStore.images(ctx), image)

    val resolution: String
        get() = when (vgaMode) {
            785 -> "640×480"
            788 -> "800×600"
            791 -> "1024×768"
            794 -> "1280×1024"
            else -> "авто"
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("label", label); put("version", version); put("api", api)
        put("color1", color1); put("color2", color2); put("image", image); put("bootMode", bootMode)
        put("append", append); put("edition", edition ?: ""); put("catalogId", catalogId ?: "")
        put("ramMb", ramMb); put("cpus", cpus); put("cpu", cpu); put("vgaMode", vgaMode); put("dpi", dpi)
        put("persistent", persistent); put("network", network); put("lastBootMs", lastBootMs); put("created", created)
    }

    companion object {
        const val MODE_ANDROID_X86 = "android_x86"
        const val MODE_CDROM = "cdrom"
        const val MODE_DISK = "disk"

        fun fromJson(o: JSONObject) = Machine(
            id = o.getString("id"),
            name = o.optString("name"),
            label = o.optString("label"),
            version = o.optString("version"),
            api = o.optInt("api"),
            color1 = o.optInt("color1"),
            color2 = o.optInt("color2"),
            image = o.optString("image"),
            bootMode = o.optString("bootMode", MODE_ANDROID_X86),
            append = o.optString("append"),
            edition = o.optString("edition").ifEmpty { null },
            catalogId = o.optString("catalogId").ifEmpty { null },
            ramMb = o.optInt("ramMb", 1024),
            cpus = o.optInt("cpus", 1),
            cpu = o.optString("cpu", "qemu64"),
            vgaMode = o.optInt("vgaMode", 788),
            dpi = o.optInt("dpi", 0),
            persistent = o.optBoolean("persistent", true),
            network = o.optBoolean("network", true),
            lastBootMs = o.optLong("lastBootMs"),
            created = o.optLong("created"),
        )
    }
}

object MachineStore {
    private val listeners = mutableListOf<() -> Unit>()

    fun root(ctx: Context) = File(ctx.filesDir, "machines").apply { mkdirs() }
    fun images(ctx: Context) = File(ctx.filesDir, "images").apply { mkdirs() }

    @Synchronized
    fun list(ctx: Context): List<Machine> =
        root(ctx).listFiles().orEmpty().mapNotNull { d ->
            val f = File(d, "machine.json")
            if (!f.exists()) null else runCatching { Machine.fromJson(JSONObject(f.readText())) }.getOrNull()
        }.sortedBy { it.created }

    fun get(ctx: Context, id: String): Machine? = list(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun save(ctx: Context, m: Machine) {
        m.dir(ctx).mkdirs()
        val tmp = File(m.dir(ctx), "machine.json.tmp")
        tmp.writeText(m.toJson().toString(1))
        tmp.renameTo(File(m.dir(ctx), "machine.json"))
        notifyChanged()
    }

    @Synchronized
    fun delete(ctx: Context, m: Machine) {
        m.dir(ctx).deleteRecursively()
        // образ удаляем, только если он больше никому не нужен
        if (list(ctx).none { it.image == m.image }) m.imageFile(ctx).delete()
        notifyChanged()
    }

    /** Размер, который машина занимает на диске (без общего ISO). */
    fun diskUsage(ctx: Context, m: Machine): Long {
        var s = m.imageFile(ctx).length()
        m.dir(ctx).walkTopDown().filter { it.isFile }.forEach { s += allocated(it) }
        return s
    }

    private fun allocated(f: File): Long = runCatching {
        val st = android.system.Os.stat(f.absolutePath)
        minOf(st.st_blocks * 512, f.length())
    }.getOrDefault(f.length())

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners += l } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners -= l } }
    fun notifyChanged() {
        val ls = synchronized(listeners) { listeners.toList() }
        App.main.post { ls.forEach { it() } }
    }
}
