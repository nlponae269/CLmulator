package app.clmulator.rom

import org.json.JSONArray
import org.json.JSONObject

/** Результат анализа прошивки — то, что показывается в рекавери и в «О телефоне». */
class RomInfo {
    var fileName = ""
    var fileSize = 0L
    /** тип пакета: AB_OTA, AB_INCREMENTAL, RECOVERY, FASTBOOT, PIXEL_FACTORY, ODIN, MOTO, MTK, EDL, HUAWEI, PAC, MAGISK, ANYKERNEL, X86, IMAGE, ENCRYPTED, UNKNOWN */
    var type = "UNKNOWN"
    var typeTitle = ""
    var flashMethod = ""
    var vendor = ""
    var osName = "Android"
    var osVersion = ""
    var androidVersion = ""
    var sdk = 0
    var device = ""
    var model = ""
    var fingerprint = ""
    var buildId = ""
    var incremental = ""
    var securityPatch = ""
    var buildDate = 0L
    var soc = ""
    var socDetail = ""
    var kernelVersion = ""
    var bootHeader = ""
    var bootCmdline = ""
    var bootCompression = ""
    var ramdiskCompression = ""
    var formats = ""
    var signed = false
    var certSubject = ""
    var payloadIncremental = false
    var dynamicPartitions = ""
    var virtualAB = ""
    var isoPath = ""
    var payloadPath = ""
    var arm64Kernel = false
    var moduleName = ""
    var moduleVersion = ""
    var moduleAuthor = ""
    val partitions = ArrayList<Part>()
    val uiPrint = ArrayList<String>()
    val asserts = ArrayList<String>()
    val notes = ArrayList<String>()
    val files = ArrayList<String>()
    var totalFiles = 0

    class Part(val name: String, val size: Long, val detail: String)

    val title: String
        get() = when {
            type == "MAGISK" -> moduleName.ifEmpty { "Magisk-модуль" }
            osVersion.isNotEmpty() -> "$osName $osVersion"
            else -> osName
        }

