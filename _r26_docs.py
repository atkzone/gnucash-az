"""R26 文档回填：规划书 → v1.26 + §14.1 索引 + E13 基线；环境文档 → K26 + 变更记录。

    python _r26_docs.py <classes> <tests> <apk_bytes> <apk_sha256> "<全量耗时>"
"""
import io
import sys

DOC = "docs/gnucash-az-开发规划书.md"
ENV = "Z:/wdDaily/程序调试运行手册/Kotlin-AGP-Gradle9-Android-环境搭建技术文档.md"

classes, tests, apk_bytes, apk_sha, duration = sys.argv[1:6]

STAMP = (
    "；**v1.26 回填「删图表 + 批量保存 ANR 真根因 + 报表图型展示」（第二十六轮）**"
    "（① **删除搜索结果页的「图表」**：`SearchChartDialogFragment`（只会画一张按账户饼图的弹窗）+ "
    "`search_results_menu.xml` 里的 `menu_search_chart` + `SearchResultsFragment` 里那个分支 + "
    "`search_chart_title` / `search_chart_by_account` / `search_chart_empty` 三条字符串（en+zh）**一并删掉** —— "
    "它的能力被本轮 ③ 的报表图型**完全覆盖**，留着就是两个入口各说一套；删完 `grep -rn SearchChartDialogFragment` "
    "只剩一条**源码守卫**（守卫「这个文件不许再回来」）；② **批量修改保存后 ANR + 数据没写入的真根因**（用户报的第三例）："
    "第二十四轮挪到 IO 线程、第二十五轮整批一个事务，**都还不够** —— 整批一个大事务意味着**写锁被整批持有**，"
    "与此同时列表 `notifyDataSetChanged()` 会**立刻重绑每一行**，而 `SearchResultViewHolder.bind()` 每行要跑 3~5 条**主线程**"
    "数据库查询（读分录 `buildModelInstance` / 读账户链 `getFullyQualifiedAccountName` / 读标签 `getTagNamesForTransaction`）"
    "⇒ 主线程读**饿在写锁后面** ⇒ ANR ⇒ 进程在事务中途被杀 ⇒ **整个事务回滚 = 一笔都没写** + 跳回首页。"
    "修复三件事：ⓐ `SearchBulkWriter.delete` / `update` 改**分块提交**（`uids.chunked(BATCH_SIZE = 50)`，**每块一个事务**）"
    "⇒ 写锁最长只被 50 笔持有；ⓑ 块间 `breathe()`（2 ms 暂停）**主动让出写锁**，给主线程的绑定查询留窗口；"
    "ⓒ `SearchResultsViewModel.search()` 里 `transactions.map {{ it.toSearchResultRow() }}` **移出 `withContext(Dispatchers.Main)`**"
    "（逐行 DB 查询本来就不该在主线程做）；⚠️ **为什么不是「再包一个事务」**：R25 的「整批一个事务」恰恰**加大**了写锁持有时间，"
    "与本轮用户报的症状同源 —— **事务粒度是「锁持有时间」的旋钮，不是「原子性」的旋钮**，大批量场景要往小调；"
    "③ **报表按图表型展示**（用户报：搜索结果只有合计列表，「无法使用饼图/柱状图/折线图展示」）："
    "报表页内加**「图型」下拉**（`Spinner`，四项 **表格 / 饼图 / 柱状图 / 折线图**，默认**表格**），"
    "选中即切换「表格滚动区」与「图表面板」的可见性；"
    "新增**纯 JVM** `SearchReportChart` 把行分桶：`byAccount`（按账户**严格分区**，按金额绝对值**降序**，无账户归入"
    "`UNCATEGORIZED` 桶，**带行数**）与 `byDate`（按 `LocalDate(row.dateMillis, DateTimeZone.forTimeZone(zone))` 分日，"
    "**最旧在前** ⇒ 折线图是时间轴而不是金额排序的锯齿）；三个图分别用 MPAndroidChart 的 `PieChart` / `BarChart` / `LineChart`，"
    "X 轴文字走 `IndexAxisValueFormatter`（账户名较长时 `labelRotationAngle = -30f`），柱/线用 `index.toFloat()` 作 X；"
    "⚠️ **多币种绝不同图相加**：`SearchReportLoader.charted()` 只挑**一个**币种作图画（`primaryCurrency` = "
    "**金额绝对值合计最大**的那个币种，LinkedHashMap + 行序保证同分时结果确定），其余币种**只留在合计表里**，"
    "并在图表上方用 `search_report_chart_note` **明说「图表只统计 %1$s，%2$s 仅见表格」**（把两种币种画进同一根柱子 = 一个"
    "**没有意义的数字**）；饼图/柱状图**丢弃零值桶**、折线图**保留**（断点会骗人）；空数据显示 `search_report_chart_empty`；"
    "`SearchReport.hasChart` 为 false 时不进图表分支；⚠️ **Kotlin 2.4 后端的 `SyntheticAccessorLowering` 不允许跨文件访问"
    "基类的 `protected companion val`** —— 复用 `BaseReportFragment` 的 `COLORS` 会让编译器 `BackendException` 崩在"
    "**另一个文件**上（报 `should not attempt to modify other files!`），故本 fragment 自带**局部 `PALETTE`**"
    "（与其它报表 fragment 同形）；**17 处变异逐处咬合 17 条红**（m01 整批一个大事务 · m02 删除不让出写锁 · "
    "m03 摘要回到主线程 · m04 折线按金额排 · m05 分桶忽略时区 · m06 账户桶升序 · m07 账户桶丢行数 · "
    "m08 图表折多个币种 · m09 图表挑最小币种 · m10 `hasChart` 恒真 · m11 默认图型改饼图 · m12 资源数组顺序对调 · "
    "m13 折线图画账户 · m14 被删的图表弹窗文件又回来 · m15 图表菜单项又回来 · m16 块大小≈整批 · m17 分块丢尾块），"
    "逐处**逐字节还原**（17 文件 sha256 全部 `byte-identical` + 1 处 `absent`）；⚠️ 本轮记下 **K26** 三坑"
    "（**事务粒度 = 写锁持有时间**，不是原子性旋钮 · Kotlin 2.4 跨文件访问基类 `protected companion` 会崩后端 · "
    "多币种报表必须单币种作图并把其余币种**说出来**）；全量单测 **{} 类 / {} 例 / 0 失败 0 错误 0 跳过**（见 E13）；"
    "**本轮含 BY**：debug APK `{} B` / sha256 `{}`，dex 硬核到 `SearchReportChart` / `SearchReportLoader` / "
    "`SearchResultReportFragment` / `SearchBulkWriter`（`chunked` / `breathe` / `BATCH_SIZE`）/ `SearchResultsViewModel`，"
    "并硬核到 `menu_search_chart` **缺席**；`resources.arsc`（UTF-8）硬核到 `图型` / `表格` / `饼图` / `柱状图` / `折线图` / "
    "`没有可绘制图表的数据` / `图表只统计` / `按账户（` / `按日期（` 与资源键 `label_search_report_chart_type` / "
    "`search_report_chart_types` / `search_report_chart_by_account` / `search_report_chart_by_date` / `search_report_chart_note`）"
).format(classes, tests, apk_bytes, apk_sha)

