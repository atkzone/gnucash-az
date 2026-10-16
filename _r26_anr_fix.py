"""R26 小修：SearchBulkWriter.update 内层块缩进（+4）+ breathe() 注释措辞。"""
import io
import os

ROOT = os.path.dirname(os.path.abspath(__file__))
rel = "app/src/main/java/org/gnucash/android/ui/search/SearchBulkWriter.kt"
path = os.path.join(ROOT, rel)
raw = io.open(path, encoding="utf-8", newline="").read()
assert "\r\n" not in raw, "expected LF"
lines = raw.split("\n")

# 内层 for 体（含 try/val/when/.../addRecord 的收尾括号）需要整体再缩进 4 空格。
start = lines.index("                for (uid in chunk) {", 90) + 1
end = lines.index("                        updated++", start)
moved = 0
for i in range(start, end):
    if lines[i].strip():
        lines[i] = "    " + lines[i]
        moved += 1
print("reindented lines:", start + 1, "..", end, "moved=", moved)

text = "\n".join(lines)

old_comment = """        } catch (e: InterruptedException) {
            // Cancelled mid-batch: chunks already committed stay committed, so stop here
            // and leave the flag set for the caller.
            Thread.currentThread().interrupt()
        }"""
new_comment = """        } catch (e: InterruptedException) {
            // The batch is not interruptible mid-row, so this only restores the flag for
            // the caller to notice; chunks already committed stay committed either way.
            Thread.currentThread().interrupt()
        }"""
assert text.count(old_comment) == 1
text = text.replace(old_comment, new_comment, 1)

io.open(path, "w", encoding="utf-8", newline="").write(text)
print("OK", rel, "bytes=", len(text))