    val fullyBootable get() = isoPath.isNotEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        for ((k, v) in fieldsMap()) put(k, v)
        put("partitions", JSONArray(partitions.map { JSONObject().put("n", it.name).put("s", it.size).put("d", it.detail) }))
        put("uiPrint", JSONArray(uiPrint)); put("asserts", JSONArray(asserts)); put("notes", JSONArray(notes))
        put("files", JSONArray(files.take(400)))
    }

    private fun fieldsMap(): Map<String, Any> = mapOf(
        "fileName" to fileName, "fileSize" to fileSize, "type" to type, "typeTitle" to typeTitle, "flashMethod" to flashMethod,
        "vendor" to vendor, "osName" to osName, "osVersion" to osVersion, "androidVersion" to androidVersion, "sdk" to sdk,
        "device" to device, "model" to model, "fingerprint" to fingerprint, "buildId" to buildId, "incremental" to incremental,
        "securityPatch" to securityPatch, "buildDate" to buildDate, "soc" to soc, "socDetail" to socDetail,
        "kernelVersion" to kernelVersion, "bootHeader" to bootHeader, "bootCmdline" to bootCmdline,
        "bootCompression" to bootCompression, "ramdiskCompression" to ramdiskCompression, "formats" to formats,
        "signed" to signed, "certSubject" to certSubject, "payloadIncremental" to payloadIncremental,
        "dynamicPartitions" to dynamicPartitions, "virtualAB" to virtualAB, "isoPath" to isoPath, "payloadPath" to payloadPath,
        "arm64Kernel" to arm64Kernel, "moduleName" to moduleName, "moduleVersion" to moduleVersion, "moduleAuthor" to moduleAuthor,
        "totalFiles" to totalFiles,
    )

    companion object {
        fun fromJson(o: JSONObject): RomInfo = RomInfo().apply {
            fileName = o.optString("fileName"); fileSize = o.optLong("fileSize"); type = o.optString("type", "UNKNOWN")
            typeTitle = o.optString("typeTitle"); flashMethod = o.optString("flashMethod"); vendor = o.optString("vendor")
            osName = o.optString("osName", "Android"); osVersion = o.optString("osVersion"); androidVersion = o.optString("androidVersion")
            sdk = o.optInt("sdk"); device = o.optString("device"); model = o.optString("model"); fingerprint = o.optString("fingerprint")
            buildId = o.optString("buildId"); incremental = o.optString("incremental"); securityPatch = o.optString("securityPatch")
            buildDate = o.optLong("buildDate"); soc = o.optString("soc"); socDetail = o.optString("socDetail")
            kernelVersion = o.optString("kernelVersion"); bootHeader = o.optString("bootHeader"); bootCmdline = o.optString("bootCmdline")
            bootCompression = o.optString("bootCompression"); ramdiskCompression = o.optString("ramdiskCompression")
            formats = o.optString("formats"); signed = o.optBoolean("signed"); certSubject = o.optString("certSubject")
            payloadIncremental = o.optBoolean("payloadIncremental"); dynamicPartitions = o.optString("dynamicPartitions")
            virtualAB = o.optString("virtualAB"); isoPath = o.optString("isoPath"); payloadPath = o.optString("payloadPath")
            arm64Kernel = o.optBoolean("arm64Kernel"); moduleName = o.optString("moduleName"); moduleVersion = o.optString("moduleVersion")
            moduleAuthor = o.optString("moduleAuthor"); totalFiles = o.optInt("totalFiles")
            o.optJSONArray("partitions")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { partitions += Part(it.getString("n"), it.getLong("s"), it.optString("d")) } }
            fun strs(k: String, to: MutableList<String>) { o.optJSONArray(k)?.let { a -> for (i in 0 until a.length()) to += a.getString(i) } }
            strs("uiPrint", uiPrint); strs("asserts", asserts); strs("notes", notes); strs("files", files)
        }

        fun androidForSdk(sdk: Int): String = when (sdk) {
            in 1..3 -> "1.5"; 4 -> "1.6"; in 5..7 -> "2.1"; 8 -> "2.2"; in 9..10 -> "2.3"; in 11..13 -> "3.x"
            in 14..15 -> "4.0"; 16 -> "4.1"; 17 -> "4.2"; 18 -> "4.3"; 19, 20 -> "4.4"; 21 -> "5.0"; 22 -> "5.1"
            23 -> "6.0"; 24 -> "7.0"; 25 -> "7.1"; 26 -> "8.0"; 27 -> "8.1"; 28 -> "9"; 29 -> "10"; 30 -> "11"
            31 -> "12"; 32 -> "12L"; 33 -> "13"; 34 -> "14"; 35 -> "15"; 36 -> "16"; 37 -> "17"; 38 -> "18"
            else -> ""
        }

        fun sdkForAndroid(v: String): Int {
            val major = v.substringBefore('.').toIntOrNull() ?: return 0
            return when {
                major >= 10 -> major + 19
                v.startsWith("9") -> 28; v.startsWith("8.1") -> 27; v.startsWith("8") -> 26
                v.startsWith("7.1") -> 25; v.startsWith("7") -> 24; v.startsWith("6") -> 23
                v.startsWith("5.1") -> 22; v.startsWith("5") -> 21; v.startsWith("4.4") -> 19
                else -> 0
            }
        }
    }
}