INDEX_ROWS = """| **①h 删除搜索结果「图表」**（第二十六轮，**已完成**） | `ui/search/SearchChartDialogFragment.kt`（**删**）、`res/menu/search_results_menu.xml`（改；删 `menu_search_chart`）、`ui/search/SearchResultsFragment.kt`（改；删分支）、`res/values/{strings,values-zh-rCN/strings}.xml`（改；删 `search_chart_title` / `search_chart_by_account` / `search_chart_empty`） |
| **①h 批量写库分块提交（ANR 真根因）**（第二十六轮，**已完成**） | `ui/search/SearchBulkWriter.kt`（改；`uids.chunked(BATCH_SIZE = 50)` **每块一个事务** + `breathe()` 2 ms 让出写锁，逐行 `try/catch` 保留）、`ui/search/SearchResultsViewModel.kt`（改；`toSearchResultRow()` 移出 `Dispatchers.Main`） |
| **①h 报表图型展示**（第二十六轮，**已完成**） | `ui/report/search/SearchReportChart.kt`（**新**，纯 JVM；`SearchChartBucket` / `byAccount` / `byDate`）、`ui/report/search/SearchReport.kt`（改；`chartCurrencyCode` / `byAccount` / `byDate` / `otherCurrencyCodes` / `hasChart`）、`ui/report/search/SearchReportLoader.kt`（改；`charted()` 单币种 + `primaryCurrency()`）、`ui/report/search/SearchResultReportFragment.kt`（改；图型 `Spinner` + Pie/Bar/Line + 局部 `PALETTE`）、`res/layout/fragment_search_report.xml`（改；图型下拉 + 图表面板 + 表格滚动区）、`res/values/{strings,values-zh-rCN/strings}.xml`（改；`label_search_report_chart_type` + `search_report_chart_types` + `search_report_chart_by_account` / `_by_date` / `_empty` / `_note`） |
| **①h 单测**（第二十六轮） | `app/src/test/java/.../ui/report/SearchReportChartTest.kt`（**新**，纯 JVM 5 例）、`.../ui/report/SearchReportLoaderTest.kt`（补 4 例）、`.../ui/report/SearchReportWiringTest.kt`（补 3 例；源码守卫）、`.../ui/search/SearchBulkWiringTest.kt`（改；分块事务 + 主线程 DB 读守卫）、`.../test/unit/db/SearchBulkWriterTest.kt`（改；跨块边界写入） |
"""

