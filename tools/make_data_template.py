#!/usr/bin/env python3
"""Делает шаблон пустого диска данных (ext2, 4 ГБ) в компактном виде:
только ненулевые блоки. Приложение разворачивает его в разрежённый файл data.img,
который Android-x86 монтирует как /data (параметр DATA=sda)."""
import gzip, os, struct, subprocess, tempfile
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "data-ext2.clm")
SIZE = 4 << 30
BS = 4096
with tempfile.TemporaryDirectory() as d:
    img = os.path.join(d, "data.img")
    with open(img, "wb") as f:
        f.truncate(SIZE)
    subprocess.run(["mke2fs", "-q", "-F", "-t", "ext2", "-b", str(BS), "-I", "128", "-m", "0", "-L", "data", img], check=True)
    blocks = []
    zero = bytes(BS)
    with open(img, "rb") as f:
        i = 0
        while True:
            b = f.read(BS)
            if not b:
                break
            if b != zero:
                blocks.append((i, b))
            i += 1
    with gzip.open(OUT, "wb", 9) as o:
        o.write(b"CLMSPRS1" + struct.pack(">QII", SIZE, BS, len(blocks)))
        for i, b in blocks:
            o.write(struct.pack(">I", i) + b)
print(OUT, os.path.getsize(OUT), "blocks:", len(blocks))
