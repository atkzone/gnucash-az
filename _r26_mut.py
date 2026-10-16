#!/usr/bin/env python3
"""R26 mutation-bite helper.

Applies one named source mutation, or restores it, or verifies the restore is
byte-identical. Line endings are handled adaptively (the repo mixes CRLF and LF),
which is the trap that makes a mutation silently not apply.

Two kinds of case:
  * text cases — one unique `find` fragment replaced by `repl`;
  * create cases (`CREATES`) — a stub file is *created*, which is the only way to
    bite a guard that asserts a file does not exist.

Usage:
  python _r26_mut.py apply   <id>
  python _r26_mut.py restore <id>
  python _r26_mut.py verify  <id>
  python _r26_mut.py list
"""
import hashlib
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
BACKUP_DIR = os.path.join(ROOT, "_r26_mut_backup")

WRITER = "app/src/main/java/org/gnucash/android/ui/search/SearchBulkWriter.kt"
VIEW_MODEL = "app/src/main/java/org/gnucash/android/ui/search/SearchResultsViewModel.kt"
CHART = "app/src/main/java/org/gnucash/android/ui/report/search/SearchReportChart.kt"
REPORT_LOADER = "app/src/main/java/org/gnucash/android/ui/report/search/SearchReportLoader.kt"
REPORT = "app/src/main/java/org/gnucash/android/ui/report/search/SearchReport.kt"
REPORT_FRAGMENT = (
    "app/src/main/java/org/gnucash/android/ui/report/search/SearchResultReportFragment.kt"
)
SEARCH_FRAGMENT = "app/src/main/java/org/gnucash/android/ui/search/SearchResultsFragment.kt"

# id -> (relative path, unique find fragment (LF), replacement fragment (LF))
CASES = {
    # --- 写路径不再分块：整批一个事务，写锁被整批持有（ANR 的原样回退）
    "m01_bulk_update_one_transaction_for_the_whole_batch": (
        WRITER,
        "        var updated = 0\n        for (chunk in uids.chunked(BATCH_SIZE)) {\n",
        "        var updated = 0\n        for (chunk in listOf(uids)) {\n",
    ),
    # --- 删除路径不再在块间让出写锁
    "m02_bulk_delete_never_breathes": (
        WRITER,
        "            } finally {\n                transactionsDbAdapter.endTransaction()\n"
        "            }\n            breathe()\n        }\n        return deleted\n",
        "            } finally {\n                transactionsDbAdapter.endTransaction()\n"
        "            }\n        }\n        return deleted\n",
    ),
    # --- 逐行 DB 查询又回到主线程
    "m03_summary_rows_built_on_the_main_thread": (
        VIEW_MODEL,
        "            val summaryRows = transactions.map { it.toSearchResultRow() }\n\n"
        "            withContext(Dispatchers.Main) {\n"
        "                _summary.value = SearchSummary.of(summaryRows)\n",
        "            withContext(Dispatchers.Main) {\n"
        "                _summary.value = SearchSummary.of(\n"
        "                    transactions.map { it.toSearchResultRow() }\n"
        "                )\n",
    ),
    # --- 折线图不再按时间排（读起来像随机锯齿）
    "m04_by_date_sorted_by_amount_instead_of_time": (
        CHART,
        "        return amounts.keys.sorted().map { day ->",
        "        return amounts.keys.sortedByDescending { it }.map { day ->",
    ),
    # --- 按日期分桶时忽略传入时区
    "m05_by_date_ignores_the_zone": (
        CHART,
        "            val day = LocalDate(row.dateMillis, dayZone)",
        "            val day = LocalDate(row.dateMillis)",
    ),
    # --- 账户桶排序反过来（最大的账户不再排第一）
    "m06_by_account_sorted_ascending": (
        CHART,
        "                compareByDescending<SearchChartBucket> { it.amount.toBigDecimal().abs() }",
        "                compareBy<SearchChartBucket> { it.amount.toBigDecimal().abs() }",
    ),
    # --- 账户桶丢掉了行数（饼图/柱状图看不出桶里有几笔）
    "m07_by_account_loses_the_count": (
        CHART,
        "            counts[key] = (counts[key] ?: 0) + 1",
        "            counts[key] = 1",
    ),
    # --- 图表把多个币种混在一起画（¥ + $ 的无意义数字）
    "m08_chart_folds_every_currency_together": (
        REPORT_LOADER,
        "        val chartedRows = rows.filter { it.amount.commodity.currencyCode == currency }",
        "        val chartedRows = rows",
    ),
    # --- 图表挑中的是「最小的」币种（用户搜到的钱全被画成空白）
    "m09_chart_picks_the_smallest_currency": (
        REPORT_LOADER,
        "        return magnitudes.maxByOrNull { it.value }?.key",
        "        return magnitudes.minByOrNull { it.value }?.key",
    ),
    # --- hasChart 恒真（空报表也会去画图）
    "m10_has_chart_always_true": (
        REPORT,
        "        get() = chartCurrencyCode != null && "
        "(byAccount.isNotEmpty() || byDate.isNotEmpty())",
        "        get() = true",
    ),
    # --- 默认图型不再是表格
    "m11_default_chart_type_is_pie": (
        REPORT_FRAGMENT,
        "    private var chartType: Int = CHART_TYPE_TABLE",
        "    private var chartType: Int = CHART_TYPE_PIE",
    ),
    # --- 资源数组的顺序与常量顺序脱钩（下拉选「饼图」出来柱状图）
    "m12_chart_array_order_swapped": (
        "app/src/main/res/values-zh-rCN/strings.xml",
        "        <item>表格</item>\n        <item>饼图</item>\n        <item>柱状图</item>\n",
        "        <item>表格</item>\n        <item>柱状图</item>\n        <item>饼图</item>\n",
    ),
    # --- 折线图改画账户（时间轴没了）
    "m13_line_chart_draws_accounts": (
        REPORT_FRAGMENT,
        "        val buckets = if (byDate) data.byDate else data.byAccount",
        "        val buckets = data.byAccount",
    ),
    # --- 搜索结果页的「图表」菜单项又回来了（该弹窗已被删）
    "m15_chart_menu_entry_came_back": (
        "app/src/main/res/menu/search_results_menu.xml",
        '    <item\n        android:id="@+id/menu_report_search_results"\n',
        '    <item\n        android:id="@+id/menu_search_chart"\n'
        '        android:title="@string/search_summary_title"\n'
        '        app:showAsAction="never" />\n\n'
        '    <item\n        android:id="@+id/menu_report_search_results"\n',
    ),
    # --- 单块大到等于整批（分块形同虚设）
    "m16_batch_size_unbounded": (
        WRITER,
        "        const val BATCH_SIZE = 50",
        "        const val BATCH_SIZE = 50000",
    ),
    # --- 分块把最后一块丢掉（跨块边界漏写）
    "m17_chunk_drops_the_tail": (
        WRITER,
        "        var deleted = 0\n        for (chunk in uids.chunked(BATCH_SIZE)) {\n",
        "        var deleted = 0\n        for (chunk in uids.chunked(BATCH_SIZE).dropLast(1)) {\n",
    ),
}