ENV_K26 = """
### K26 ★ 第二十六轮（R26）的三处坑：**事务粒度 = 写锁持有时间**、Kotlin 2.4 跨文件访问基类 `protected companion` 会崩后端、多币种报表必须单币种作图并**说出来**

**坑 1 · 「批量保存后 ANR + 数据没写入」：上一轮修的「整批一个事务」正是本轮故障的**放大器** —— 事务粒度是「写锁持有时间」的旋钮，不是「原子性」的旋钮**

R24 把批量写挪到 IO 线程（解掉界面死锁），R25 把 N 次自动提交收成「整批一个事务」（解掉 N 次 fsync）。用户**仍然**报：点保存 → 提示「正在更新 N 笔…」→ 弹「GnuCash dev 没有响应」→ 跳回首页 → **一笔都没写**。

根因是三条叠乘，缺一条都不出这个症状：

1. `SearchResultViewHolder.bind()` 每行跑 **3~5 条主线程数据库查询**（`buildModelInstance` 读分录、`getFullyQualifiedAccountName` 读账户链、`getTagNamesForTransaction` 读标签）；
2. 提交前关 CAB / 提交后刷新都走 `notifyDataSetChanged()` ⇒ **立刻重绑每一行**；
3. 写事务**整批持有** ⇒ 主线程那几十条读查询**全部饿在写锁后面** ⇒ 5 s 没响应 = ANR ⇒ **进程在事务中途被杀 ⇒ 整批回滚**。

⇒ 关键认识：**「包一个事务」买到的是原子性，代价是「写锁被整批持有」**。大批量场景两者冲突，而用户要的是「别卡」＋「至少要落一部分」—— 所以粒度必须**往小调**。

**处置**：`uids.chunked(BATCH_SIZE = 50)`，**每块一个独立事务**；块间 `breathe()`（2 ms 暂停）**主动让出写锁**；再把逐行 `toSearchResultRow()` 移出 `Dispatchers.Main`（它本来就是 DB 查询）。

⚠️ **判据**：`N × 自动提交`、`整批一个事务`、`分块事务` 在 UI 上**无法区分**，计时断言又不稳 ⇒ 可靠做法仍是**源码守卫**：断言 `uids.chunked(BATCH_SIZE)` 在场、`BATCH_SIZE` 是**有界常量**（不要写成 `uids.size`）、块间**有** `breathe()`。配套的**行为**断言是「跨块边界」（`BATCH_SIZE * 2 + 7` 条全部写入）—— 只测 3 条永远走不到第二块。

⚠️ 顺带一条**别照抄的写法**：`for (chunk in listOf(uids))` 看起来「也是 for 循环」，实际等于把整批当一块 —— 守卫要盯着 `chunked(BATCH_SIZE)` 这个**字面量**，不是「有没有 for」。

**坑 2 · Kotlin 2.4 的 `SyntheticAccessorLowering` **不允许跨文件**访问基类的 `protected companion` 成员 ⇒ 报错挂在**另一个文件**上**

`BaseReportFragment<D : ChartData<*>>` 里有个 `protected companion object { val COLORS = … }`。新 fragment 在同包、不同文件里直接引用 `COLORS`：

```
e: BackendException: The lowering of `SyntheticAccessorLowering` should not attempt to modify other files!
```

**报错文件是 `BaseReportFragment.kt`，不是你正在写的那一个** —— 第一眼会以为基类坏了。Kotlin 需要为「跨文件读 protected」合成 accessor，而该 lowering 阶段不允许回头改别的文件 ⇒ 崩。

**处置**：**每个报表 fragment 自带一份局部 `PALETTE`**（本工程其它报表 fragment 本来就是这么写的 —— 不是新发明，是回归既有形状）。要共享调色板就只能提成 `internal` 顶层常量。

⚠️ 同族提醒：**编译期后端崩溃**（`BackendException`）与**源码错误**要分开看 —— 它不指行号、不指你改的文件，`grep` 报错里的**类名**比读行号有用。

**坑 3 · 多币种报表**不能**画进同一张图 —— 要么分图，要么**挑一个币种并把其余币种说出来**

搜索命中的交易可能横跨多个币种。把 `¥ 100` 与 `$ 100` 加进同一根柱子得到 `200` —— 一个**没有意义的数字**，而且**没有任何报错**（用户只会觉得「这个数不对」）。

**处置**：`charted()` **只挑一个**币种作图：`primaryCurrency` = **金额绝对值合计最大**的那个（`maxByOrNull`；同分时靠 LinkedHashMap 插入序 + 行序保证**确定**，不要用 `Set` 使结果随机）；其余币种 `otherCurrencyCodes` **只留在合计表里**，并在图表上方用一行 note **明说**「图表只统计 X，Y 仅见表格」。`byDate` 也要按同一个币种过滤（否则日期桶里混币）。

⚠️ 两条细节：① **饼图/柱状图丢弃零值桶、折线图保留** —— 柱子上一个高度为 0 的项只会挤占标签，而折线图**删掉中间的点会让线断开**、读起来像「那天没有数据」（其实有，只是合计为 0）；② **`hasChart` 必须是派生属性**（`chartCurrencyCode != null && (byAccount.isNotEmpty() || byDate.isNotEmpty())`）—— 写成恒真，空报表也会去画一张空图。
"""

