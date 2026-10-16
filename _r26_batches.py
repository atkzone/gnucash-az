"""R26: 把 113 个单测类切成前台可跑的分批文件（每批 12 个）。

    python _r26_batches.py
"""
import io

cls = [l.strip() for l in io.open("_r26_classlist.txt", encoding="utf-8") if l.strip()]
SIZE = 12
files = []
for i in range(0, len(cls), SIZE):
    name = "_r26_batch_%02d.txt" % (i // SIZE + 1)
    io.open(name, "w", encoding="utf-8", newline="\n").write("\n".join(cls[i:i + SIZE]) + "\n")
    files.append(name)
print("batches:", len(files), "classes:", len(cls))
for f in files:
    print(" ", f)
