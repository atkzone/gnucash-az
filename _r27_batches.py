"""R27: 把 115 个单测类（= R26 的 113 + 本轮新增的 ConflictResolutionTest、ConflictResolutionStoreTest）
切成前台/短跑可用的分批文件（每批 12 个）。

    python _r27_batches.py
"""
import io

base = [l.strip() for l in io.open("_r26_classlist.txt", encoding="utf-8") if l.strip()]
NEW = [
    "org.gnucash.android.test.unit.sync.ConflictResolutionTest",
    "org.gnucash.android.ui.settings.ConflictResolutionStoreTest",
]
cls = base + [c for c in NEW if c not in base]
SIZE = 12
files = []
for i in range(0, len(cls), SIZE):
    name = "_r27_batch_%02d.txt" % (i // SIZE + 1)
    io.open(name, "w", encoding="utf-8", newline="\n").write("\n".join(cls[i:i + SIZE]) + "\n")
    files.append(name)
io.open("_r27_classlist.txt", "w", encoding="utf-8", newline="\n").write("\n".join(cls) + "\n")
print("batches:", len(files), "classes:", len(cls))
for f in files:
    print(" ", f, "(%d)" % sum(1 for _ in io.open(f, encoding="utf-8") if _.strip()))
