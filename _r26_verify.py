"""R26 出包硬核验收：sha256 + dex/arsc 字面量在场/缺席。

    python _r26_verify.py <apk_path> [<sha256>]

判产物**只认 sha256 / mtime / dex|arsc 内容**，不看体积（K16）。
中文一律在 `resources.arsc`（UTF-8 / UTF-16 混排），dex 里搜中文**必定 0 命中**（K16 同族）。
"""
import hashlib
import os
import sys
import zipfile

apk = sys.argv[1]
expect_sha = sys.argv[2] if len(sys.argv) > 2 else None

raw = open(apk, "rb").read()
sha = hashlib.sha256(raw).hexdigest()
print("APK      :", apk)
print("bytes    :", len(raw), "(%.2f MiB)" % (len(raw) / 1048576.0))
print("sha256   :", sha)
if expect_sha:
    print("sha256 ==  :", "OK" if sha == expect_sha else "MISMATCH (expected %s)" % expect_sha)
print("mtime    :", os.path.getmtime(apk))

zf = zipfile.ZipFile(apk)
names = zf.namelist()
dex = sorted(n for n in names if n.startswith("classes") and n.endswith(".dex"))
blobs = {}
for n in dex:
    blobs[n] = zf.read(n)
arsc = zf.read("resources.arsc")
print("dex      :", dex)

# dex 中按 UTF-8 搜（类名 / 方法名 / 英文常量都在 dex 的 string_ids，UTF-8 编码）
DEX_PRESENT = [
    "SearchReportChart", "SearchReportLoader", "SearchResultReportFragment",
    "SearchBulkWriter", "SearchResultsViewModel",
    "chunked", "breathe", "BATCH_SIZE", "toSearchResultRow",
    "IndexAxisValueFormatter", "PieChart", "BarChart", "LineChart",
]
# 被删的东西**必须缺席**
DEX_ABSENT = ["SearchChartDialogFragment"]

# arsc 中中文/资源键按 UTF-8 搜
ARSC_PRESENT = [
    "图型", "表格", "饼图", "柱状图", "折线图",
    "没有可绘制图表的数据", "图表只统计",
    "按账户（", "按日期（",
    "label_search_report_chart_type", "search_report_chart_types",
    "search_report_chart_by_account", "search_report_chart_by_date",
    "search_report_chart_note",
]
ARSC_ABSENT = [
    "menu_search_chart", "search_chart_title", "search_chart_by_account",
    "search_chart_empty",
]

fail = 0


def hit(blob, s):
    return blob.count(s.encode("utf-8"))


print("\n-- dex: 必须在场 --")
for s in DEX_PRESENT:
    c = sum(hit(b, s) for b in blobs.values())
    print("  %-30s %s" % (s, c if c else "*** MISSING ***"))
    if not c:
        fail += 1

print("-- dex: 必须缺席 --")
for s in DEX_ABSENT:
    c = sum(hit(b, s) for b in blobs.values())
    print("  %-30s %s" % (s, "absent OK" if c == 0 else "*** STILL PRESENT (%d) ***" % c))
    if c:
        fail += 1

print("-- arsc: 必须在场 --")
for s in ARSC_PRESENT:
    c = hit(arsc, s)
    print("  %-34s %s" % (s, c if c else "*** MISSING ***"))
    if not c:
        fail += 1

print("-- arsc: 必须缺席 --")
for s in ARSC_ABSENT:
    c = hit(arsc, s)
    print("  %-34s %s" % (s, "absent OK" if c == 0 else "*** STILL PRESENT (%d) ***" % c))
    if c:
        fail += 1

print("\nVERDICT:", "ALL OK" if fail == 0 else "%d FAILURE(S)" % fail)
sys.exit(1 if fail else 0)