ENV_ROWS = (
    "| 2026-10-14 | **第二十三轮补充：基线 94 类 / 1550 例 / 0 失败 / 0 错误 / 0 跳过**"
    "（`--rerun-tasks --no-build-cache` 全量实测 1h 16m 11s、测试本身 1h 14m 0.37s、`37 actionable tasks: 37 executed`；"
    "+5 类 +81 例）；对应「②-v2 接线第二半 —— 冲突中心（只读）」：`ConflictDigest`（四态 `ConflictKind` 由 `decision` 的**结构**算出、"
    "**不读 `note`**；「读不出来」独立成一种情况，既不折进「两端都改了」也**不显示「0 处差异」**）+ `FieldDeltaKind` 八态"
    "（含「两端都没了这个字段」）+ `ConflictCenterCodec`（两层 `RecordCodec` 复用已验证转义；**某一侧没有值 ⇒ 省略键**；"
    "`MAX_REVIEWS = 100` / `MAX_VALUE_CHARS = 512`；**截断位在截断那一刻记录**、绝不事后按 `…` 推断）+ `ConflictCenterText`"
    "（三组 `when` **穷举**；字段名一律用**原始列名**）+ `ConflictCenterDialogFragment`（走 **dialog arguments** 而非静态持有者；"
    "`contentView(context, payload)` 可单测构建）+ `BackupPreferenceFragment.showConflicts`（**排在 `deservesCounts` 提前返回之前**）；"
    "**新增 K23**（未 `show()` 的 `AlertDialog` 里**没有 view** —— 读 `window.decorView` 的断言会**整批假绿**："
    "`noneSatisfy` 在空集合上**恒真**）；**12 处变异逐处咬合 17 条红**，逐处字节还原；"
    "**本轮不含 BY**，APK 沿用第二十轮 `debae437…b6e0` |\n"
    "| 2026-10-14 | **第二十四轮补充：基线 104 类 / 1628 例 / 0 失败 0 忽略**"
    "（`--rerun-tasks --no-build-cache`，任务 1h 24m 2s、测试本身 1h 16m 51.27s；+10 类 +78 例）；"
    "对应「①f 交易列表紧凑／扩展视图 + 借贷同号修复 + 批量写库异步化 + 模板账户模糊搜索」：`TransactionSides` / "
    "`AccountConditions.selectable()`（借贷共用一份 WHERE）+ `BulkActionRunner`（`withContext(IO)` + `Main.immediate`）"
    "+ `ExtendedViews` / `ExtendedViewStore` 全族 + 溢出菜单二级菜单；⚠️ 本轮收尾生成报告被沙箱拦（`Could not generate test report`）"
    "⇒ 任务判 `EXIT=1`、XML 未落盘，**权威数字取自 `reports/tests/testDevelopmentDebugUnitTest/index.html`**"
    "（1628 / 0 / 0 / 100%，表格 104 行求和一致），已另存 `app/build/evidence_r24/full1628_index.html`；"
    "**新增 K24**（CRLF 补丁 / 咬合被截断留变异态 / 无 `kotlinx-coroutines-test` / `FragmentResult` / 源码守卫锚点）；"
    "**12 处变异逐处咬合 26 条红**；**本轮含 BY**：APK 41,702,489 B / 39.77 MiB、sha256 `0ae9a2ea…a6315` |\n"
    "| 2026-10-14 | **第二十五轮补充：基线 111 类 / 1704 例 / 0 失败 0 错误 0 跳过**"
    "（`--rerun-tasks --no-build-cache`，任务 1h 24m 3s；+7 类 +76 例）；"
    "对应「①g 批量写库单事务 + 扩展视图单选化 + 一级菜单 + 日期手工填写 + 搜索结果接报表」："
    "`SearchBulkWriter` **整批一个事务**、`ExtendedViewStore.select(id?)` 唯一写入路径、`ManualDate` / `ManualDateRange`"
    "（**严格 `yyyyMMdd`**）+ 两个「手工填写」对话框、`ReportType.SEARCH_RESULT` + `SearchReportLoader`（复用 `fetchSearch(where)`）；"
    "**新增 K25** 七坑（整批一次提交 / `BaseReportFragment` 的泛型上界 / 报表枚举顺序 / **同一类日期不止一个控件入口** / "
    "源码守卫锚点要照抄真实串 / **咬合中途改源码 ⇒ 整轮作废且留变异态** / Windows Python 的 stdout 换行）；"
    "**14 处变异逐处咬合 14 条红**，逐处字节还原；**本轮含 BY**：APK 41,752,098 B、"
    "sha256 `f772c8221f5842d2abd85df2cc03c3566f00207c7c501ee0735e1421e6e42a32` |\n"
)


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
            raise SystemExit("anchor count %d for %r" % (n, old[:70]))
        text = text.replace(old, new, 1)
    out = text.replace("\n", "\r\n") if crlf else text
    io.open(path, "w", encoding="utf-8", newline="").write(out)
    print("OK", path, "edits=", len(edits), "crlf=", crlf, "bytes=", len(out))


