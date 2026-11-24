#!/usr/bin/env python3
"""
Собирает тестовые прошивки CLmulator ("CLM editions").

Тестовая прошивка = официальный ISO Android-x86 (скачивается приложением с SourceForge)
                   + наш initrd с оверлеем (testroms/overlay), который при загрузке:
                     * подменяет build.prop (модель, бренд, номер сборки),
                     * даёт root (своё su для Android < 4.3, на 4.4 — встроенный Superuser),
                     * кладёт свою бутанимацию, обои и приложение CLM Center.

Результат: app/src/main/assets/testroms/<id>/initrd.img

Требует: python3 + Pillow, xorriso, cpio, gzip, gcc (-m32), Android SDK build-tools (aapt2, d8, apksigner).
"""
import io, json, os, shutil, subprocess, sys, urllib.request, zipfile
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
T = os.path.join(ROOT, "testroms")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "testroms")
CACHE = os.environ.get("CLM_CACHE", os.path.join(ROOT, "build", "testroms-cache"))
SDK = os.environ.get("ANDROID_HOME", "/opt/android-sdk")
BT = os.path.join(SDK, "build-tools", "35.0.0")
ANDROID_JAR = os.path.join(SDK, "platforms", "android-35", "android.jar")
SF = "https://downloads.sourceforge.net/project/android-x86/"
FONT_B = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"

EDITIONS = [
    dict(id="clm-donut", iso="Release 1.6-r2/android-x86-1.6-r2.iso", title="CLM Donut Root",
         version="1.6", codename="Donut", c1=(124, 179, 66), c2=(46, 125, 50), su=1, bootanim=False,
         model="CLM One", device="clm_donut", display="CLM-Donut-1.6-TEST01 (rooted)"),
    dict(id="clm-froyo", iso="Release 2.2/android-x86-2.2-generic.iso", title="CLM Froyo Plus",
         version="2.2", codename="Froyo", c1=(255, 167, 38), c2=(230, 81, 0), su=1, bootanim=True,
         model="CLM Froyo Plus", device="clm_froyo", display="CLM-Froyo-2.2-TEST02 (rooted)"),
    dict(id="clm-kitkat", iso="Release 4.4/android-x86-4.4-r5.iso", title="CLM KitKat Lite",
         version="4.4", codename="KitKat", c1=(66, 165, 245), c2=(40, 53, 147), su=0, bootanim=True,
         model="CLM KitKat Lite", device="clm_kitkat", display="CLM-KitKat-4.4-TEST03 (rooted)"),
]


def run(*cmd, **kw):
    print("+", " ".join(cmd))
    subprocess.run(cmd, check=True, **kw)


def font(path, size):
    return ImageFont.truetype(path, size)


def gradient(w, h, c1, c2):
    im = Image.new("RGB", (w, h))
    px = im.load()
    for y in range(h):
        for x in range(w):
            t = (x / w * 0.6 + y / h * 0.4)
            px[x, y] = tuple(int(c1[i] * (1 - t) + c2[i] * t) for i in range(3))
    return im


