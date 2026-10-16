"""R26 需求①：删除搜索结果页的「图表」（菜单项 + 弹窗 + 字符串）。

文件混用 LF/CRLF ⇒ 读时归一、写时按原文件还原（K22 坑）。
用法：python _r26_del_chart.py
"""
import io
import os

ROOT = os.path.dirname(os.path.abspath(__file__))


def patch(rel, edits):
    path = os.path.join(ROOT, rel)
    raw = io.open(path, encoding="utf-8", newline="").read()
    crlf = raw.count("\r\n")
    lf = raw.count("\n") - crlf
    if crlf and lf:
        raise SystemExit("mixed line endings in " + rel)
    text = raw.replace("\r\n", "\n")
    for old, new in edits:
        n = text.count(old)
        if n != 1:
            raise SystemExit("anchor count %d for %r in %s" % (n, old[:70], rel))
        text = text.replace(old, new, 1)
    out = text.replace("\n", "\r\n") if crlf else text
    io.open(path, "w", encoding="utf-8", newline="").write(out)
    print("OK", rel, "edits=", len(edits), "crlf=", crlf, "bytes=", len(out))


# 1) 菜单项：删掉「图表」
patch("app/src/main/res/menu/search_results_menu.xml", [(
    """    <item
        android:id="@+id/menu_search_chart"
        android:title="@string/search_chart_title"
        app:showAsAction="never" />

""",
    "",
)])

# 2) 片段里那条菜单分支
patch("app/src/main/java/org/gnucash/android/ui/search/SearchResultsFragment.kt", [(
    """            R.id.menu_search_chart -> {
                SearchChartDialogFragment().show(childFragmentManager, SearchChartDialogFragment.TAG)
                true
            }

""",
    "",
)])

# 3) 英文串
patch("app/src/main/res/values/strings.xml", [
    ('    <string name="search_chart_title">Chart</string>\n', ""),
    ('    <string name="search_chart_by_account">Amount by account</string>\n', ""),
    ('    <string name="search_chart_empty">There is nothing to chart</string>\n', ""),
])

# 4) 中文串
patch("app/src/main/res/values-zh-rCN/strings.xml", [
    ('    <string name="search_chart_title">图表</string>\n', ""),
    ('    <string name="search_chart_by_account">按账户金额分布</string>\n', ""),
    ('    <string name="search_chart_empty">没有可绘制图表的数据</string>\n', ""),
])

# 5) 弹窗本体
target = os.path.join(
    ROOT, "app/src/main/java/org/gnucash/android/ui/search/SearchChartDialogFragment.kt"
)
if os.path.isfile(target):
    os.remove(target)
    print("REMOVED", os.path.relpath(target, ROOT))
else:
    print("ALREADY GONE", os.path.relpath(target, ROOT))
