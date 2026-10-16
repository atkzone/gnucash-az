"""R25 通用补丁工具：逐处精确替换，保留原文件换行符（CRLF / LF）。

工程里历史 Kotlin/XML 是 CRLF、R24 起新文件是 LF，混着写就会把整个文件
重排（K22 坑 1）。读时归一成 \n，写回时按原文件还原。
"""
import io


def read(path):
    raw = io.open(path, encoding="utf-8", newline="").read()
    crlf = raw.count("\r\n")
    lf = raw.count("\n") - crlf
    if crlf and lf:
        raise SystemExit("mixed line endings in %s (crlf=%d lf=%d)" % (path, crlf, lf))
    return raw.replace("\r\n", "\n"), crlf > 0


def patch(path, edits):
    text, crlf = read(path)
    original = text
    for index, (old, new) in enumerate(edits, start=1):
        old = old.replace("\r\n", "\n")
        new = new.replace("\r\n", "\n")
        count = text.count(old)
        if count != 1:
            raise SystemExit("EDIT %d in %s: expected 1 match, found %d" % (index, path, count))
        text = text.replace(old, new, 1)
    if text == original:
        raise SystemExit("no change produced for " + path)
    out = text.replace("\n", "\r\n") if crlf else text
    io.open(path, "w", encoding="utf-8", newline="").write(out)
    print("OK " + path + " edits=" + str(len(edits)) + " crlf=" + str(crlf)
          + " bytes=" + str(len(out)))
