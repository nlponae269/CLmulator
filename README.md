# CLmulator

Старые прошивки Android (1.6 – 9.0) на современном телефоне. Внутри — QEMU (эмуляция x86, TCG)
и сборки проекта [Android-x86](https://www.android-x86.org). Root на телефоне не нужен.

Готовый APK: [`dist/CLmulator-0.1.0.apk`](dist/CLmulator-0.1.0.apk) (arm64, Android 8+).

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
python3 tools/prepare_qemu.py         # QEMU + библиотеки из пакетов Termux -> jniLibs, BIOS -> assets
python3 tools/build_testroms.py       # initrd тестовых прошивок CLM (нужны xorriso, cpio, gcc -m32)
python3 tools/make_data_template.py   # шаблон пустого диска данных
./gradlew assembleRelease
```

Лицензии: QEMU — GPL-2.0, Android-x86 — Apache-2.0/GPL, сборка QEMU — пакеты Termux.
