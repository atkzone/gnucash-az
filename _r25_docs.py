"""R25 文档回填：规划书 → v1.25 + §14.1 索引 + E13 基线；用法

    python _r25_docs.py <classes> <tests> <apk_bytes> <apk_sha256>
"""
import io
import sys

DOC = "docs/gnucash-az-开发规划书.md"

classes, tests, apk_bytes, apk_sha = sys.argv[1:5]

STAMP = (
    "；**v1.25 回填 ①g「批量写库单事务 + 扩展视图单选化 + 一级菜单 + 日期手工填写 + 搜索结果接报表」（第二十五轮）**"
    "（① **批量修改保存后卡住 / 数据没写入的真根因**（用户报的第二例）：第二十四轮把批量写挪到 IO 线程只解掉了「界面死锁」，"
    "剩下的是**每笔一次事务提交** —— `addRecord` / `deleteRecord` **各自开事务并提交**，N 笔 = N 次 fsync，"
    "屏幕能点但数据迟迟不落盘，观感与「挂住」完全一致。修复 = `SearchBulkWriter.delete` / `update` **整批包一个事务**"
    "（`beginTransaction` → `setTransactionSuccessful` → `endTransaction`，**但保留逐行 `try/catch`**：一行失败仍跳过、不整批回滚 —— "
    "「一次提交」与「失败策略」是两件事）；并补两处与「卡住」同族的静默丢数据：**`EXTRA_UIDS` 随对话框结果回传**"
    "（界面**先关 CAB 再提交**，而关 CAB 会清空适配器的选择 ⇒ 回落到 `selectedUids()` 会拿到**空集** ⇒ 保存看起来「没反应」）"
    "+ 保存前「正在更新 N 笔…」提示；② **扩展视图改单选**：`ExtendedViewStore.select(id?)` 是**唯一写入路径**"
    "（`null` ⇒ `setEnabled(false)`；否则设默认 + 开模式），管理页列表**首行 = 「不使用扩展显示」**、勾选即生效、勾选即开关 —— "
    "删掉「设为默认」这套第二步骤（两处状态最容易漂移）；③ **取消二级菜单**：`transactions_list_actions.xml` 里那层嵌套 `<menu>` 与 "
    "`menu_toggle_extended` 一起删掉，改为**一级菜单项「设置扩展显示」**，「定义显示哪些字段」改由管理页顶部的**字段勾选面板**承担"
    "（`ExtendedViewStore.updateFields(id, fields)`，**空集合被拒** —— 那是紧凑视图本就在做的事）；"
    "④ **修正扩展视图的显示逻辑**：`ExtendedRowContent.hasExtraLines`（`notes` / `number` / `tags` 任一有内容即可；"
    "**刻意忽略 account** —— 账户行由卡片自己的 secondary row 画，算进来会让「只勾账户」的视图撑开一条**可见的空行**）"
    "+ 列表容器可见性改为 `content?.hasExtraLines == true`（容器默认 `GONE`）；紧凑视图与扩展视图**互斥**改走 `select(null)`，"
    "并补上紧凑开关的 **book pref 持久化**（原先只存 `savedInstanceState`，重启即丢）；⑤ **所有日历加「手工填写」**："
    "单选日期全走 `DatePickerDialogFragment` ⇒ 给它加 `BUTTON_NEUTRAL`（**listener 必须传 `null`**，否则 AlertDialog 自带的中性处理"
    "会**先把日历关掉**；`onShow` 时再绑）→ `ManualDateDialogFragment`；⚠️ **报表的「日期范围」是另一个控件**"
    "（`DateRangePickerDialogFragment` + `CalendarPickerView`）⇒ 「所有日期」若只改单选那处就是**半成品**，故另加 "
    "`ManualDateRangeDialogFragment`（两个 `20260129` 字段）+ 纯函数 `ManualDateRange.parse`（负责「结束不早于起始」）；"
    "顺手记下 `ReportsActivity` **实现**了 `DatePickerDialog.OnDateSetListener` 却**从来没有人 new 过那个 Dialog**（死代码）"
    "—— **数入口要按构造点数、不是按 implements 数**；⑥ **`20260129` 控件**：纯核心 `ManualDate`"
    "（`format` / `digitsOf`（过滤非数字 + 截 8 位 ⇒ 粘贴 `2026-01-29` 也得 `20260129`）/ `parse`（**`isLenient = false` 严格**，"
    "`20260230` **拒绝**而不是滚到 3 月）/ `startOfDay`）+ 对话框**输入即归一化**（`normalising` 防回环）+ "
    "**单击聚焦到末尾 / 双击全选**（`GestureDetector`；吞掉触摸意味着输入框不再自己放光标 —— 八位框里这两种手势就是全部交互）；"
    "⚠️ 丢掉前导零的形式（`2026/1/29` ⇒ 7 位）**拒绝而非猜**（`2026119` 是 1/19 还是 11/9 无从判断）；"
    "⑦ **搜索结果接报表**：新增 `ReportType.SEARCH_RESULT` + `SearchResultReportFragment` + 纯核心 `SearchReportLoader`"
    "（**复用 `fetchSearch(where)`** ⇒ 报表与结果列表对「命中」与「金额」的定义必须一致；金额 = **借方分录合计**，与 ①d 同口径；"
    "合计**按币种独立**、绝不相加）+ `ReportsActivity.showSearchResult(context, where)`（查询走 **intent extra** ⇒ "
    "从导航栏直接打开报表仍落在普通总览；activity 已在栈上时走 `onNewIntent`）+ 搜索结果页溢出菜单入口；"
    "⚠️ **枚举顺序契约**：下拉用 `values()[position]` 索引，而 `getReportNames` 会过滤 `NONE`（总是）与 `SEARCH_RESULT`（没搜过时）"
    "⇒ **被过滤的项必须在末尾**，中间开洞会让它下面**每一项**都打开错报表；⚠️ `SearchReport` **必须继承 `ChartData<*>`**"
    "（`BaseReportFragment<D : ChartData<*>>` 的泛型上界，与 `BalanceSheet` 同形；不继承则编译挂在 **fragment 那一行**）；"
    "顺手修 zh 文案 `hint_manual_date_format` 的措辞（「年月日在后」→「依次为年月日」）；"
    "**14 处变异逐处咬合 14 条红**（m01 批量单事务 1 · m02 `select(null)` 不关模式 1 · m03 `updateFields` 收空集 1 · "
    "m04 `hasExtraLines` 恒真 1 · m05 宽松解析 1 · m06 无查询也提供「搜索结果」1 · m07 不过滤 `SEARCH_RESULT` 1 · "
    "m08 空数组不判空 1 · m09 单选日历按钮脱线 1 · m10 合计只留一个币种桶 1 · m11 金额折全部（含贷方）1 · "
    "m12 `select` 忽略实参 1 · m13 范围顺序不校验 1 · m14 范围日历按钮脱线 1），逐处**逐字节还原**"
    "（14 文件 sha256 全部 `byte-identical`）；⚠️ 本轮记下 **K25** 五坑（整批一次提交 / `BaseReportFragment` 的泛型上界 / "
    "报表枚举顺序 / **同一类日期不止一个控件入口** / 源码守卫锚点要照抄真实串），并实测「**咬合跑到一半改源码 ⇒ 整轮结果作废**；"
    "强停按 K24 坑 2 逐处验回读，确有**一处**留在变异态」；全量单测 **{} 类 / {} 例 / 0 失败**（见 E13）；"
    "**本轮含 BY**：debug APK `{} B` / sha256 `{}`，dex 硬核到 `ManualDate` / `ManualDateRange` / `ManualDateDialogFragment` / "
    "`ManualDateRangeDialogFragment` / `SearchReportLoader` / `SearchResultReportFragment` / `MenuFragment` 无关的 "
    "`ExtendedViewStore`（`select` / `updateFields`）与菜单项 `menu_extended_view_settings`，"
    "`resources.arsc`（UTF-8）硬核到 `手工填写` / `不使用扩展显示` / `设置扩展显示` / `搜索结果` / `起始` / `结束` 与资源键 "
    "`btn_manual_date` / `title_manual_date_range` / `menu_report_search_results`）"
).format(classes, tests, apk_bytes, apk_sha)