TAIL = "`menu_report_search_results`）"           # v1.25 stamp 的结尾（唯一）
E13_HEAD = "**111 个测试类 / 1704 个用例 / 0 失败 0 错误 0 跳过**"
E13_ANCHOR = "逐处字节还原；见环境文档 **K25**）"
INDEX_ANCHOR = "| 清单 | `app/src/main/AndroidManifest.xml` |"
INDEX_TESTCOUNT = "`app/src/test/`（70 个 `.kt`）"
ENV_REF = "（本项目技术栈总环境文档，坑表 **K1–K25**）"
ENV_PITRANGE = "编号 K1–K17，按\"危害面\"排序。"
ENV_SECTION_TAIL = "\n---\n\n## 4. 复现命令速查（照抄）"


def append(path, text):
    raw = io.open(path, encoding="utf-8", newline="").read()
    crlf = raw.count("\r\n")
    lf = raw.count("\n") - crlf
    if crlf and lf:
        raise SystemExit("mixed line endings in " + path)
    if not raw.endswith("\n"):
        raw += "\n"
    if crlf:
        text = text.replace("\n", "\r\n")
    out = raw + text
    io.open(path, "w", encoding="utf-8", newline="").write(out)
    print("APPEND", path, "bytes=", len(out))


def main():
    e13_note = (
        "逐处字节还原；见环境文档 **K25**）"
        "（**第二十六轮** R26 删图表 + 批量保存 ANR 真根因（分块提交 + 让出写锁）+ 报表图型展示；"
        "⚠️ **沙箱拦后台 ⇒ 全量单测改由 10 批前台过滤跑拼装**（逐批归档 `TEST-*.xml` 到 `app/build/evidence_r26/`，"
        "每批日志含 `> Task :app:testDevelopmentDebugUnitTest` 且**不带 `UP-TO-DATE`**；"
        "与 R25 归档 index 逐类对照 ⇒ **+1 类 / +17 例、无类消失**），"
        "**{} 个测试类 / {} 个用例 / 0 失败 0 错误 0 跳过**；17 处变异逐处咬合 17 条红、逐处字节还原；"
        "见环境文档 **K26**）"
    ).format(classes, tests)
    patch(DOC, [
        (TAIL, TAIL + STAMP),
        (E13_HEAD, "**{} 个测试类 / {} 个用例 / 0 失败 0 错误 0 跳过**".format(classes, tests)),
        (E13_ANCHOR, e13_note),
        (INDEX_ANCHOR, INDEX_ROWS + INDEX_ANCHOR),
        (INDEX_TESTCOUNT, "`app/src/test/`（112 个 `.kt`）"),
        (ENV_REF, "（本项目技术栈总环境文档，坑表 **K1–K26**）"),
    ])
    patch(ENV, [
        (ENV_SECTION_TAIL, ENV_K26 + ENV_SECTION_TAIL),
        (ENV_PITRANGE, "编号 K1–K26，按\"危害面\"排序。"),
    ])
    append(ENV, (
        ENV_ROWS
        + "| 2026-10-" + "15 | **第二十六轮补充：基线 {} 类 / {} 例 / 0 失败 0 错误 0 跳过**"
        "（`--rerun-tasks --no-build-cache` 全量实测 {}）；"
        "对应「①h 删「图表」+ 批量保存 ANR 真根因 + 报表图型展示」："
        "`SearchChartDialogFragment` 及其菜单项/字符串**整体删除**；`SearchBulkWriter` 由「整批一个事务」改**分块提交**"
        "（`BATCH_SIZE = 50` + `breathe()` 2 ms 让出写锁）+ `SearchResultsViewModel` 的逐行 DB 查询移出主线程；"
        "报表新增「图型」下拉（表格/饼图/柱状图/折线图）+ 纯 JVM `SearchReportChart`（`byAccount` / `byDate`）；"
        "**新增 K26** 三坑（**事务粒度 = 写锁持有时间**，不是原子性旋钮 · Kotlin 2.4 跨文件访问基类 `protected companion` "
        "会让 `SyntheticAccessorLowering` 崩后端且报错文件是基类 · 多币种报表必须单币种作图并把其余币种**说出来**，"
        "饼图/柱状图丢零值桶而折线图保留）；**17 处变异逐处咬合 17 条红**，逐处字节还原（17 文件 `byte-identical` + 1 处 `absent`）；"
        "**本轮含 BY**：APK {} B、sha256 `{}` |\n"
    ).format(classes, tests, duration, apk_bytes, apk_sha))


if __name__ == "__main__":
    main()
