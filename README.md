# CLmulator

Эмулятор прошивок Android на телефоне. x86-прошивки (Android-x86, Bliss OS, PrimeOS) запускаются целиком в QEMU,
прошивки телефонов любых производителей — импортируются в **CLM Recovery** (рекавери в стиле TWRP).
Root на телефоне не нужен.

Готовый APK: [`dist/CLmulator-0.2.1.apk`](dist/CLmulator-0.2.1.apk) (arm64, Android 8+).
Версии 0.2.x подписаны другим ключом, чем 0.1.0, — перед установкой удалите старую.

## Прошивки телефонов (CLM Recovery)
Тип файла определяется по сигнатурам, вложенные архивы разбираются рекурсивно, большие файлы не копируются:

| Производитель / формат | Что внутри |
|---|---|
| Xiaomi HyperOS / MIUI, Pixel, OnePlus, OPPO, realme, Nothing, LineageOS и др. — A/B OTA `.zip` | `payload.bin`: разделы, SHA-256, распаковка XZ/BZip2 |
| Xiaomi fastboot `.tgz`, Pixel factory `.zip`, Motorola `flashfile.xml` | образы `images/*.img`, `flash_all.sh` |
| Samsung Odin `.tar.md5` (и `.zip` с AP/BL/CP/CSC) | `*.img.lz4`, проверка MD5 |
| старые recovery-zip | `updater-script` (ui_print, assert), `system.new.dat.br` |
| MediaTek SP Flash Tool, Qualcomm EDL | `*_scatter.txt`, `rawprogram*.xml` |
| Huawei / Honor `UPDATE.APP`, Unisoc `.pac` | список разделов, boot |
| `boot.img`, sparse, `super.img`, ext4/EROFS, Magisk-модули, AnyKernel3 | заголовки, ramdisk, логические разделы |
| OPPO `.ozip`, OnePlus `.ops`, LG `.kdz` | распознаются, но зашифрованы — нужна расшифровка |

Что можно сделать:
- карточка прошивки: производитель, оболочка (HyperOS, One UI, ColorOS…), Android, кодовое имя, патч, процессор, ядро, разделы;
- «прошить» в виртуальный телефон (Install со свайпом, Wipe, Backup/Restore, слоты A/B, Magisk-модули) —
  запись виртуальная, проверки контрольных сумм настоящие;
- извлечь раздел (boot.img, init_boot, vendor_boot…) в файл;
- **настоящий запуск ядра** ARM64-прошивки в `qemu-system-aarch64` (virt) с её ramdisk — живой лог консоли.

Честно: прошивка телефона целиком (до рабочего стола) на чужом железе не запускается — в ней драйверы своего
процессора, GPU, модема. После «перезагрузки» показывается помеченная симуляция с реальными данными прошивки.

## Что умеет
- Каталог: оригинальные Android-x86 1.6, 2.2, 4.0, 4.4, 5.1, 6.0, 7.1, 8.1, 9.0 (качаются с SourceForge, докачка при обрыве).
- **Тестовые прошивки CLM** (Donut 1.6 / Froyo 2.2 / KitKat 4.4): официальный ISO + свой initrd-оверлей —
  root (`su` для всех приложений на 1.6/2.2, Superuser на 4.4), своя модель и номер сборки в build.prop,
  русский язык по умолчанию, своя бутанимация (2.2/4.4), обои, приложение **CLM Center** (инфо + root-терминал),
  экран не гаснет через минуту.
- Импорт своих ISO / образов дисков (Samsung/Xiaomi-прошивки реальных телефонов не запускаются — это другое железо).
- Сохранение данных между запусками (диск ext2 `DATA=sda`), настройки ОЗУ / ядер / разрешения / DPI / CPU.
- Экран через встроенный VNC QEMU (unix-сокет), касания → usb-tablet (4.0+) или PS/2-мышь (1.6/2.2),
  кнопки Назад / Домой / Меню / клавиатура / питание.

## Сборка
```bash
python3 tools/prepare_qemu.py         # QEMU x86_64 + aarch64 + библиотеки из пакетов Termux -> jniLibs, BIOS -> assets
python3 tools/build_testroms.py       # initrd тестовых прошивок CLM (нужны xorriso, cpio, gcc -m32)
python3 tools/make_data_template.py   # шаблон пустого диска данных
./gradlew assembleRelease
./gradlew testDebugUnitTest -Dtestdata=/путь/к/прошивкам   # тесты на настоящих прошивках (необязательно)
```

Лицензии: QEMU — GPL-2.0, Android-x86 — Apache-2.0/GPL, сборка QEMU — пакеты Termux.