# id -> relative path of a file whose *existence* is the mutation.
CREATES = {
    "m14_chart_dialog_file_came_back": (
        "app/src/main/java/org/gnucash/android/ui/search/SearchChartDialogFragment.kt"
    ),
}

STUB = "// R26 mutation stub: the 图表 dialog must not exist any more.\n"


def _paths(mid):
    rel, find, repl = CASES[mid]
    src = os.path.join(ROOT, rel.replace("/", os.sep))
    flat = rel.replace("/", "__")
    bak = os.path.join(BACKUP_DIR, mid + "__" + flat)
    return src, find, repl, bak


def _create_target(mid):
    rel = CREATES[mid]
    src = os.path.join(ROOT, rel.replace("/", os.sep))
    flat = rel.replace("/", "__")
    bak = os.path.join(BACKUP_DIR, mid + "__" + flat + ".absent")
    return src, bak


def sha(path):
    with open(path, "rb") as fh:
        return hashlib.sha256(fh.read()).hexdigest()


def apply(mid):
    if mid in CREATES:
        src, bak = _create_target(mid)
        if os.path.isfile(src):
            raise SystemExit("mutation target already exists: " + src)
        os.makedirs(BACKUP_DIR, exist_ok=True)
        with open(bak, "w", encoding="utf-8") as fh:
            fh.write("absent\n")
        with open(src, "w", encoding="utf-8", newline="") as fh:
            fh.write(STUB)
        print("APPLIED %s (created %s)" % (mid, os.path.basename(src)))
        return

    src, find, repl, bak = _paths(mid)
    with open(src, "r", encoding="utf-8", newline="") as fh:
        raw = fh.read()
    if not os.path.isfile(bak):
        os.makedirs(BACKUP_DIR, exist_ok=True)
        shutil.copy2(src, bak)
    eol = "\r\n" if "\r\n" in raw else "\n"
    text = raw.replace("\r\n", "\n")
    occurrences = text.count(find)
    if occurrences != 1:
        raise SystemExit(
            "MUTATION ANCHOR NOT UNIQUE (%d) in %s for %s" % (occurrences, src, mid)
        )
    mutated = text.replace(find, repl, 1).replace("\n", eol)
    with open(src, "w", encoding="utf-8", newline="") as fh:
        fh.write(mutated)
    print("APPLIED %s (%s, eol=%s)" % (mid, os.path.basename(src), repr(eol)))


def restore(mid):
    if mid in CREATES:
        src, bak = _create_target(mid)
        if os.path.isfile(src):
            os.remove(src)
        print("RESTORED %s (removed %s)" % (mid, os.path.basename(src)))
        return
    src, _find, _repl, bak = _paths(mid)
    if not os.path.isfile(bak):
        raise SystemExit("no backup for %s" % mid)
    shutil.copy2(bak, src)
    print("RESTORED %s" % mid)


def verify(mid):
    if mid in CREATES:
        src, bak = _create_target(mid)
        absent = not os.path.isfile(src)
        print("VERIFY %s: %s" % (mid, "absent (as required)" if absent else "STILL PRESENT"))
        return 0 if absent else 1
    src, _find, _repl, bak = _paths(mid)
    if not os.path.isfile(bak):
        print("VERIFY %s: NO BACKUP" % mid)
        return 1
    same = sha(src) == sha(bak)
    print("VERIFY %s: %s" % (mid, "byte-identical" if same else "DIFFERS"))
    return 0 if same else 1


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    cmd = sys.argv[1]
    if cmd == "list":
        for mid in CASES:
            print(mid)
        for mid in CREATES:
            print(mid)
        return
    mid = sys.argv[2]
    if mid not in CASES and mid not in CREATES:
        raise SystemExit("unknown id %s" % mid)
    if cmd == "apply":
        apply(mid)
    elif cmd == "restore":
        restore(mid)
    elif cmd == "verify":
        raise SystemExit(verify(mid))
    else:
        raise SystemExit("unknown command %s" % cmd)


if __name__ == "__main__":
    main()
