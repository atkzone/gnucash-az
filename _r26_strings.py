"""R26 需求③（续）：新字符串 / 字符串数组（en + zh），并修正 SearchReport 的过时说明。"""
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


EN_ANCHOR = '    <string name="label_search_report_amount">Amount</string>\n'
EN_NEW = EN_ANCHOR + """    <!-- 搜索结果报表的图型：表格是默认（合计列表），其余三种是图。顺序即下拉顺序。 -->
    <string name="label_search_report_chart_type">Chart type</string>
    <string-array name="search_report_chart_types">
        <item>Table</item>
        <item>Pie</item>
        <item>Bar</item>
        <item>Line</item>
    </string-array>
    <string name="search_report_chart_by_account">By account (%1$s)</string>
    <string name="search_report_chart_by_date">By date (%1$s)</string>
    <string name="search_report_chart_empty">There is nothing to chart</string>
    <string name="search_report_chart_note">Charts draw %1$s only; %2$s are left to the table</string>
"""

ZH_ANCHOR = '    <string name="label_search_report_amount">金额</string>\n'
ZH_NEW = ZH_ANCHOR + """    <!-- 搜索结果报表的图型：表格是默认（合计列表），其余三种是图。顺序即下拉顺序。 -->
    <string name="label_search_report_chart_type">图型</string>
    <string-array name="search_report_chart_types">
        <item>表格</item>
        <item>饼图</item>
        <item>柱状图</item>
        <item>折线图</item>
    </string-array>
    <string name="search_report_chart_by_account">按账户（%1$s）</string>
    <string name="search_report_chart_by_date">按日期（%1$s）</string>
    <string name="search_report_chart_empty">没有可绘制图表的数据</string>
    <string name="search_report_chart_note">图表只统计 %1$s 的数据；%2$s 见表格</string>
"""

patch("app/src/main/res/values/strings.xml", [(EN_ANCHOR, EN_NEW)])
patch("app/src/main/res/values-zh-rCN/strings.xml", [(ZH_ANCHOR, ZH_NEW)])

# SearchReport 的类注释还写着「这张报表是表格、不是图」——现在两种都能画。
patch("app/src/main/java/org/gnucash/android/ui/report/search/SearchReport.kt", [(
    """ * It extends [ChartData] only because every report here is drawn by a
 * `BaseReportFragment<D : ChartData<*>>` (the balance sheet does exactly the same). This
 * report is a table, not a chart, so the inherited data-set machinery stays empty on
 * purpose — [rows] and [totals] are what the screen reads.
""",
    """ * It extends [ChartData] only because every report here is drawn by a
 * `BaseReportFragment<D : ChartData<*>>` (the balance sheet does exactly the same). The
 * inherited data-set machinery stays empty on purpose: this report is drawn either as a
 * table or as one of three charts from the 图型 selector, so [rows], [totals] and the
 * [SearchChartBucket] lists are what the screen reads — not MPAndroidChart's own data
 * sets, which want a shape ([Entry] with an x index) this report would have to invent.
""",
)])