INDEX_ROWS = """| **①g 批量写库单事务 + 扩展视图单选化 + 一级菜单**（第二十五轮，**已完成**） | `ui/search/SearchBulkWriter.kt`（改；`delete` / `update` **整批一个事务**，逐行 `try/catch` 保留）、`ui/search/BulkEditTransactionsDialogFragment.kt`（改；`EXTRA_UIDS` + `uidsOf`）、`ui/search/SearchResultsFragment.kt`（改；`resultUids ?: selectedUids()`、`bulk_edit_running` 提示）、`res/menu/transactions_list_actions.xml`（改；删嵌套 `<menu>`，改一级 `menu_extended_view_settings`）、`res/menu/extended_view_item_actions.xml`（改；删「设为默认」）、`ui/transaction/view/ExtendedViewStore.kt`（改；`select(id?)` / `updateFields(id, fields)`）、`ui/transaction/view/ExtendedViewsFragment.kt`（改；**单选列表** + 字段勾选面板 + `syncingFields` 防回环）、`ui/transaction/view/ExtendedViewEditFragment.kt`（改；仅改名）、`ui/transaction/view/ExtendedRowContent.kt`（改；`hasExtraLines`）、`ui/transaction/TransactionsListFragment.kt`（改；`select(null)` / `saveCompactView` / `content?.hasExtraLines == true`）、`res/layout/{fragment_extended_views,item_extended_view,dialog_extended_view_edit}.xml`（改；RadioButton + CheckBox 面板） |
| **①g 日期「手工填写」**（第二十五轮，**已完成**；单选 + 范围两个控件入口都接） | `util/ManualDate.kt`（**新**，纯 JVM：`format` / `digitsOf` / `parse`（严格 `yyyyMMdd`）/ `startOfDay`）、`util/ManualDateRange.kt`（**新**，纯 JVM：`parse` 负责「结束不早于起始」）、`ui/util/dialog/ManualDateDialogFragment.kt`（**新**）、`ui/util/dialog/ManualDateRangeDialogFragment.kt`（**新**）、`ui/util/dialog/DatePickerDialogFragment.kt`（改；`BUTTON_NEUTRAL` + `showManualEntry` + `applyManualDate`）、`ui/util/dialog/DateRangePickerDialogFragment.kt`（改；范围日历同款入口）、`res/layout/{dialog_manual_date,dialog_manual_date_range}.xml`（**新**） |
| **①g 搜索结果接报表**（第二十五轮，**已完成**） | `ui/report/search/SearchReport.kt`（**新**；`SearchReportRow` / `SearchReport : ChartData<DataSet<Entry>>`）、`ui/report/search/SearchReportLoader.kt`（**新**；**复用 `fetchSearch(where)`**、金额 = 借方分录合计、合计按币种）、`ui/report/search/SearchResultReportFragment.kt`（**新**；`BaseReportFragment<SearchReport>`）、`ui/report/ReportType.kt`（改；`SEARCH_RESULT` **置于 `NONE` 之前**、`getReportNames(includeSearchResult)`）、`ui/report/ReportsActivity.kt`（改；`searchWhere` / `EXTRA_SEARCH_WHERE` / `showSearchResult` / `onNewIntent`）、`ui/search/SearchResultsFragment.kt`（改；溢出菜单入口）、`res/menu/search_results_menu.xml`（改）、`res/layout/{fragment_search_report,row_search_report}.xml`（**新**） |
| **①g 单测**（第二十五轮） | `app/src/test/java/.../util/{ManualDateTest,ManualDateRangeTest}.kt`（**新**，纯 JVM）、`.../ui/util/ManualDateWiringTest.kt`（**新**，源码守卫）、`.../ui/report/{ReportTypeTest,SearchReportLoaderTest,SearchReportWiringTest}.kt`（**新**；后者为源码守卫）、`.../ui/report/SearchReportLoaderTest.kt`（真库）、`.../ui/search/BulkEditResultTest.kt`（**新**）、`.../ui/search/SearchBulkWiringTest.kt` / `.../ui/transaction/view/{ExtendedViewStoreTest,ExtendedRowContentTest,ExtendedViewWiringTest}.kt`（补例） |
"""


