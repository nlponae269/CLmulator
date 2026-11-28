package app.clmulator.rom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * Тесты на настоящих прошивках. Файлы не хранятся в репозитории:
 * положите их в каталог -Dtestdata (по умолчанию /home/user/testdata).
 * Синтетические пакеты других производителей делает tools/make_test_fixtures.py.
 */
class RomScanTest {
    private val dir = File(System.getProperty("testdata") ?: "/home/user/testdata")

    private fun scan(f: File, verify: Boolean = false): Pair<ScanResult, RomInfo> {
        val src = ChannelSource.of(f)
        val sc = RomScanner(verify = verify, log = { println("   log: $it") })
        val r = sc.scan(src, f.name)
        val comment = runCatching { ZipArchive(ChannelSource.of(f)).use { it.comment } }.getOrNull()
        val info = RomAnalyzer.analyze(r, f.name, f.length(), comment)
        println("== ${f.name}: ${info.typeTitle} | ${info.vendor} ${info.title} | Android ${info.androidVersion} (API ${info.sdk}) | " +
            "device=${info.device} model=${info.model} | patch=${info.securityPatch} | soc=${info.soc} ${info.socDetail} | " +
            "kernel=${info.kernelVersion.take(70)} | parts=${info.partitions.size} | formats=${info.formats} | errors=${r.verifyErrors}")
        info.notes.forEach { println("   note: $it") }
        // JSON туда-обратно
        val back = RomInfo.fromJson(info.toJson())
        assertEquals(info.title, back.title); assertEquals(info.partitions.size, back.partitions.size)
        return r to info
    }

    @Test
    fun realLineageAbOta() {
        val f = File(dir, "lineage-panther.zip")
        assumeTrue("нет $f", f.exists())
        val (r, info) = scan(f)
        assertEquals("AB_OTA", info.type)
        assertEquals("panther", info.device)
        assertEquals("LineageOS", info.osName)
        assertEquals("Google Tensor", info.soc)
        assertTrue(info.kernelVersion.startsWith("Linux version"))
        assertTrue(info.arm64Kernel)
        assertTrue(info.signed)
        val pl = r.payload!!
        // проверка целостности и распаковка маленьких разделов
        for (name in listOf("vbmeta", "init_boot", "boot", "vendor_boot")) {
            val p = pl.partition(name)!!
            assertEquals("SHA-256 $name", 0, pl.verify(p))
            val out = File(dir, "$name.img")
            RandomAccessFile(out, "rw").use { pl.extract(p, it.channel) }
            assertEquals(p.size, out.length())
        }
        val vb = File(dir, "vbmeta.img").readBytes()
        assertEquals("AVB0", String(vb, 0, 4))
        val boot = BootImage.parse(File(dir, "boot.img").readBytes())!!
        println("boot: v${boot.headerVersion} kernel=${boot.kernelCompression} ${boot.kernelVersion}")
        val ib = BootImage.parse(File(dir, "init_boot.img").readBytes())!!
        println("init_boot: ramdisk=${ib.ramdiskCompression} files=${ib.ramdiskFiles.size} init=${ib.ramdiskFiles.containsKey("init")}")
        assertTrue(ib.ramdiskFiles.containsKey("init"))
        val vbt = BootImage.parse(File(dir, "vendor_boot.img").readBytes())!!
        println("vendor_boot: v${vbt.headerVersion} ramdisk=${vbt.ramdiskCompression} files=${vbt.ramdiskFiles.size} bootconfig=${vbt.bootconfig?.take(80)}")
    }

    @Test
    fun fixtures() {
        val fx = File(dir, "fixtures")
        assumeTrue(fx.isDirectory)
        val expect = mapOf(
            "samsung_odin.zip" to "ODIN", "xiaomi_fastboot.tgz" to "FASTBOOT", "mtk_scatter.zip" to "MTK",
            "legacy_recovery.zip" to "RECOVERY", "huawei_update.zip" to "HUAWEI", "magisk_module.zip" to "MAGISK",
            "moto_fastboot.zip" to "MOTO", "qcom_edl.zip" to "EDL", "pixel_factory.zip" to "PIXEL_FACTORY",
            "unisoc.pac" to "PAC", "boot.img" to "IMAGE", "oppo.ozip" to "ENCRYPTED", "x86_rom.zip" to "X86",
            "AP_S918BXXU3BWK6.tar.md5" to "ODIN",
        )
        for ((name, type) in expect) {
            val f = File(fx, name)
            if (!f.exists()) { println("skip $name"); continue }
            val (r, info) = scan(f, verify = true)
            assertEquals(name, type, info.type)
            assertEquals("$name verify", 0, r.verifyErrors)
        }
    }

    @Test
    fun lz4RoundTrip() {
        val f = File(dir, "fixtures/boot.img.lz4")
        assumeTrue(f.exists())
        val data = Lz4InputStream(f.inputStream().buffered()).readBytes()
        assertTrue(BootImage.isBoot(data))
    }
}