/** Превращает ScanResult в RomInfo. */
object RomAnalyzer {
    fun analyze(s: ScanResult, fileName: String, fileSize: Long, zipComment: ByteArray? = null): RomInfo {
        val i = RomInfo()
        i.fileName = fileName; i.fileSize = fileSize
        i.formats = s.formats.joinToString(" → ")
        i.totalFiles = s.files.size
        i.files += s.files.map { it.path }
        val props = LinkedHashMap<String, String>()
        val names = s.files.map { it.base.lowercase() }.toSet()
        val lowerPaths = s.files.map { it.path.lowercase() }

        // --- свойства из любых build.prop / prop.default
        s.small.filterKeys { it.endsWith(".prop") && !it.endsWith("module.prop") }.forEach { (_, v) -> parseProps(String(v, Charsets.UTF_8), props) }
        // --- boot-образы
        val boots = s.boots.mapNotNull { (p, b) -> BootImage.parse(b)?.let { p to it } }
        boots.forEach { (_, b) -> b.ramdiskFiles.filterKeys { it.endsWith("prop.default") || it.endsWith("default.prop") || it.endsWith("build.prop") }
            .forEach { (_, v) -> parseProps(String(v, Charsets.UTF_8), props, overwrite = false) } }
        // --- payload: boot из payload
        var payloadBoots = emptyList<Pair<String, BootImage>>()
        s.payload?.let { pl ->
            payloadBoots = listOf("boot", "init_boot", "vendor_boot").mapNotNull { n ->
                pl.partition(n)?.takeIf { !it.incremental && it.size <= RomScanner.BOOT_LIMIT }?.let { part ->
                    runCatching { BootImage.parse(pl.extractBytes(part)) }.getOrNull()?.let { n to it }
                }
            }
            payloadBoots.forEach { (_, b) -> b.ramdiskFiles.filterKeys { it.endsWith("prop.default") || it.endsWith("build.prop") }
                .forEach { (_, v) -> parseProps(String(v, Charsets.UTF_8), props, overwrite = false) } }
        }
        val allBoots = payloadBoots + boots
        s.parsedBoots = allBoots
        val kernelBoot = allBoots.firstOrNull { it.second.hasKernel && it.second.kind == "boot" && it.first.substringAfterLast('/').startsWith("boot") }
            ?: allBoots.firstOrNull { it.second.hasKernel }
        kernelBoot?.second?.let { b ->
            i.kernelVersion = b.kernelVersion ?: ""
            i.bootHeader = "v${b.headerVersion}, страница ${b.pageSize}"
            i.bootCmdline = b.cmdline
            i.bootCompression = b.kernelCompression
            i.ramdiskCompression = allBoots.firstOrNull { it.second.ramdisk.isNotEmpty() }?.second?.ramdiskCompression ?: ""
            i.arm64Kernel = b.isArm64Image
            if (i.securityPatch.isEmpty()) b.patchLevel?.let { i.securityPatch = it }
            if (i.androidVersion.isEmpty()) b.osVersion?.let { i.androidVersion = it }
        }

        // --- метаданные OTA
        val meta = s.text { it.endsWith("META-INF/com/android/metadata") }?.second?.let { parseProps(it) } ?: emptyMap()
        val fp = meta["post-build"] ?: props["ro.build.fingerprint"] ?: props["ro.system.build.fingerprint"]
            ?: props["ro.vendor.build.fingerprint"] ?: props["ro.bootimage.build.fingerprint"] ?: ""
        i.fingerprint = fp
        parseFingerprint(fp)?.let { f ->
            i.vendor = f.brand; i.device = f.device; i.androidVersion = f.release; i.buildId = f.id; i.incremental = f.incremental
        }
        meta["post-build-incremental"]?.let { i.incremental = it }
        meta["post-sdk-level"]?.toIntOrNull()?.let { i.sdk = it }
        meta["post-security-patch-level"]?.let { i.securityPatch = it }
        meta["post-timestamp"]?.toLongOrNull()?.let { i.buildDate = it }
        meta["pre-device"]?.let { if (i.device.isEmpty()) i.device = it.substringBefore(',') }
        props["ro.product.device"]?.let { if (i.device.isEmpty()) i.device = it }
        (props["ro.product.model"] ?: props["ro.product.vendor.model"] ?: props["ro.product.system.model"])?.takeIf { !it.startsWith("Generic") && !it.contains("mainline", true) && !it.startsWith("AOSP on") }?.let { i.model = it }
        (props["ro.product.brand"] ?: props["ro.product.system.brand"] ?: props["ro.product.manufacturer"])?.let { if (i.vendor.isEmpty()) i.vendor = it }
        props["ro.build.version.release"]?.let { if (i.androidVersion.isEmpty()) i.androidVersion = it }
        props["ro.build.version.sdk"]?.toIntOrNull()?.let { if (i.sdk == 0) i.sdk = it }
        props["ro.build.version.security_patch"]?.let { if (i.securityPatch.isEmpty()) i.securityPatch = it }
        props["ro.build.date.utc"]?.toLongOrNull()?.let { if (i.buildDate == 0L) i.buildDate = it }
        props["ro.build.version.incremental"]?.let { if (i.incremental.isEmpty()) i.incremental = it }
        props["ro.build.display.id"]?.let { if (i.buildId.isEmpty()) i.buildId = it }

        // --- payload.bin
        s.payload?.let { pl ->
            i.payloadPath = s.payloadPath ?: ""
            i.payloadIncremental = pl.isIncremental
            if (i.securityPatch.isEmpty()) pl.securityPatch?.let { i.securityPatch = it }
            if (i.buildDate == 0L) i.buildDate = pl.maxTimestamp
            pl.partitions.forEach { p ->
                i.partitions += RomInfo.Part(p.name, p.size, p.methods.joinToString("+") + " · ${p.ops.size} блоков")
            }
            if (pl.groups.isNotEmpty()) i.dynamicPartitions = pl.groups.filter { it.partitions.isNotEmpty() }
                .joinToString("; ") { g -> "${g.name}: ${g.partitions.joinToString(", ")}" }
            i.virtualAB = when {
                pl.vabcEnabled -> "Virtual A/B со сжатием" + (pl.vabcCompression?.let { " ($it)" } ?: "")
                pl.snapshotEnabled -> "Virtual A/B"
                else -> ""
            }
        }
        if (s.payloadInStream) i.notes += "payload.bin лежит внутри сжатого архива — для полной работы распакуйте внешний архив и импортируйте .zip"

        // --- скрипты разных производителей
        s.text { it.endsWith("updater-script") }?.second?.let { parseUpdaterScript(it, i) }
        s.textByBase("module.prop")?.second?.let { t ->
            val m = parseProps(t)
            i.moduleName = m["name"] ?: m["id"] ?: ""; i.moduleVersion = m["version"] ?: ""; i.moduleAuthor = m["author"] ?: ""
        }
        val flashAll = s.textByBase("flash_all.sh", "flash-all.sh", "flash_all_except_storage.sh")?.second
        flashAll?.let { parseFlashAll(it, i) }
        s.textByBase("android-info.txt")?.second?.let { t ->
            Regex("require board=([\\w|-]+)").find(t)?.let { if (i.device.isEmpty()) i.device = it.groupValues[1].substringBefore('|') }
        }
        val motoXml = s.textByBase("flashfile.xml", "servicefile.xml")?.second
        motoXml?.let { parseMoto(it, i) }
        val scatter = s.text { k -> k.substringAfterLast('/').lowercase().let { it.contains("scatter") && it.endsWith(".txt") } }?.second
        scatter?.let { parseScatter(it, i) }
        val rawprog = s.small.filterKeys { it.substringAfterLast('/').lowercase().startsWith("rawprogram") }
        rawprog.values.forEach { parseRawprogram(String(it, Charsets.UTF_8), i) }
        if (s.packedPartitions.isNotEmpty() && i.partitions.isEmpty()) s.packedPartitions.forEach { (n, sz) -> i.partitions += RomInfo.Part(n, sz, "") }
        if (s.superPartitions.isNotEmpty()) {
            i.dynamicPartitions = (if (i.dynamicPartitions.isEmpty()) "" else i.dynamicPartitions + "; ") +
                "super: " + s.superPartitions.joinToString(", ") { it.first }
        }
        // разделы-образы из архива (fastboot, odin и т.п.)
        if (i.partitions.isEmpty()) {
            s.files.filter { f ->
                val b = f.base.lowercase()
                (b.endsWith(".img") || b.endsWith(".img.lz4") || b.endsWith(".bin") || b.endsWith(".bin.lz4") || b.endsWith(".mbn") ||
                    b.endsWith(".elf") || b.endsWith(".elf.lz4") || b.endsWith(".sin")) && f.size > 0
            }.take(200).forEach { f -> i.partitions += RomInfo.Part(f.base.removeSuffix(".lz4").substringBeforeLast('.'), f.size, f.kind) }
        }

        // --- тип пакета
        val hasOdin = lowerPaths.any { p -> p.substringAfterLast('/').let { it.startsWith("ap_") || it.startsWith("bl_") || it.startsWith("cp_") || it.startsWith("csc_") || it.startsWith("home_csc_") } } ||
            s.formats.contains("tar.md5")
        when {
            s.encrypted != null -> {
                i.type = "ENCRYPTED"
                when (s.encrypted) {
                    "ozip" -> { i.typeTitle = "OPPO / Realme .ozip (зашифровано)"; i.vendor = i.vendor.ifEmpty { "OPPO" }
                        i.notes += "Файл зашифрован OPPO. Расшифруйте его в обычный .zip (утилита ozipdecrypt) и импортируйте снова." }
                    "ops" -> { i.typeTitle = "OnePlus MSM (.ops, зашифровано)"; i.vendor = i.vendor.ifEmpty { "OnePlus" }
                        i.notes += "Пакет для MSM Download Tool зашифрован. Возьмите OTA-прошивку .zip (payload.bin) для этой модели." }
                    else -> { i.typeTitle = "LG KDZ"; i.vendor = i.vendor.ifEmpty { "LG" }
                        i.notes += "KDZ — фирменный формат LG Flash Tool. Распакуйте его (kdztools) в .img или возьмите OTA .zip." }
                }
                i.flashMethod = "фирменная утилита производителя"
            }
            s.isoPath != null -> {
                i.type = "X86"; i.typeTitle = "x86-прошивка (Android-x86 / Bliss OS / PrimeOS)"; i.isoPath = s.isoPath!!
                i.flashMethod = "полный запуск в QEMU"; i.soc = "x86 (эмуляция)"
            }
            s.payload != null -> {
                i.type = if (s.payload!!.isIncremental) "AB_INCREMENTAL" else "AB_OTA"
                i.typeTitle = if (s.payload!!.isIncremental) "Инкрементальное A/B OTA (payload.bin)" else "Полная A/B-прошивка (payload.bin)"
                i.flashMethod = "recovery / sideload / update_engine"
            }
            hasOdin -> { i.type = "ODIN"; i.typeTitle = "Samsung Odin (.tar.md5)"; i.vendor = i.vendor.ifEmpty { "samsung" }; i.flashMethod = "Odin / Heimdall (Download Mode)" }
            motoXml != null -> { i.type = "MOTO"; i.typeTitle = "Motorola fastboot (flashfile.xml)"; i.vendor = i.vendor.ifEmpty { "motorola" }; i.flashMethod = "fastboot" }
            scatter != null -> { i.type = "MTK"; i.typeTitle = "MediaTek SP Flash Tool (scatter)"; i.flashMethod = "SP Flash Tool (BROM/Preloader)" }
            rawprog.isNotEmpty() -> { i.type = "EDL"; i.typeTitle = "Qualcomm EDL / firehose (rawprogram.xml)"; i.flashMethod = "QFIL / EDL 9008" }
            s.formats.contains("UPDATE.APP") -> { i.type = "HUAWEI"; i.typeTitle = "Huawei / Honor UPDATE.APP"; i.vendor = i.vendor.ifEmpty { "HUAWEI" }; i.flashMethod = "dload / eRecovery" }
            s.formats.contains("pac") -> { i.type = "PAC"; i.typeTitle = "Unisoc / Spreadtrum .pac"; i.flashMethod = "UpgradeDownload / ResearchDownload"
                s.productName?.let { if (i.model.isEmpty()) i.model = it }; s.productVersion?.let { if (i.buildId.isEmpty()) i.buildId = it } }
            names.any { it.startsWith("image-") && it.endsWith(".zip") } || (flashAll != null && names.any { it.startsWith("bootloader-") }) ->
                { i.type = "PIXEL_FACTORY"; i.typeTitle = "Factory image (Google Pixel / Nothing и др.)"; i.flashMethod = "fastboot (flash-all)" }
            flashAll != null || lowerPaths.any { it.contains("images/") && it.endsWith(".img") } ->
                { i.type = "FASTBOOT"; i.typeTitle = "Fastboot-прошивка (flash_all)"; i.flashMethod = "fastboot" }
            s.textByBase("module.prop") != null -> { i.type = "MAGISK"; i.typeTitle = "Magisk / KernelSU модуль"; i.flashMethod = "Magisk / recovery" }
            s.textByBase("anykernel.sh") != null -> { i.type = "ANYKERNEL"; i.typeTitle = "Ядро AnyKernel3"; i.flashMethod = "recovery" }
            s.text { it.endsWith("updater-script") } != null -> {
                i.type = "RECOVERY"
                i.typeTitle = if (names.any { it.endsWith(".new.dat") || it.endsWith(".new.dat.br") }) "Recovery-прошивка (block-based)" else "Recovery-прошивка (file-based)"
                i.flashMethod = "TWRP / OrangeFox / stock recovery"
            }
            s.boots.isNotEmpty() || s.files.any { it.kind in setOf("ext4", "erofs", "sparse", "super") || it.kind.startsWith("sparse:") || it.kind.startsWith("lz4:") } -> {
                i.type = "IMAGE"; i.typeTitle = "Образ раздела (boot / system / super / GSI)"; i.flashMethod = "fastboot / TWRP (Install Image)"
            }
            else -> { i.type = "UNKNOWN"; i.typeTitle = "Неизвестный пакет (" + (s.formats.firstOrNull() ?: "файл") + ")"
                if ("7z" in s.formats || "rar" in s.formats) i.notes += "7z/RAR пока не поддерживаются — распакуйте архив и импортируйте файл прошивки (.zip, .tgz, .tar.md5, .img)." }
        }

        // --- подпись OTA (футер в комментарии zip)
        zipComment?.let { c ->
            if (c.size >= 6 && (c[c.size - 3].toInt() and 0xff) == 0xff && (c[c.size - 4].toInt() and 0xff) == 0xff) i.signed = true
        }
        s.small.entries.firstOrNull { it.key.endsWith("otacert") }?.value?.let { cert ->
            runCatching {
                val cf = java.security.cert.CertificateFactory.getInstance("X.509")
                val x = cf.generateCertificate(cert.inputStream()) as java.security.cert.X509Certificate
                i.certSubject = x.subjectX500Principal.name
            }
        }

        // --- производитель / оболочка / процессор
        detectSoc(i, s)
        detectOs(i, fileName)
        if (i.androidVersion.isEmpty() && i.sdk > 0) i.androidVersion = RomInfo.androidForSdk(i.sdk)
        if (i.sdk == 0 && i.androidVersion.isNotEmpty()) i.sdk = RomInfo.sdkForAndroid(i.androidVersion)
        if (i.androidVersion.isEmpty()) Regex("(?i)(?:android|_os|_a)[ _-]?(1[0-9]|[5-9])(?![0-9])").find(fileName)?.let { i.androidVersion = it.groupValues[1] }
        if (i.type == "AB_INCREMENTAL") i.notes += "Это инкрементальное обновление: оно ставится только поверх предыдущей версии. Для установки с нуля нужна полная (full) прошивка."
        return i
    }