def make_wallpaper(e, path):
    w, h = 1600, 1000
    im = gradient(w // 4, h // 4, e["c1"], e["c2"]).resize((w, h), Image.BICUBIC)
    d = ImageDraw.Draw(im, "RGBA")
    for i, r in enumerate((620, 470, 330)):
        d.ellipse((w * 0.72 - r, h * 0.3 - r, w * 0.72 + r, h * 0.3 + r), fill=(255, 255, 255, 14 + i * 6))
    d.text((w // 2, h // 2 - 30), e["version"], font=font(FONT_B, 190), fill=(255, 255, 255, 235), anchor="mm")
    d.text((w // 2, h // 2 + 100), "CLmulator • " + e["codename"], font=font(FONT, 52), fill=(255, 255, 255, 200), anchor="mm")
    im.save(path, quality=90)


def make_bootanim(e, path):
    w, h, n = 480, 200, 30
    frames = []
    for i in range(n):
        im = Image.new("RGB", (w, h), (0, 0, 0))
        d = ImageDraw.Draw(im)
        text = "CLmulator"
        f = font(FONT_B, 64)
        d.text((w // 2, h // 2 - 18), text, font=f, fill=(60, 60, 60), anchor="mm")
        # light sweep: draw the text in color through a moving mask
        mask = Image.new("L", (w, h), 0)
        md = ImageDraw.Draw(mask)
        cx = int(-120 + (w + 240) * i / (n - 1))
        md.polygon([(cx - 60, 0), (cx + 40, 0), (cx + 100, h), (cx, h)], fill=255)
        mask = mask.filter(ImageFilter.GaussianBlur(18))
        col = Image.new("RGB", (w, h), e["c1"])
        tmask = Image.new("L", (w, h), 0)
        ImageDraw.Draw(tmask).text((w // 2, h // 2 - 18), text, font=f, fill=255, anchor="mm")
        from PIL import ImageChops
        im.paste(col, (0, 0), ImageChops.multiply(mask, tmask))
        d.text((w // 2, h // 2 + 48), "Android " + e["version"] + " " + e["codename"] + " • test build",
               font=font(FONT, 20), fill=e["c1"], anchor="mm")
        b = io.BytesIO()
        im.save(b, "PNG")
        frames.append(b.getvalue())
    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as z:
        z.writestr("desc.txt", "%d %d 15\np 0 0 part0\n" % (w, h))
        for i, fr in enumerate(frames):
            z.writestr("part0/%03d.png" % i, fr)


def make_icon(path, size):
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    pad = size // 12
    d.rounded_rectangle((pad, pad, size - pad, size - pad), radius=size // 5, fill=(28, 37, 41, 255), outline=(139, 195, 74, 255), width=max(2, size // 24))
    d.text((size // 2, size // 2), "#", font=font(FONT_B, int(size * 0.55)), fill=(139, 195, 74, 255), anchor="mm")
    im.save(path)


def build_center_apk(work):
    src = os.path.join(T, "clmcenter")
    b = os.path.join(work, "clmcenter")
    shutil.rmtree(b, ignore_errors=True)
    for dpi, size in (("drawable-mdpi", 48), ("drawable-hdpi", 72), ("drawable", 48)):
        os.makedirs(os.path.join(b, "res", dpi))
        make_icon(os.path.join(b, "res", dpi, "icon.png"), size)
    # Классический aapt: строки ресурсов в UTF-16 (Android 1.6 не понимает UTF-8 string pool от aapt2)
    run(os.path.join(BT, "aapt"), "package", "-f", "-M", os.path.join(src, "AndroidManifest.xml"),
        "-S", os.path.join(b, "res"), "-I", ANDROID_JAR, "--min-sdk-version", "4", "--target-sdk-version", "4",
        "-F", os.path.join(b, "base.apk"))
    javas = []
    for dp, _, fs in os.walk(os.path.join(src, "src")):
        javas += [os.path.join(dp, f) for f in fs if f.endswith(".java")]
    run("javac", "-encoding", "UTF-8", "--release", "8", "-cp", ANDROID_JAR, "-d", os.path.join(b, "classes"), *javas)
    classes = []
    for dp, _, fs in os.walk(os.path.join(b, "classes")):
        classes += [os.path.join(dp, f) for f in fs if f.endswith(".class")]
    run(os.path.join(BT, "d8"), "--release", "--min-api", "4", "--lib", ANDROID_JAR, "--output", b, *classes)
    with zipfile.ZipFile(os.path.join(b, "base.apk"), "a") as z:
        z.write(os.path.join(b, "classes.dex"), "classes.dex")
    run(os.path.join(BT, "zipalign"), "-f", "4", os.path.join(b, "base.apk"), os.path.join(b, "aligned.apk"))
    ks = os.path.join(CACHE, "clmcenter-test.jks")
    if not os.path.exists(ks):
        run("keytool", "-genkeypair", "-keystore", ks, "-storepass", "clmtest", "-keypass", "clmtest", "-alias", "clm",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000", "-dname", "CN=CLmulator Test Build")
    out = os.path.join(b, "CLMCenter.apk")
    run(os.path.join(BT, "apksigner"), "sign", "--ks", ks, "--ks-pass", "pass:clmtest", "--min-sdk-version", "4",
        "--v1-signing-enabled", "true", "--v2-signing-enabled", "false", "--v3-signing-enabled", "false",
        "--out", out, os.path.join(b, "aligned.apk"))
    return out


def iso_path(e):
    p = os.path.join(CACHE, os.path.basename(e["iso"]))
    if not os.path.exists(p):
        url = SF + urllib.request.quote(e["iso"])
        print("downloading", url)
        run("curl", "-sfL", "-o", p + ".part", url)
        os.rename(p + ".part", p)
    return p


def build_initrd(e, apk, work):
    d = os.path.join(work, e["id"])
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)
    iso = iso_path(e)
    run("xorriso", "-osirrox", "on", "-indev", iso, "-extract", "/initrd.img", os.path.join(d, "orig.img"),
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    rd = os.path.join(d, "rd")
    os.makedirs(rd)
    subprocess.run("zcat ../orig.img | cpio -idm --quiet", shell=True, cwd=rd, check=True)
    init = open(os.path.join(rd, "init")).read()
    assert "\nsetup_tslib\n" in init, "hook point not found in " + e["id"]
    init = init.replace("\nsetup_tslib\n", "\nsetup_tslib\n[ -e /clm/hook.sh ] && . /clm/hook.sh\n", 1)
    open(os.path.join(rd, "init"), "w").write(init)
    clm = os.path.join(rd, "clm")
    os.makedirs(clm)
    shutil.copy(os.path.join(T, "overlay", "hook.sh"), clm)
    shutil.copy(os.path.join(T, "overlay", "tweak.sh"), clm)
    shutil.copy(os.path.join(work, "su"), os.path.join(clm, "su"))
    shutil.copy(apk, os.path.join(clm, "CLMCenter.apk"))
    make_wallpaper(e, os.path.join(clm, "wallpaper.jpg"))
    if e["bootanim"]:
        make_bootanim(e, os.path.join(clm, "bootanimation.zip"))
    open(os.path.join(clm, "edition.conf"), "w").write(
        'CLM_ID=%s\nCLM_SU=%d\nCLM_TITLE="%s"\n' % (e["id"], e["su"], e["title"]))
    open(os.path.join(clm, "props"), "w").write("\n".join([
        "# --- CLmulator test build ---",
        "ro.product.model=" + e["model"],
        "ro.product.brand=CLmulator",
        "ro.product.manufacturer=CLmulator",
        "ro.product.device=" + e["device"],
        "ro.build.display.id=" + e["display"],
        "ro.build.host=clmulator",
        "ro.build.user=clm",
        "ro.clm.edition=" + e["title"],
        "ro.clm.version=1",
        "ro.product.locale.language=ru",
        "ro.product.locale.region=RU",
        "persist.sys.language=ru",
        "persist.sys.country=RU",
        ""]))
    for f in os.listdir(clm):
        os.chmod(os.path.join(clm, f), 0o755 if f.endswith(".sh") or f == "su" else 0o644)
    os.makedirs(os.path.join(OUT, e["id"]), exist_ok=True)
    out = os.path.join(OUT, e["id"], "initrd.img")
    subprocess.run("find . | cpio -o -H newc --quiet | gzip -9 > " + out, shell=True, cwd=rd, check=True)
    meta = {k: e[k] for k in ("id", "iso", "title", "version", "codename", "model", "display")}
    json.dump(meta, open(os.path.join(OUT, e["id"], "edition.json"), "w"), ensure_ascii=False, indent=1)
    print("built", out, os.path.getsize(out))


def main():
    os.makedirs(CACHE, exist_ok=True)
    work = os.path.join(CACHE, "work")
    os.makedirs(work, exist_ok=True)
    run("gcc", "-m32", "-Os", "-static", "-nostdlib", "-ffreestanding", "-fno-pie", "-no-pie", "-fno-stack-protector",
        "-fno-asynchronous-unwind-tables", "-o", os.path.join(work, "su"), os.path.join(T, "src", "su.c"))
    run("strip", os.path.join(work, "su"))
    apk = build_center_apk(work)
    only = sys.argv[1:]
    for e in EDITIONS:
        if not only or e["id"] in only:
            build_initrd(e, apk, work)


if __name__ == "__main__":
    main()
