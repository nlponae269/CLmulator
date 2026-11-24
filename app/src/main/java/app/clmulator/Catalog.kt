package app.clmulator

/**
 * Каталог прошивок. Все образы — официальные сборки проекта Android-x86
 * (https://www.android-x86.org), которые раздаются через SourceForge.
 * "CLM" — наши тестовые прошивки: тот же официальный ISO + свой initrd-оверлей
 * (root, свой build.prop, бутанимация, обои, приложение CLM Center).
 */
data class CatalogEntry(
    val id: String,
    val title: String,
    val version: String,
    val api: Int,
    val codename: String,
    /** путь на SourceForge внутри project/android-x86 */
    val isoPath: String,
    val approxMb: Int,
    val color1: Int,
    val color2: Int,
    val ramMb: Int,
    val cpus: Int,
    val cpu: String = "qemu64",
    /** id тестовой сборки (assets/testroms/<id>/initrd.img) или null для оригинала */
    val edition: String? = null,
    val note: String = "",
    val heavy: Boolean = false,
    val dpi: Int = 0,
) {
    val isoName: String get() = isoPath.substringAfterLast('/')
    val isoUrl: String get() = "https://downloads.sourceforge.net/project/android-x86/" + isoPath.replace(" ", "%20")
    val isCustom: Boolean get() = edition != null
}

object Catalog {
    private const val GREEN1 = 0xFF8BC34A.toInt()
    private const val GREEN2 = 0xFF2E7D32.toInt()

    val custom = listOf(
        CatalogEntry(
            "clm-donut", "CLM Donut Root", "1.6", 4, "Donut",
            "Release 1.6-r2/android-x86-1.6-r2.iso", 55, GREEN1, GREEN2, 512, 1,
            edition = "clm-donut",
            note = "Тестовая сборка: root для всех приложений, русский язык, CLM Center, своя модель «CLM One»"
        ),
        CatalogEntry(
            "clm-froyo", "CLM Froyo Plus", "2.2", 8, "Froyo",
            "Release 2.2/android-x86-2.2-generic.iso", 86, 0xFFFFA726.toInt(), 0xFFE65100.toInt(), 512, 1,
            edition = "clm-froyo", dpi = 160,
            note = "Тестовая сборка: root, своя бутанимация и обои, русский язык, CLM Center"
        ),
        CatalogEntry(
            "clm-kitkat", "CLM KitKat Lite", "4.4", 19, "KitKat",
            "Release 4.4/android-x86-4.4-r5.iso", 462, 0xFF42A5F5.toInt(), 0xFF283593.toInt(), 1024, 2,
            edition = "clm-kitkat",
            note = "Тестовая сборка: Superuser, своя бутанимация и обои, русский язык, CLM Center"
        ),
    )

    val official = listOf(
        CatalogEntry(
            "x86-1.6", "Android 1.6 Donut", "1.6", 4, "Donut",
            "Release 1.6-r2/android-x86-1.6-r2.iso", 55, 0xFF9CCC65.toInt(), 0xFF558B2F.toInt(), 512, 1,
            note = "Самая лёгкая. Грузится быстрее всех"
        ),
        CatalogEntry(
            "x86-2.2", "Android 2.2 Froyo", "2.2", 8, "Froyo",
            "Release 2.2/android-x86-2.2-generic.iso", 86, 0xFFFFCA28.toInt(), 0xFFF57F17.toInt(), 512, 1, dpi = 160
        ),
        CatalogEntry(
            "x86-4.0", "Android 4.0 Ice Cream Sandwich", "4.0", 14, "ICS",
            "Testing/android-x86-4.0-tegav2-20120101.iso", 194, 0xFF26C6DA.toInt(), 0xFF00838F.toInt(), 1024, 2,
            note = "Тестовая сборка проекта Android-x86 (2012)"
        ),
        CatalogEntry(
            "x86-4.4", "Android 4.4 KitKat", "4.4", 19, "KitKat",
            "Release 4.4/android-x86-4.4-r5.iso", 462, 0xFF66BB6A.toInt(), 0xFF1B5E20.toInt(), 1024, 2
        ),
        CatalogEntry(
            "x86-5.1", "Android 5.1 Lollipop", "5.1", 22, "Lollipop",
            "Release 5.1/android-x86-5.1-rc1.iso", 380, 0xFFEC407A.toInt(), 0xFF880E4F.toInt(), 1536, 2,
            cpu = "max", heavy = true
        ),
        CatalogEntry(
            "x86-6.0", "Android 6.0 Marshmallow", "6.0", 23, "Marshmallow",
            "Testing/android-x86-6.0_20160129.iso", 420, 0xFF8D6E63.toInt(), 0xFF3E2723.toInt(), 1536, 2,
            cpu = "max", heavy = true
        ),
        CatalogEntry(
            "x86-7.1", "Android 7.1 Nougat", "7.1", 25, "Nougat",
            "Release 7.1/android-x86-7.1-r5.iso", 690, 0xFF7E57C2.toInt(), 0xFF311B92.toInt(), 2048, 2,
            cpu = "max", heavy = true
        ),
        CatalogEntry(
            "x86-8.1", "Android 8.1 Oreo", "8.1", 27, "Oreo",
            "Release 8.1/android-x86-8.1-r6.iso", 830, 0xFFFF7043.toInt(), 0xFFBF360C.toInt(), 2048, 2,
            cpu = "max", heavy = true
        ),
        CatalogEntry(
            "x86-9.0", "Android 9 Pie", "9.0", 28, "Pie",
            "Release 9.0/android-x86-9.0-r2.iso", 920, 0xFF5C6BC0.toInt(), 0xFF1A237E.toInt(), 2048, 2,
            cpu = "max", heavy = true
        ),
    )

    val all: List<CatalogEntry> get() = custom + official

    fun byId(id: String) = all.firstOrNull { it.id == id }

    fun codenameFor(api: Int): String = when {
        api <= 3 -> "Cupcake"
        api <= 4 -> "Donut"
        api <= 7 -> "Eclair"
        api <= 8 -> "Froyo"
        api <= 10 -> "Gingerbread"
        api <= 13 -> "Honeycomb"
        api <= 15 -> "Ice Cream Sandwich"
        api <= 18 -> "Jelly Bean"
        api <= 20 -> "KitKat"
        api <= 22 -> "Lollipop"
        api <= 23 -> "Marshmallow"
        api <= 25 -> "Nougat"
        api <= 27 -> "Oreo"
        api <= 28 -> "Pie"
        else -> "Android"
    }

    /** "1.6" -> 4 и т.п. для импортированных образов */
    fun apiForVersion(v: String): Int = when {
        v.startsWith("1.5") -> 3
        v.startsWith("1.6") -> 4
        v.startsWith("2.0") || v.startsWith("2.1") -> 7
        v.startsWith("2.2") -> 8
        v.startsWith("2.3") -> 10
        v.startsWith("3.") -> 13
        v.startsWith("4.0") -> 15
        v.startsWith("4.1") -> 16
        v.startsWith("4.2") -> 17
        v.startsWith("4.3") -> 18
        v.startsWith("4.4") -> 19
        v.startsWith("5.0") -> 21
        v.startsWith("5.1") -> 22
        v.startsWith("6") -> 23
        v.startsWith("7.0") -> 24
        v.startsWith("7") -> 25
        v.startsWith("8.0") -> 26
        v.startsWith("8") -> 27
        v.startsWith("9") -> 28
        else -> 0
    }
}