def patch(path, edits):
    raw = io.open(path, encoding="utf-8", newline="").read()
    crlf = raw.count("\r\n")
    lf = raw.count("\n") - crlf
    if crlf and lf:
        raise SystemExit("mixed line endings in " + path)
    text = raw.replace("\r\n", "\n")
    for old, new in edits:
        n = text.count(old)
        if n != 1:
            raise SystemExit("anchor count %d for %r" % (n, old[:60]))
        text = text.replace(old, new, 1)
    out = text.replace("\n", "\r\n") if crlf else text
    io.open(path, "w", encoding="utf-8", newline="").write(out)
    print("OK", path, "edits=", len(edits), "crlf=", crlf, "bytes=", len(out))


TAIL = "`title_extended_views` / `menu_extended_view_settings` / `toast_transaction_sides_must_differ`）"
E13_HEAD = "**89 个测试类 / 1469 个用例 / 0 失败 0 错误 0 跳过**"
E13_CHAIN = "→ **二十四 1628**（**第二十四轮**"
E13_NOTE = (
    "→ **二十四 1628** → **{} {}**（**第二十五轮** R25 批量单事务 + 扩展视图单选化 + 一级菜单 + 日期手工填写 + "
    "搜索结果接报表；`--rerun-tasks --no-build-cache`，**{} 个测试类**；14 处变异逐处咬合 14 条红、逐处字节还原；"
    "见环境文档 **K25**）（**第二十四轮**"
)
INDEX_ANCHOR = "（改：模板账户模糊搜索） |\n| 清单 |"
ENV_ANCHOR = "（本项目技术栈总环境文档，坑表 K1–K24）"

patch(DOC, [
    (TAIL, TAIL + STAMP),
    (E13_HEAD, "**{} 个测试类 / {} 个用例 / 0 失败 0 错误 0 跳过**".format(classes, tests)),
    (E13_CHAIN, E13_NOTE.format(classes, tests, classes)),
    (INDEX_ANCHOR, "（改：模板账户模糊搜索） |\n" + INDEX_ROWS + "| 清单 |"),
    (ENV_ANCHOR, "（本项目技术栈总环境文档，坑表 **K1–K25**）"),
])