    // ------------------------------------------------------------------ вспомогательное

    fun parseProps(t: String, to: MutableMap<String, String> = LinkedHashMap(), overwrite: Boolean = true): MutableMap<String, String> {
        t.lineSequence().forEach { l ->
            val line = l.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val eq = line.indexOf('='); if (eq <= 0) return@forEach
            val k = line.substring(0, eq).trim(); val v = line.substring(eq + 1).trim()
            if (overwrite || k !in to) to[k] = v
        }
        return to
    }

    class Fingerprint(val brand: String, val product: String, val device: String, val release: String, val id: String, val incremental: String)

    fun parseFingerprint(fp: String): Fingerprint? {
        val m = Regex("^([^/]+)/([^/]+)/([^:]+):([^/]+)/([^/]+)/([^:]+):").find(fp) ?: return null
        val g = m.groupValues
        return Fingerprint(g[1], g[2], g[3], g[4], g[5], g[6])
    }

    private fun parseUpdaterScript(t: String, i: RomInfo) {
        Regex("ui_print\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(t).forEach { i.uiPrint += it.groupValues[1].replace("\\\"", "\"").replace("\\n", "") }
        Regex("getprop\\(\"ro\\.(?:product\\.)?(?:device|build\\.product)\"\\)\\s*==\\s*\"([^\"]+)\"").findAll(t).forEach {
            if (it.groupValues[1] !in i.asserts) i.asserts += it.groupValues[1]
        }
        if (i.device.isEmpty() && i.asserts.isNotEmpty()) i.device = i.asserts.first()
        Regex("block_image_update\\(\"([^\"]+)\"").findAll(t).forEach { m ->
            val n = m.groupValues[1].substringAfterLast('/')
            if (i.partitions.none { it.name == n }) i.partitions += RomInfo.Part(n, 0, "block_image_update")
        }
        Regex("package_extract_file\\(\"([^\"]+\\.img)\",\\s*\"([^\"]+)\"").findAll(t).forEach { m ->
            val n = m.groupValues[2].substringAfterLast('/')
            if (i.partitions.none { it.name == n }) i.partitions += RomInfo.Part(n, 0, m.groupValues[1])
        }
    }

    private fun parseFlashAll(t: String, i: RomInfo) {
        Regex("grep\\s+\"\\^product:\\s*\\*?([\\w|-]+)").find(t)?.let { if (i.device.isEmpty()) i.device = it.groupValues[1].substringBefore('|') }
        Regex("fastboot\\s+(?:\\$\\*\\s+|-w\\s+|%\\*\\s+)*flash\\s+(\\S+)\\s+(\\S+)").findAll(t).forEach { m ->
            val n = m.groupValues[1]
            if (n != "bootloader" || i.partitions.none { it.name == n })
                if (i.partitions.none { it.name == n }) i.partitions += RomInfo.Part(n, 0, m.groupValues[2].substringAfterLast('/').trim('"'))
        }
    }

    private fun parseMoto(t: String, i: RomInfo) {
        Regex("<phone_model\\s+model=\"([^\"]+)\"").find(t)?.let { i.model = it.groupValues[1] }
        Regex("<software_version\\s+version=\"([^\"]+)\"").find(t)?.let { i.buildId = it.groupValues[1] }
        Regex("<step[^>]*operation=\"flash\"[^>]*>").findAll(t).forEach { m ->
            val s = m.value
            val p = Regex("partition=\"([^\"]+)\"").find(s)?.groupValues?.get(1) ?: return@forEach
            val f = Regex("filename=\"([^\"]+)\"").find(s)?.groupValues?.get(1) ?: ""
            if (i.partitions.none { it.name == p }) i.partitions += RomInfo.Part(p, 0, f)
        }
    }

    private fun parseScatter(t: String, i: RomInfo) {
        Regex("platform:\\s*(MT\\w+)").find(t)?.let { i.socDetail = it.groupValues[1] }
        Regex("project:\\s*(\\S+)").find(t)?.let { if (i.device.isEmpty()) i.device = it.groupValues[1] }
        val blocks = t.split(Regex("- partition_index:"))
        blocks.drop(1).forEach { b ->
            val name = Regex("partition_name:\\s*(\\S+)").find(b)?.groupValues?.get(1) ?: return@forEach
            val file = Regex("file_name:\\s*(\\S+)").find(b)?.groupValues?.get(1) ?: ""
            val dl = Regex("is_download:\\s*(\\S+)").find(b)?.groupValues?.get(1) == "true"
            val size = Regex("partition_size:\\s*0x([0-9a-fA-F]+)").find(b)?.groupValues?.get(1)?.toLongOrNull(16) ?: 0L
            if (dl && file != "NONE") i.partitions += RomInfo.Part(name, size, file)
        }
    }

    private fun parseRawprogram(t: String, i: RomInfo) {
        Regex("<program\\s[^>]*>").findAll(t).forEach { m ->
            val s = m.value
            val label = Regex("label=\"([^\"]*)\"").find(s)?.groupValues?.get(1) ?: return@forEach
            val file = Regex("filename=\"([^\"]*)\"").find(s)?.groupValues?.get(1) ?: ""
            if (file.isEmpty()) return@forEach
            val sectors = Regex("num_partition_sectors=\"(\\d+)\"").find(s)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val ss = Regex("SECTOR_SIZE_IN_BYTES=\"(\\d+)\"").find(s)?.groupValues?.get(1)?.toLongOrNull() ?: 4096L
            if (i.partitions.none { it.name == label }) i.partitions += RomInfo.Part(label, sectors * ss, file)
        }
    }

    private val QCOM = setOf("xbl", "xbl_config", "abl", "aop", "aop_config", "tz", "hyp", "devcfg", "qupfw", "cpucp", "shrm",
        "uefisecapp", "uefi", "keymaster", "cmnlib", "cmnlib64", "featenabler", "imagefv", "rpm", "sbl1", "aboot", "dsp", "bluetooth", "multiimgoem")
    private val MTK = setOf("preloader", "lk", "md1img", "scp", "sspm", "spmfw", "mcupm", "dpm", "gz", "tinysys", "md1dsp", "cam_vpu1", "gpueb", "vcp", "pi_img")
    private val TENSOR = setOf("bl1", "pbl", "bl2", "bl31", "tzsw", "gsa", "ldfw", "gcf")
    private val UNISOC = setOf("sml", "trustos", "uboot", "fdl", "fdl2", "l_modem", "pm_sys", "l_ldsp", "l_gdsp", "teecfg")
    private val EXYNOS = setOf("sboot", "cm", "keystorage", "epbl", "bl1_exynos", "el3_mon", "tzsw_exynos")
    private val KIRIN = setOf("xloader", "fastboot", "teeos", "sensorhub", "modem_fw", "hisee_img", "lpm3", "fw_lpm3", "hhee", "trustfirmware")

    private fun detectSoc(i: RomInfo, s: ScanResult) {
        val parts = (i.partitions.map { it.name } + s.files.map { it.base } + s.packedPartitions.map { it.first })
            .map { it.lowercase().removeSuffix(".lz4").substringBeforeLast('.').removeSuffix("_a").removeSuffix("_b") }.toSet()
        fun hits(set: Set<String>) = parts.count { it in set }
        val scores = listOf(
            "Qualcomm Snapdragon" to hits(QCOM), "MediaTek" to hits(MTK), "Google Tensor" to hits(TENSOR),
            "Unisoc / Spreadtrum" to hits(UNISOC), "Samsung Exynos" to hits(EXYNOS), "HiSilicon Kirin" to hits(KIRIN),
        )
        val best = scores.maxBy { it.second }
        if (i.soc.isEmpty() && best.second >= 2) i.soc = best.first
        if (i.soc.isEmpty() && i.socDetail.startsWith("MT")) i.soc = "MediaTek"
        if (i.soc.isEmpty() && i.type == "PAC") i.soc = "Unisoc / Spreadtrum"
        // платформа из boot cmdline (androidboot.hardware=qcom и т.п.)
        if (i.socDetail.isEmpty()) Regex("androidboot\\.hardware=(\\S+)").find(i.bootCmdline)?.let { i.socDetail = it.groupValues[1] }
    }

    private fun detectOs(i: RomInfo, fileName: String) {
        val fn = fileName.lowercase()
        val brand = i.vendor.lowercase()
        val inc = i.incremental
        val customs = listOf(
            "lineage" to "LineageOS", "crdroid" to "crDroid", "evolution" to "Evolution X", "pixelos" to "PixelOS",
            "pixelexperience" to "Pixel Experience", "arrow" to "ArrowOS", "derpfest" to "DerpFest", "havoc" to "Havoc-OS",
            "rising" to "RisingOS", "yaap" to "YAAP", "calyx" to "CalyxOS", "graphene" to "GrapheneOS", "paranoid" to "Paranoid Android",
            "omni" to "OmniROM", "aicp" to "AICP", "resurrection" to "Resurrection Remix", "superior" to "SuperiorOS",
            "matrixx" to "Project Matrixx", "infinity" to "Project Infinity X", "afterlife" to "AfterlifeOS", "alphadroid" to "AlphaDroid",
            "e-" to "/e/OS", "bliss" to "Bliss OS", "primeos" to "PrimeOS", "android-x86" to "Android-x86", "voltage" to "VoltageOS",
        )
        val custom = customs.firstOrNull { (k, _) -> if (k == "e-") fn.startsWith("e-") else fn.contains(k) }
        val miBrand = brand in setOf("xiaomi", "redmi", "poco") || fn.contains("miui") || fn.contains("hyperos")
        when {
            custom != null -> {
                i.osName = custom.second
                Regex("(?i)${Regex.escape(custom.first.trimEnd('-'))}[-_ ]?(?:os[-_])?v?(\\d+(?:\\.\\d+)*)").find(fileName)?.let { i.osVersion = it.groupValues[1] }
            }
            Regex("^OS(\\d+\\.\\d+(?:\\.\\d+)*)").find(inc) != null || fn.contains("hyperos") || Regex("(?i)_OS\\d\\.\\d+\\.\\d+").containsMatchIn(fileName) && miBrand -> {
                i.osName = "Xiaomi HyperOS"
                (Regex("^OS(\\d+(?:\\.\\d+){1,3})").find(inc) ?: Regex("(?i)OS(\\d+(?:\\.\\d+){2,3})").find(fileName))?.let { i.osVersion = it.groupValues[1] }
                if (i.vendor.isEmpty()) i.vendor = "Xiaomi"
            }
            miBrand || Regex("^V1\\d\\.\\d").containsMatchIn(inc) -> {
                i.osName = "MIUI"
                (Regex("^V(\\d+(?:\\.\\d+){1,3})").find(inc) ?: Regex("(?i)V(1\\d(?:\\.\\d+){2,3})").find(fileName))?.let { i.osVersion = it.groupValues[1] }
                if (i.vendor.isEmpty()) i.vendor = "Xiaomi"
            }
            brand == "samsung" -> i.osName = "Samsung One UI"
            brand == "oneplus" -> i.osName = if (fn.contains("colos") || fn.contains("coloros")) "ColorOS" else "OxygenOS"
            brand == "oppo" -> i.osName = "ColorOS"
            brand == "realme" -> i.osName = "realme UI"
            brand == "huawei" -> i.osName = if (fn.contains("harmony")) "HarmonyOS" else "EMUI"
            brand == "honor" -> i.osName = "MagicOS"
            brand == "vivo" || brand == "iqoo" -> i.osName = "Funtouch OS / OriginOS"
            brand == "nothing" -> i.osName = "Nothing OS"
            brand == "motorola" -> i.osName = "Motorola (Android)"
            brand == "google" -> i.osName = "Android (Google Pixel)"
            brand == "sony" -> i.osName = "Xperia (Android)"
            brand == "asus" -> i.osName = "ASUS ZenUI / ROG UI"
            brand == "nokia" || brand == "hmd" -> i.osName = "Android One (Nokia/HMD)"
            brand == "lge" || brand == "lg" -> i.osName = "LG UX"
            i.type == "X86" -> i.osName = "Android-x86"
            i.type == "MAGISK" -> i.osName = "Magisk-модуль"
            else -> i.osName = "Android"
        }
        if (i.osVersion.isEmpty() && i.osName.startsWith("Android") && i.androidVersion.isNotEmpty() && i.type != "MAGISK") i.osVersion = ""
        i.vendor = prettyBrand(i.vendor)
    }

    fun prettyBrand(b: String): String = when (b.lowercase()) {
        "" -> ""; "xiaomi" -> "Xiaomi"; "redmi" -> "Redmi"; "poco" -> "POCO"; "samsung" -> "Samsung"; "google" -> "Google"
        "oneplus" -> "OnePlus"; "oppo" -> "OPPO"; "realme" -> "realme"; "huawei" -> "HUAWEI"; "honor" -> "HONOR"
        "motorola" -> "Motorola"; "lge", "lg" -> "LG"; "sony" -> "Sony"; "nokia" -> "Nokia"; "asus" -> "ASUS"; "vivo" -> "vivo"
        "nothing" -> "Nothing"; else -> b.replaceFirstChar { it.uppercase() }
    }
}
