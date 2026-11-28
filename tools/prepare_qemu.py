#!/usr/bin/env python3
"""
Готовит QEMU (из пакетов Termux, aarch64) для упаковки в APK.

Что делает:
  1. Качает индекс пакетов Termux и нужные .deb (qemu-system-x86-64 и qemu-system-aarch64 headless + зависимости).
  2. Вычисляет замыкание DT_NEEDED для бинарников qemu-system-x86_64 и qemu-system-aarch64.
  3. Переименовывает все библиотеки в вид lib*.so (Android распаковывает из APK только такие),
     правя имена прямо в .dynstr (новое имя всегда не длиннее старого, поэтому без patchelf).
  4. Кладёт всё в app/src/main/jniLibs/arm64-v8a, а BIOS-файлы в app/src/main/assets/qemu.

Использование: python3 tools/prepare_qemu.py [workdir]
"""
import gzip, io, os, re, shutil, subprocess, sys, tarfile, urllib.request

REPO = "https://packages.termux.dev/apt/termux-main/"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WORK = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "build", "termux")
JNI = os.path.join(ROOT, "app", "src", "main", "jniLibs", "arm64-v8a")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "qemu")
PREFIX = "data/data/com.termux/files/usr"
SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"}
BIOS = ["bios-256k.bin", "bios.bin", "vgabios-stdvga.bin", "vgabios.bin", "vgabios-cirrus.bin",
        "vgabios-vmware.bin", "vgabios-virtio.bin", "vgabios-bochs-display.bin", "kvmvapic.bin",
        "linuxboot_dma.bin", "linuxboot.bin", "multiboot_dma.bin", "pvh.bin",
        "efi-e1000.rom", "efi-rtl8139.rom", "efi-ne2k_pci.rom", "efi-pcnet.rom", "efi-virtio.rom",
        "pxe-e1000.rom", "pxe-rtl8139.rom", "pxe-ne2k_pci.rom", "pxe-pcnet.rom", "pxe-virtio.rom"]


def fetch(url):
    print("GET", url)
    with urllib.request.urlopen(url) as r:
        return r.read()


def parse_index(raw):
    pk = {}
    for block in raw.decode().split("\n\n"):
        d = {}
        for line in block.split("\n"):
            if ": " in line and not line.startswith(" "):
                k, v = line.split(": ", 1)
                d[k] = v
        if "Package" in d:
            pk[d["Package"]] = d
            for p in d.get("Provides", "").split(","):
                p = p.strip().split(" ")[0]
                if p and p not in pk:
                    pk[p] = d
    return pk


def closure(pk, roots):
    seen, stack = [], list(roots)
    while stack:
        p = stack.pop()
        if p in seen or p not in pk:
            continue
        seen.append(p)
        for dep in pk[p].get("Depends", "").split(","):
            dep = dep.strip().split(" ")[0].split("|")[0]
            if dep:
                stack.append(dep)
    return seen


def extract_deb(data, dest):
    # ar archive
    assert data[:8] == b"!<arch>\n"
    off = 8
    while off < len(data):
        name = data[off:off + 16].decode().strip()
        size = int(data[off + 48:off + 58].decode().strip())
        body = data[off + 60:off + 60 + size]
        off += 60 + size + (size & 1)
        if name.startswith("data.tar"):
            mode = "r:xz" if name.endswith("xz") else "r:gz" if name.endswith("gz") else "r:*"
            if name.endswith(".zst"):
                p = subprocess.run(["zstd", "-dc"], input=body, capture_output=True, check=True)
                body, mode = p.stdout, "r:"
            with tarfile.open(fileobj=io.BytesIO(body), mode=mode) as t:
                t.extractall(dest, filter="tar") if hasattr(tarfile, "data_filter") else t.extractall(dest)


def needed(path):
    out = subprocess.run(["readelf", "-d", path], capture_output=True, text=True).stdout
    return re.findall(r"NEEDED.*\[(.*)\]", out)


def new_name(soname):
    # libz.so.1 -> libz1.so ; libgio-2.0.so.0 -> libgio-2.00.so ; libfoo.so -> libfoo.so
    m = re.match(r"^(lib.+?)\.so((?:\.[0-9A-Za-z_]+)*)$", soname)
    base, ver = m.group(1), m.group(2).replace(".", "")
    return base + ver + ".so"


def main():
    os.makedirs(WORK, exist_ok=True)
    pk = parse_index(gzip.decompress(fetch(REPO + "dists/stable/main/binary-aarch64/Packages.gz")))
    pkgs = [p for p in closure(pk, ["qemu-system-x86-64-headless", "qemu-system-aarch64-headless", "qemu-common"])
            if p not in ("python",)]
    rootdir = os.path.join(WORK, "root")
    debs = os.path.join(WORK, "debs")
    os.makedirs(debs, exist_ok=True)
    for p in pkgs:
        fn = pk[p]["Filename"]
        local = os.path.join(debs, os.path.basename(fn))
        if not os.path.exists(local):
            open(local, "wb").write(fetch(REPO + fn))
        extract_deb(open(local, "rb").read(), rootdir)

    usr = os.path.join(rootdir, PREFIX)
    libdir = os.path.join(usr, "lib")
    qemu = os.path.join(usr, "bin", "qemu-system-x86_64")
    qemu_arm = os.path.join(usr, "bin", "qemu-system-aarch64")
    libs, stack = {}, needed(qemu) + needed(qemu_arm)
    while stack:
        s = stack.pop()
        if s in libs or s in SYSTEM_LIBS:
            continue
        f = os.path.join(libdir, s)
        if not os.path.exists(f):
            raise SystemExit("missing library " + s)
        libs[s] = os.path.realpath(f)
        stack += needed(f)

    renames = {s: new_name(s) for s in libs}
    assert len(set(renames.values())) == len(renames), "rename collision"

    shutil.rmtree(JNI, ignore_errors=True)
    os.makedirs(JNI)
    items = [(qemu, "libqemu_x86_64.so"), (qemu_arm, "libqemu_aarch64.so")] + [(libs[s], renames[s]) for s in libs]
    for src, dst in items:
        data = bytearray(open(src, "rb").read())
        for old, new in renames.items():
            if old == new:
                continue
            o, n = b"\0" + old.encode() + b"\0", b"\0" + new.encode() + b"\0"
            n = n + b"\0" * (len(o) - len(n))
            data = bytearray(bytes(data).replace(o, n))
        # libslirp ищет DNS в resolv.conf Termux — направляем в files/ нашего приложения
        o = b"/data/data/com.termux/files/usr/etc/resolv.conf\0"
        n = b"/data/data/app.clmulator/files/resolv.conf\0"
        data = bytearray(bytes(data).replace(o, n + b"\0" * (len(o) - len(n))))
        open(os.path.join(JNI, dst), "wb").write(data)
        print("  ", dst, len(data))

    shutil.rmtree(ASSETS, ignore_errors=True)
    os.makedirs(ASSETS)
    share = os.path.join(usr, "share", "qemu")
    for b in BIOS:
        if os.path.exists(os.path.join(share, b)):
            shutil.copy(os.path.join(share, b), ASSETS)
    shutil.copytree(os.path.join(share, "keymaps"), os.path.join(ASSETS, "keymaps"))
    ver = pk["qemu-system-x86-64-headless"]["Version"]
    open(os.path.join(ASSETS, "VERSION"), "w").write(ver + "\n")
    print("done, qemu", ver, "libs:", len(libs))


if __name__ == "__main__":
    main()
