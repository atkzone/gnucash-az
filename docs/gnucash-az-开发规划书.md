# GnuCash Pocket（gnucash-az）功能扩展 · 开发规划书；**v1.27 回填 ②-v3「冲突解决动作」**（第二十七轮）：把冲突中心从「只读镜子」升级为**能拍板** —— 用户点「保留本机／采用云端」后，答案经**单点写路径** `ConflictResolutionStore.remember` 落进新表 `sync_resolutions`（schema **35→36**，唯一索引 `(record_type, record_key)` **REPLACE 语义**、`putResolution` 返回 `Boolean` 把「没写进去」如实报回），下一轮 `SyncRoundPlanner` **只在该记录本会被扣下时**读它并强制一个动作（`ResolveSide` 存的是**边**不是动词：本机有 ⇒ `PUSH`（对端无副本 ⇒ `If-None-Match: *`）、本机已删 ⇒ `DELETE_REMOTE`；采用云端：对端有 ⇒ `PULL`、对端已确认删 ⇒ `DELETE_LOCAL` + 墓碑；对端**存在但读不出** ⇒ 不兑现、继续停驻）；**消费时机**：`resolutionsApplied ∩ landed` 才清除（`412`／传输失败／被 store 拒 ⇒ 保留答案下轮重试），「该记录已不再停驻且本轮真的出了动作」⇒ `resolutionsDropped` 清除，**本轮没看过的记录类型的答案原样保留**（否合同步某类记录会静默丢另一类的答案）；**11 处变异逐处咬合 11 条红**（m01 保留本机被抹成不动 · m02 强制推丢 `MustNotExist` · m03 采用云端面对对端删除时原地停驻 · m04 过期答案永不清 · m05 被观察记录的答案被当过期丢弃 · m06 不看落地就消费 · m07 永不消费 · m08 读不出的答案被猜成本机 · m09 清空路径谎报失败 · m10 读不出的行也给「采用云端」· m11 写失败吞成成功），逐处**逐字节还原**（11 文件 sha256 全部 `byte-identical`）；全量单测 **114 类 / 1791 例 / 0 失败 0 错误 0 跳过**（见 E13）；**本轮不含 BY**（KFCSTX）

> 仓库：`https://github.com/atkzone/gnucash-az`（Apache-2.0；fork 自已废弃的 `codinguser/gnucash-android`）
> 本机工作区：`Z:\wdDaily\gnucash-az`（浅克隆，`master` @ `9933108`，2026-10-06）
> 文档状态：**v1.27** —— v1.0 纳入 2026-10-11 环境实测与全部锁定决策；v1.1 补入 ①「多关键词与/或 · 多标签 · 搜索模板」细化（§5①、§2.4、§6.1、§11、§13）；v1.2 回填 ①a 的**持久化与模板管理 UI** 落地结果（schema 30→31、`SearchTemplateDbAdapter`、模板对话框；全量单测 533 例 0 失败）；**v1.3 回填 ⑧ 标签数据层**（schema 31→32、`TagCodec`/`TagsDbAdapter`、保存与导入的双写钩子、①b 接真实数据；R-9 重估、三个设计决定记录在 §5⑧）；**v1.4 回填 V7 往返闭环 + 标签 chip 展示层**（`TagXmlRoundTripTest` 8 例 + `TagChipFilterTest` 20 例；全量 642 例 0 失败；debug APK 重建 44,178,018 B）；**v1.5 新增「导入备份」**（备份设置 → 文件浏览器选文件，支持 `.gnca`/`.gnucash`，扩展名不符先拦下；纯函数 `BackupFileFormat` + `BackupFileFormatTest` 15 例；全量 672 例 0 失败）；**v1.6 回填 ②-v2 的决策内核 + 持久化层**（schema 32→33 `sync_state`/`tombstones`；纯函数 `ThreeWayMerge`（`decide`/`planDeletions`）+ `SyncStateDbAdapter` 原子快照读；删除只认「已确认的缺席」+ 两轮晋升 + 两道放弃闸；全量单测见 E13）；**v1.7 回填 ②-v2 的 WebDAV 传输层**（纯协议核心 `WebDavPath`/`WebDavXml`/`WebDavListing` + `WebDavClient`（`Depth: 1` 自递归、列举完整性判定）+ OkHttp transport（复用 `TrustExt`）；`Incomplete` **绝不退化为"空"**、只经 `confirmsAbsence` 接线；§7 新增 7.5 列举协议；新增 54 例全绿；本轮不含 BY）；**v1.8 回填 ②-v2 的 WebDAV 动作层**（纯策略 `WebDavActions`：五个动词的 `classifyXxx` + `collectionsToEnsure`/`putHeaders`/`moveHeaders`；`WebDavClient` 加 `put`/`download`/`delete`/`mkcol`/`move`/`ensureCollections`/`putCreatingCollections`；OkHttp transport 补写动词与下载；条件写 `If-Match`/`If-None-Match` 防丢更新；§7 新增 7.6 动作层协议；新增 45 例、五处闸门咬合验证；本轮不含 BY）；**v1.9 回填 ②-v2 的编排层（一轮真实同步）**（schema 33→34 给 `sync_state` 补 `base_etag`/`pending_since`；纯核心 `SyncLayout`/`RecordCodec` + `SyncRoundPlanner`（列举→合并→动作→写回基线/墓碑/pending 一轮合成）+ 驱动 `SyncRound`/`LocalRecordStore` 接缝；不完整列举**整轮放弃**、`pending` 二轮晋升、未变则不下载、`412` 停轮；§7 新增 7.7 编排协议；新增 8 例闸门咬合验证）；**v1.10 回填 ②-v2 的落盘层（真实 `LocalRecordStore`：账号）**（纯映射 `AccountFields`（键 = `accounts` 列名；布尔一律写出、可空量省略）+ `DbLocalRecordStore`（`getAllRecords` 含隐藏账号、`REPLACE` 就地更新、非级联 `deleteRecord`、四种拒绝）；`LocalRecordStore.write` 改返回 `Boolean`（**被拒 ⇒ 绝不回写基线**）+ `list` 对不支持类型**抛异常而非返回空表**；新增 `SyncRoundResult.deferred`；§7 新增 7.8 落盘协议；新增 33 例、六处闸门咬合验证；**本轮含 BY**：debug APK 44,182,697 B / sha256 `c9dfe3bf…1eb28`）；**v1.11 回填 ②-v2 的落盘层扩展（真实 `LocalRecordStore`：交易 + 分录）**（纯映射 `TransactionFields`（键 = `transactions` 列名；分录内联 `split.<index>.<列名>`，索引即顺序且**必须连续**；金额走原始分数 `value/quantity num·denom`；`NEVER` 的 `reconcile_date` 以「键缺席」表达）+ `DbLocalRecordStore` 接 `TRANSACTION`（模板**列举排除 / 写入拒 / 删除拒**；**五道闸**：不可解·uid 不符·模板 ／ 商品解析不出 ／ 某分录账号不在本机⇒整条延后 ／ **不配平拒**（防凭空造"不平衡"账号）／ **落库≠载荷拒**（防模型规范化造成永久来回推））；`SPLIT` 仍**非独立同步单位**（随交易整体走）；**咬合验证挖出并删除一处死代码**（`transaction.id = 现存 id` 实为空操作 —— 旧分录由默认 `REPLACE` + `ON DELETE CASCADE` 清掉，见环境文档 **K17**）；§7.8 补交易约定；新增 `TransactionFieldsTest` 21 例（纯 JVM）与 `DbLocalRecordStoreTest` 19→40 例（×2 SDK）；全量单测见 E13；**本轮含 BY**：debug APK `44,182,697 B` / sha256 `f49c6e9e…4cecfa`（与第十轮**逐字节同体积但哈希不同** ⇒ K16；是否在包以 dex 字面量硬核到 `templates are not part of the merge`、`storing it would not reproduce the payload`））；**v1.12 回填 ②-v2 的「父引用不可解」一族**（① **ROOT 归一化**：`list` 把"父 = 本书 ROOT"读作**无父** —— ROOT 的 uid 由建书设备铸成，照发上线会让对端**每一个顶级账号**永久延后（本轮动手时发现的、比原缺口更严重的问题）；② **删账号修悬空子引用**：`delete()` 调 `reassignDescendantAccounts`，**库里真的**把后代重挂到本机 ROOT 并重算 `full_name`（原 `deleteRecord` 只修内存缓存 ⇒ 悬空子账号永久不可写 = 唯一不收敛的延后情形）；§7.8 补两条约定 + 已知限制 ③ 结案；新增 4 例（`DbLocalRecordStoreTest` 40→**44** ×2 SDK，sync 包 311 例）、**两处咬合**共 8 红；全量单测见 E13；本轮不含 BY）；**v1.13 回填 ②-v2 的「删账号 × 交易的交叉」**（`deleteAccount` 在**该账号仍被任何分录引用**时**拒绝（延后）**：光"不用 `recursiveDeleteAccount`"不够 —— `splits.account_uid` 是 `ON DELETE CASCADE` + `PRAGMA foreign_keys=ON`，删行仍会带走分录 ⇒ 交易少腿/无腿、**不配平** ⇒ 被交易写入闸拒收 ⇒ **永远推不出去**（= 一次账号删除**间接改写另一个 record type**，而合并从未同意）；与 app 自身一致：界面快捷删除本就先查 `getTransactionCount == 0 && getSubAccountCount == 0`（`getTransactionCount` 按 `splits.account_uid` 统计 ⇒ "0 交易" ⟺ "0 分录"）；`DbLocalRecordStore` 构造增 `splits: SplitsDbAdapter`；§7.8 补"删账号前置闸"约定 + 已知限制 ④ 结案；新增 2 例（`DbLocalRecordStoreTest` 44→**46** ×2 SDK，sync 包 315 例）、**两处咬合**共 12 红；全量单测见 E13；本轮不含 BY）；**v1.14 回填 ②-v1 单向加密备份（自描述容器 + 远端备份/恢复编排）**（纯核心 `EncryptedBackup`：文本头（`version`/`kdf`/`iterations`/`cipher`/`keybits`/`salt`/`iv`，首行 magic）+ 二进制密文体；PBKDF2-HMAC-SHA256 → AES-256-GCM；**头部字节作 AAD**（不认证则改 `iterations`/`salt` 要等到**产出乱码**才暴露；认证后头部篡改与口令错**共用同一条失败路径**）；**版本号 = 唯一前向兼容闸**（算法名按版本钉死 ⇒ 不认识的算法判**损坏**而非"更新的文件"）；口令错/密文改/头部改一律 `WrongPasswordException`（GCM 不区分，消息同时写明"或已损坏"）；空口令不允许加密、上层把 null/空统一读作不加密；迭代数**可注入**（测试 1000/生产 600k）。纯编排 `RemoteBackup`：**单一固定路径** `/backup/<bookUid>.gnca`（按模式分名会留下**陈旧兄弟文件**，恢复就得猜哪个是当前的；扩展名不因加密而变、由内容 magic 声明）+ **硬守卫**（远端是密文而无口令 ⇒ `PassphraseRequired`、**零字节返回**，把密文当书交回＝"恢复说成功、账本变乱码"的持久静默故障）；`PUT` 不带条件（§7.6 的条件写是保护**合并**；v1 单向备份"覆盖"正是被要求的行为）；五态返回区分"没备份/备份坏了/口令错/要输口令/网络问题"；与 v2 布局不冲突（`SyncLayout.parse` 对它返回 `null` ⇒ `materialize` 跳过）。§8 新增 8.1 容器与远端约定；新增 `EncryptedBackupTest` 24 + `RemoteBackupTest` 16（纯 JVM）⇒ sync 子集 15 类/355 例；**5 处变异分两批咬合共 18 红**；全量单测见 E13；**本轮含 BY**：debug APK `41,372,674 B`（39.46 MiB）/ sha256 `934d9d72…d6946`，dex 内硬核到 `BEGIN GNUCASH POCKET BACKUP` / `PBKDF2WithHmacSHA256` / `AES/GCM/NoPadding`；⚠️ 体积自第十一轮的 44.18 MB 回落至 39.46 MiB —— 本轮是**首次以 `--rerun-tasks --no-build-cache` 干净重建**（此前各轮是增量/缓存态），与 V12 首建的 **39.3 MiB** 同量级 + 九轮代码增量）；**v1.15 回填 ②-v1 的「接线第一半」（纯核心）**（承接第十四轮"把 `RemoteBackup` 接真实 WebDAV"：新增纯核心 `sync/webdav/WebDavConnection.kt`（连接配置的**类型化校验 + 归一化**，11 类问题；`Ready.connect(WebDavTransportFactory)` 是唯一接缝）+ `util/BookPayload.kt`（恢复载荷守卫：魔数单点、`classify`/`inspect`，拒绝网页/空体/非书）；`RemoteBackup.download` 接入守卫并新增 `NotABook` 六态；**顺带修掉一处既有缺陷** —— `ImporterFactory` 的 zip 魔数按**小端**拼装而常量写成大端 `0x504B0304`，那个分支**从未命中过**（`.gnca.zip` 备份导入必然失败），集中到 `BookPayload` 后修正；新增 `BookPayloadTest` 18 例 + `WebDavConnectionTest` 27 例 + `RemoteBackupTest` 补 5 例；4 批变异共 **32 红**；全量单测见 E13；**本轮不含 BY**，APK 沿用第十四轮）；**v1.16 回填 ①c「全文搜索」（首页右上角入口 + 查询语法 + 搜索词全中文）**（新增纯核心 `ui/search/FullTextQuery.kt`：把**一行纯文本**编译成裸 `WHERE` 片段（仍经 `SearchResultsFragment.EXTRA_FORM` → `fetchSearch`）—— 空格 / `，` / `、` = **且**、`|`（或 `｜`）= **或**、`not`（大小写不敏感）= **否定**、`"…"` 锁词组、`(…)` 分组，优先级 `not` ＞ 且 ＞ 或；一个词对**摘要 / 备注 / 编号 / 两条分录备注**做模糊 `LIKE '%词%'`，**叶子谓词委派 `SearchQueryBuilder`**（"哪些列、怎么匹配"仍只有一份实现）；⚠️ **否定必须 NULL 安全** —— SQLite 里 `0 OR NULL` = `NULL` 且 `NOT NULL` = `NULL`，直接 `NOT(谓词)` 会**静默丢掉所有"未命中且至少一列为空"的交易**（交易只要有任一文本列为 `NULL` 就中招），故编译为 `NOT COALESCE(谓词, 0)`；括号不闭合 / 悬空 `not` / 只有算子 ⇒ **降级丢弃而非报错**，完全没有词 ⇒ `""`；**首页右上角「搜索」→「全文」**（原 `SearchView` 的**按账户名过滤被替换**）经 `FormActivity(FormType.FULL_TEXT_SEARCH)` 进 `FullTextSearchFragment`（单行输入 + 语法提示 + 搜索按钮）；**左侧导航栏「搜索」→「高级搜索」**（行为不变，仍打开表单式搜索）；**搜索词一律用中文** —— 新增入口 / 标题 / 提示语**直接以中文作默认值**（不限 locale），另补 zh-CN 缺失的历史搜索词表 32 条（`title_search_form` / `search_criteria_all|any` / `search_field_*` / `search_compare_*`）；新增 `FullTextQueryTest` 24 例全绿，`SearchFormTest` / `SearchQueryBuilderTest` **零回归**；**7 处变异分 3 批咬合共 66 条红**（33 个用例 ×2 SDK）；⚠️ **已知限制**：表单式路径的 `ComparisonType.None` 仍是 **NULL 不安全**的 `NOT (…)` —— 本轮只保证全文路径 NULL 安全，刻意不动历史行为（改它会牵连 `SearchFormTest` 的逐字断言）；全量单测见 E13；**本轮含 BY**）；**v1.17 回填 ①c 第四轮「常用搜索」+ 字段扩展 + 两页互跳**（① **模糊匹配扩到"所有字段值"**：`AnyField` 由 5 列（摘要/备注/编号/两条分录备注）扩到 **7 列** —— 补两条分录的**账户名**（`accounts.name` 相关子查询）与**标签**（`transaction_tags` `EXISTS` 子查询），仍一律走 `sqlEscapeLike` 转义；② **全文 / 高级两页共用的「常用搜索」**：新增共享层 `SearchFavoritesUi`（保存**同名覆盖**、应用、改名、**多选批量删**），全文搜索页加「保存为常用搜索 / 常用搜索 / **高级搜索**」三按钮，高级搜索页复用同一层并补上**批量删**；③ **模板载荷零 schema 变更**：`SearchTemplate` 增 `kind`（`SearchTemplateKind.Form` / `FullText`）与 `query`，存进**同一个 JSON 信封**内 ⇒ `search_templates` 表结构不动、既有 Form 模板**逐字节兼容**（`kindOf` / `fullTextQueryOf` 判别），`SearchTemplateDbAdapter` 加 `getByKind` / `delete(ids)`；④ **顺手修掉两处既有缺陷** —— `SearchFormFragment` 重写了 `onCreateOptionsMenu` 却**从未** `setHasOptionsMenu(true)`（`FormActivity` 也不转发菜单）⇒ 现有模板菜单**从未显示过**（改继承 `MenuFragment` 一并修好）；删常用搜索失败却提示"保存失败" ⇒ 新增 `search_templates_delete_failed`；⑤ 搜索相关字符串**一律中文**（`search_templates_*` 17 条 values/zh-rCN 对齐，措辞由"模板"改"常用搜索"）；**7 处变异分 2 批咬合共 50 红**（批 A 3 处 AnyField 扩列 19 用例 ×2 SDK = 38 红；批 B 4 处 载荷往返 / kind 判别 / `getByKind` 过滤 / 批量删 6 用例 ×2 SDK = 12 红）—— ⚠️ 验证中途**断电两次**，`mutate.py` 备份还原 + `grep` 回读确认源码干净；全量单测见 E13；**本轮含 BY**：debug APK `41,412,740 B` / sha256 `075c958f…3480b`，dex 硬核到 `SearchFavoritesUi` / `SearchTemplateKind` / `getByKind` / `accountNameCondition` / `anyTagCondition`，`resources.arsc` 硬核到 `常用搜索` / `保存为常用搜索` / `删除失败` 与资源键 `search_templates_delete_failed`）；**v1.18 回填 ①d「交易模板全链 + 表单借贷双账户 + 搜索结果排序/合计/统计/图表」**（① **交易模板**：新表 `transaction_templates`（schema **34 → 35**，纯加法）—— `TransactionTemplateCodec` 把「**空值金额**」表达成 **JSON 里省略 `amount` 键**（`null` 绝不退化成 `0`，空串也不写），`TransactionTemplateDbAdapter` 支持同名覆盖 / 批量删 / 坏载荷标 `decodeFailed` 而**不丢行**；抽屉新增「交易模板」页（列表 + FAB + 套用/编辑/删除 + 删除二次确认），编辑对话框**显式选借贷两个账户**、金额可留空；点条目即带 `SELECTED_TEMPLATE_ID` 进交易表单**预填快速新建**（空值金额不填）；② **表单显示并可直接改「借」「贷」两个账户**：新增借方下拉、原转出下拉改贷方语义，`extractSplitsFromView` 按位置直接定 `DEBIT`/`CREDIT`（**不再依赖被隐藏的 `input_transaction_type`**）；③ **搜索结果增强**：四种排序（日期↑↓ / 金额↑↓）由 `SearchSortOrder` 产出裸 `ORDER BY`、经新增的 `fetchSearch(where, orderBy)` 注入（空 WHERE 走字面量 `1`，**查询形状不变 ⇒ 换排序不改变命中行**）；⚠️ **金额 = 借方分录合计**（`SUM(value_num * 1.0 / value_denom) WHERE type='DEBIT'` 的相关子查询）—— 分录值在库里**无符号**、配平交易借贷等额 ⇒ 直接 `SUM` 恒为 0；`SearchSummary` 纯函数做**按币种合计 / 按账户严格分区 / 按标签分桶（允许重叠）**，合计与统计走对话框、图表用已有 MPAndroidChart 画**按账户**饼图（按标签会重叠故只列不画），入口统一在搜索结果页溢出菜单（该页改继承 `MenuFragment`）；④ **顺手修掉三处**：`TransactionTemplatesActivity` **漏注册清单**（运行时必崩）、编辑对话框「先 load 后挂 adapter」可能丢预选位置（改「先挂再 load」+ 兜 `-1`）、账户名缺失误用「金额空值」文案；**5 处变异 / 2 批咬合共 19 红**；全量单测 **75 类 / 1287 例 / 0 失败**；**本轮含 BY**：debug APK `41,490,280 B` / sha256 `bc415c2e…131fa`，dex 硬核到 `TransactionTemplateCodec`/`TransactionTemplatesActivity`/`SearchSortOrder`/`SearchSummaryDialogFragment`/`SearchChartDialogFragment`/`transaction_templates`，`resources.arsc` 硬核到 `交易模板`/`合计与统计`/`按账户`/`借方账户`/`贷方账户`/`无账户`）；**v1.22 回填 ②-v2「接线第一半」—— 手动一轮同步 + 冲突评审回传**（把第九轮起就在纯 Kotlin 里跑通的 `SyncRound` 接到设置页：① **结果描述收成纯函数** `sync/SyncNotice.kt` —— 8 态 `SyncNotice` + 2 态 `SyncAbandon` + `SyncNotices.of(result)`，按**严重度**取胜出分支（放弃 ＞ 需重启 ＞ 有失败 ＞ 有冲突 ＞ 有延后 ＞「一条都没动」＞ 同步完成），`deservesCounts` **仅 `NOTHING_TO_DO` 为 false**（两台设备本来就一致时不弹「上传 0 · 下载 0」）；② **`SyncOutcome` 三态 + 薄适配层** `sync/SyncRunner.kt` —— `connection.check()` 是**唯一漏斗**（`Invalid` ⇒ `NotUsable`、**零请求**），`Ready` ⇒ `try { SyncRound(...).run() } catch (e) { Failed(e.message) }`（未捕获的异常会让界面停在"同步中"并把这一轮说成成功 ⇒ 必须兜成**显式失败**）；③ **`SyncRoundResult` 修形**：新增 `abandonReason: SyncAbandon?`（放弃的原因要能区分"列举不完整（重试）"与"地址指到别的目录（去改地址）"），`reviews: List<ConflictReview>` **取代** `conflicts: Int` —— 原先冲突只被数了个数就当场蒸发（合并算对了、界面无从展示），`conflicts` / `movedAnything` 降为**派生**属性；④ **设备 id read-or-create** `sync/AndroidSyncDeviceId.kt`（读**默认** SharedPreferences 的 `key_sync_device_id`，空则生成 UUID 写回，**永不 regenerate** —— 那会让墓碑表整体失效、已删记录在对端复活）；⑤ **双向同步是显式 opt-in**：`WebDavPreferences.twoWaySync` 独立于 `isConfigured`、默认 false（双向允许**对端删本机记录**，若由"填了地址"派生即等于用户配个备份地址就被默许接受删除），开关用 `setOnPreferenceChangeListener` —— `newValue != true` 直接放行，`true` 则先弹确认并**返回 `false`** 让行保持 off，确认后才写 store 并 `setChecked(true)`（`SwitchPreference` 只在返回 `true` 时落库；`setChecked()` 不是用户动作、不会重入监听器 ⇒ 不会"确认 ⇒ 回写 ⇒ 再问"死循环）；`syncNow()` 一律从 store **重读**开关（"行显示开、store 说关"正是"打开 → 拒绝确认 → 返回"的终态）；⑥ **值→文案单点映射** `ui/settings/SyncNoticeText.kt`（`of(notice)` 8 分支 + `of(outcome)`，`NotUsable` 委派 `WebDavNoticeText` 把问题**点到具体那一行**）；设置页只加两行（`SwitchPreference` 双向同步 + `Preference` 立即同步，后者未开双向时禁用）+ 3 个 `donottranslate` 键 + `webdav_sync_counts`（六个占位一次性格式化）；新增 **3 类 / 34 例**（`SyncNoticeTest` 13 纯 JVM / `SyncRunnerTest` 11（真 `SyncRound` + 计数假 transport + 可抛假 store，含"不可用零请求""store 抛异常 ⇒ `Failed` 且零请求""空 peer 而已有 base ⇒ `WRONG_FOLDER`"）/ `SyncNoticeTextTest` 10）+ `WebDavPreferencesTest` / `BackupPreferenceScreenTest` 各补用例；**10 处变异逐处咬合 31 条红**（A1 优先级 6 · A2 `EMPTY_REMOTE` 误报 4 · A3 `movedAnything` 恒真 6 · A4 `reviews` 不回传 2 · A5 `NotUsable` 误报 2 · A6 计数占位互换 2 · A7 归因写错 2 · A8 异常不兜 2 · A9 `twoWaySync` 默认开 2 · A10 开关 XML `defaultValue` 改 true 2），逐处自备份**逐字节还原**、收尾 `diff` 7 文件全 OK + `grep BITE`=0；⚠️ 脚本自查：咬合脚本用 `python /tmp/…` 在 Windows 上会被解析成 `Z:\tmp\…`（**bash 的 `/tmp` 与 Windows python 的 `/tmp` 不是同一处**）⇒ 中途 `exit 1` 让还原段没跑到，补跑 A10 时又发现 `fragment_backup_preferences.xml` 是 **LF** 而非 CRLF（锚点自适应换行后通过）；全量单测 **89 类 / 1469 例 / 0 失败 0 错误 0 跳过**（`--rerun-tasks --no-build-cache`，`BUILD SUCCESSFUL in 1h 8m 56s`、测试本身 1h 6m 47.52s、`37 actionable tasks: 37 executed`）；**本轮不含 BY**，APK 沿用第二十轮 `debae437…b6e0`）；**v1.23 回填 ②-v2「接线第二半」—— 冲突中心（只读）**（把上一轮回传的 `ConflictReview` 真正消费掉 —— 本轮之前 `webdav_sync_conflicts` 字符串已经写好却**没有任何人消费它**，正是"配了但不读"：① **分类收成纯函数** `sync/ConflictDigest.kt` —— `ConflictKind` 四态（`BOTH_EDITED` / `DELETED_THERE_EDITED_HERE` / `DELETED_HERE_EDITED_THERE` / `UNREADABLE`）由 `decision` 的**结构**算出（**不读 `note`** —— 那是写给日志的自由英文，靠它显示等于让界面跟着一句日志的措辞走，改一次措辞就把每一条悄悄重新归类），**「读不出来」独立成一种情况**、既不折进「两端都改了」也**不显示「0 处差异」**（"0 处差异"读起来就是"两边一致"，这是唯一绝不能从一份读不出的载荷里得出的结论），`kindOf` 对**不可评审**的 decision 也报「读不出来」而不是**编一个像样的错故事**；`MergeDecision.fieldDiffs` 扩展取出两类可评审 decision 的差异；② **字段差异分类** `FieldDeltaKind` 八态（本机改过 / 云端改过 / 两端改得不一样 / 本机有云端没有 / 云端有本机没有 / 本机删了这个字段 / 云端删了这个字段 / **两端都没了这个字段**），从 `(base, local, remote)` 三值判定 —— ⚠️ 「两端都没了这个字段」**在删改冲突里真的会发生**（`ThreeWayMerge.editDiff` 的幸存侧自己也少了一个字段），说成「两端都改了」会把用户指向一个**已经不存在的字段**；③ **载荷编解码** `ui/settings/ConflictCenterCodec.kt` —— 两层 `RecordCodec`（外层 `r0 = <内层>`；内层 `id` / `note` / `decision` / `d.<i>.<side>`）复用已验证的转义而不造第二套，⚠️ **某一侧没有值 ⇒ 省略键**（`null` 绝不写成 `""`，否则"对端没有这个字段"会变成"对端把它清空了"），上限 `MAX_REVIEWS = 100` / `MAX_VALUE_CHARS = 512`（要跨 Binder 进 dialog arguments），**截断位在截断那一刻记录、绝不事后按 `…` 推断**（真值可能合法地以 `…` 结尾、长度恰好等于上限 ⇒ 会给**完整**的值贴上"已截断"），`dropped` / `capped` / `truncated` 随载荷回传 ⇒ 界面**主动说出**「丢了几条 / 列表被截断 / 值被截断」（静默截断会让用户以为看到的就是全部差异）；冲突走 **dialog arguments** 而不是静态持有者（后者进程被杀后回来是**空的** ⇒ 显示"没有需要确认的记录"，与"确实没有"长得一模一样）；④ **值 → 文案单点** `ui/settings/ConflictCenterText.kt`（三组 `when` **穷举** ⇒ 新增枚举值会**编译失败**而不是渲染成空行；**字段名一律用原始列名**、不做"友好名" —— 同步层以数据库列名为键，而 `name` / `description` 的**模型含义与列名相反**，在这里发明友好名会让屏幕与数据各说一套）；⑤ **对话框** `ui/settings/ConflictCenterDialogFragment.kt`（标题 + 提示行 + 逐行摘要，点一条出二级对话框显示「记录：类型 / key 前 8 位 + 情况 + 每字段三列对照」，`null` 显示成「（无）」而不是空白；空态与列表态**走同一个渲染函数**；`build(context, payload)` / `contentView(context, payload)` **不要求 attach** ⇒ 可单测构建 —— 这个对话框是在用户**已被告知「有记录需要确认」之后**才打开的，只在显示时才失败的构造留给用户的是一句承诺和一块空白）；⑥ **入口** `ui/settings/BackupPreferenceFragment.kt`（`showSyncOutcome` 里 `if (result.reviews.isNotEmpty()) showConflicts(result.reviews)`，且**排在 `deservesCounts` 提前返回之前** —— 冲突列表不能被"本轮没什么可计数"那道闸一起挡掉）；新增 **5 类 / 59 例**（`ConflictDigestTest` 15 纯 JVM、`ConflictCenterCodecTest` 19 纯 JVM、`ConflictCenterTextTest` 7、`ConflictCenterDialogTest` 15、`ConflictCenterWiringTest` 3 纯 JVM —— 最后一类是**源码守卫**：接线整条就是 `if (…) show(…)`、**没有任何新字面量**，被删掉不会让任何测试变红、用户则永远看不到这个界面，故把「`showConflicts(result.reviews)` 在场且早于计数闸」钉在源码上）；**12 处变异逐处咬合 17 条红**（A1 `null` 决策误报 2 · A2 不可评审决策误报 1 · A3 删改冲突赢家说反 1 · A4「两端都没了」误报 1 · A5「云端改过」说成「本机改过」1 · A6 行序不再按 token 排 1 · A7 `null` 编成空串 2 · A8 截断了却不记 1 · A9 上限放宽一条 1 · A10 capped 提示不再出现 2 · A11 缺值画成空白 2 · A12 **接线被摘掉** 2），逐处自备份**逐字节还原**、收尾 `diff` 4 文件全 OK + `grep BITE` = 0；⚠️ 新增 **K23**（未 show 的 `AlertDialog` 里**没有 view** —— 读 `window.decorView` 的断言会**整批假绿**：`anySatisfy` 在空集合上直接失败，而 `noneSatisfy` 在空集合上**恒真**（把"没有警告"变成永真）；且无宿主 Activity 时 `Dialog.show()` 抛 `IllegalStateException` ⇒ 处置 = 把内容视图提成 `internal fun contentView(context, payload)` 单独断言，构建路径照真跑）；全量单测见 E13；**本轮不含 BY**，APK 沿用第二十轮 `debae437…b6e0`）；**v1.24 回填 ①f「交易列表紧凑／扩展视图」+ 借贷同号修复 + 批量写库异步化 + 模板账户模糊搜索**（① **借贷同号修复**（用户报：修改交易时借贷两侧被改成同一账户）：根因是「贷方 = 不是起始账户的那一条分录」——搜索结果点「修改」是以**贷方**账户打开表单的，于是「另一条」恰好是**借方**分录；且旧 `creditAccountConditions` **排除起始账户** ⇒ 真贷方账户根本不在下拉里、spinner 回落到首项（借方）。修复 = ⓐ「按 `Split.type` 取账户」收成纯函数 `TransactionSides`（`debitAccountUid` / `creditAccountUid`）；ⓑ「哪些账户可选」收成 `AccountConditions.selectable()` **借贷共用一份** WHERE（不再排除起始账户），下拉与模糊搜索选择器**同源**；ⓒ 保存前拒绝两侧同号（`toast_transaction_sides_must_differ`）—— 单式记账路径会**静默写出一对不平衡的分录**；② **批量写库异步化**（用户报：批量修改保存后卡住）：根因是 `N × addRecord` 在**主线程同步**跑，延迟随选中笔数增长。收成 `BulkActionRunner`（`withContext(IO)` 执行、`Main.immediate` 回结果），`SearchResultsViewModel.deleteTransactions/bulkUpdate` 改**异步 + 回调**，界面**先关 CAB 再提交**（大批量期间列表仍可用）；③ **模板借贷账户也支持模糊搜索**：`TransactionTemplateEditFragment` 两行各挂搜索按钮，复用 ①e 的 `AccountSearchDialogFragment`（**条件同源**）+ `FragmentResult`；④ **扩展视图（①f）**：列表在**紧凑视图**之外新增「扩展视图」模式 —— 一行内追加显示**账户 / 备注 / 编号 / 标签**四类可选字段，**与紧凑视图互斥**；⑤ 溢出菜单新增**二级菜单**「设置扩展显示」（`menu_extended_view` 嵌套 `<item>`）：`menu_toggle_extended`（checkable，仅双式记账时可用）+「管理扩展视图」；⑥ **可设置多套扩展视图**（新建 / **重命名** / **设为默认** / 删除，删默认时**交棒**给幸存者），每套独立勾选字段；空名与空字段被拒，坏载荷行**丢弃而不炸**；⑦ 存储是 **book preference 的单个字符串**（`id TAB name TAB field,field`，分隔符反斜杠转义；随 book 切换而切换），未配置时回退**全字段**默认视图；⑧ 顺手记下 **K24** 的三处夹具坑（CRLF 保持补丁工具 / Robolectric 测 fragment dialog result / 工程无 `kotlinx-coroutines-test` 时用 `Dispatchers.Default` + `CountDownLatch`）；⚠️ 本轮**首次给表单补「从贷方打开」回归**（`TransactionFormEditTest` 补例，`FormActivity` + `SELECTED_ACCOUNT_UID`）；**12 处变异逐处咬合 26 条红**（见环境文档 K24）；全量单测 **104 类 / 1628 例 / 0 失败 0 忽略**（见 E13；⚠️ 收尾生成报告被沙箱拦 ⇒ 权威数字取自 `index.html`）；**本轮含 BY**：debug APK `41,702,489 B`（39.77 MiB）/ sha256 `0ae9a2ea…a6315`，dex 硬核到 `ExtendedViews` / `ExtendedViewCodec` / `ExtendedRowContent` / `ExtendedViewStore` / `ExtendedViewsActivity` / `BulkActionRunner` / `TransactionSides` / `AccountConditions` / `menu_toggle_extended`，`resources.arsc`（UTF-8）硬核到 `扩展视图` / `设置扩展显示` / `使用扩展视图` / `设为默认` / `对方账户` / `借方账户与贷方账户不能相同` 与资源键 `title_extended_views` / `menu_extended_view_settings` / `toast_transaction_sides_must_differ`）；**v1.25 回填 ①g「批量写库单事务 + 扩展视图单选化 + 一级菜单 + 日期手工填写 + 搜索结果接报表」（第二十五轮）**（① **批量修改保存后卡住 / 数据没写入的真根因**（用户报的第二例）：第二十四轮把批量写挪到 IO 线程只解掉了「界面死锁」，剩下的是**每笔一次事务提交** —— `addRecord` / `deleteRecord` **各自开事务并提交**，N 笔 = N 次 fsync，屏幕能点但数据迟迟不落盘，观感与「挂住」完全一致。修复 = `SearchBulkWriter.delete` / `update` **整批包一个事务**（`beginTransaction` → `setTransactionSuccessful` → `endTransaction`，**但保留逐行 `try/catch`**：一行失败仍跳过、不整批回滚 —— 「一次提交」与「失败策略」是两件事）；并补两处与「卡住」同族的静默丢数据：**`EXTRA_UIDS` 随对话框结果回传**（界面**先关 CAB 再提交**，而关 CAB 会清空适配器的选择 ⇒ 回落到 `selectedUids()` 会拿到**空集** ⇒ 保存看起来「没反应」）+ 保存前「正在更新 N 笔…」提示；② **扩展视图改单选**：`ExtendedViewStore.select(id?)` 是**唯一写入路径**（`null` ⇒ `setEnabled(false)`；否则设默认 + 开模式），管理页列表**首行 = 「不使用扩展显示」**、勾选即生效、勾选即开关 —— 删掉「设为默认」这套第二步骤（两处状态最容易漂移）；③ **取消二级菜单**：`transactions_list_actions.xml` 里那层嵌套 `<menu>` 与 `menu_toggle_extended` 一起删掉，改为**一级菜单项「设置扩展显示」**，「定义显示哪些字段」改由管理页顶部的**字段勾选面板**承担（`ExtendedViewStore.updateFields(id, fields)`，**空集合被拒** —— 那是紧凑视图本就在做的事）；④ **修正扩展视图的显示逻辑**：`ExtendedRowContent.hasExtraLines`（`notes` / `number` / `tags` 任一有内容即可；**刻意忽略 account** —— 账户行由卡片自己的 secondary row 画，算进来会让「只勾账户」的视图撑开一条**可见的空行**）+ 列表容器可见性改为 `content?.hasExtraLines == true`（容器默认 `GONE`）；紧凑视图与扩展视图**互斥**改走 `select(null)`，并补上紧凑开关的 **book pref 持久化**（原先只存 `savedInstanceState`，重启即丢）；⑤ **所有日历加「手工填写」**：单选日期全走 `DatePickerDialogFragment` ⇒ 给它加 `BUTTON_NEUTRAL`（**listener 必须传 `null`**，否则 AlertDialog 自带的中性处理会**先把日历关掉**；`onShow` 时再绑）→ `ManualDateDialogFragment`；⚠️ **报表的「日期范围」是另一个控件**（`DateRangePickerDialogFragment` + `CalendarPickerView`）⇒ 「所有日期」若只改单选那处就是**半成品**，故另加 `ManualDateRangeDialogFragment`（两个 `20260129` 字段）+ 纯函数 `ManualDateRange.parse`（负责「结束不早于起始」）；顺手记下 `ReportsActivity` **实现**了 `DatePickerDialog.OnDateSetListener` 却**从来没有人 new 过那个 Dialog**（死代码）—— **数入口要按构造点数、不是按 implements 数**；⑥ **`20260129` 控件**：纯核心 `ManualDate`（`format` / `digitsOf`（过滤非数字 + 截 8 位 ⇒ 粘贴 `2026-01-29` 也得 `20260129`）/ `parse`（**`isLenient = false` 严格**，`20260230` **拒绝**而不是滚到 3 月）/ `startOfDay`）+ 对话框**输入即归一化**（`normalising` 防回环）+ **单击聚焦到末尾 / 双击全选**（`GestureDetector`；吞掉触摸意味着输入框不再自己放光标 —— 八位框里这两种手势就是全部交互）；⚠️ 丢掉前导零的形式（`2026/1/29` ⇒ 7 位）**拒绝而非猜**（`2026119` 是 1/19 还是 11/9 无从判断）；⑦ **搜索结果接报表**：新增 `ReportType.SEARCH_RESULT` + `SearchResultReportFragment` + 纯核心 `SearchReportLoader`（**复用 `fetchSearch(where)`** ⇒ 报表与结果列表对「命中」与「金额」的定义必须一致；金额 = **借方分录合计**，与 ①d 同口径；合计**按币种独立**、绝不相加）+ `ReportsActivity.showSearchResult(context, where)`（查询走 **intent extra** ⇒ 从导航栏直接打开报表仍落在普通总览；activity 已在栈上时走 `onNewIntent`）+ 搜索结果页溢出菜单入口；⚠️ **枚举顺序契约**：下拉用 `values()[position]` 索引，而 `getReportNames` 会过滤 `NONE`（总是）与 `SEARCH_RESULT`（没搜过时）⇒ **被过滤的项必须在末尾**，中间开洞会让它下面**每一项**都打开错报表；⚠️ `SearchReport` **必须继承 `ChartData<*>`**（`BaseReportFragment<D : ChartData<*>>` 的泛型上界，与 `BalanceSheet` 同形；不继承则编译挂在 **fragment 那一行**）；顺手修 zh 文案 `hint_manual_date_format` 的措辞（「年月日在后」→「依次为年月日」）；**14 处变异逐处咬合 14 条红**（m01 批量单事务 1 · m02 `select(null)` 不关模式 1 · m03 `updateFields` 收空集 1 · m04 `hasExtraLines` 恒真 1 · m05 宽松解析 1 · m06 无查询也提供「搜索结果」1 · m07 不过滤 `SEARCH_RESULT` 1 · m08 空数组不判空 1 · m09 单选日历按钮脱线 1 · m10 合计只留一个币种桶 1 · m11 金额折全部（含贷方）1 · m12 `select` 忽略实参 1 · m13 范围顺序不校验 1 · m14 范围日历按钮脱线 1），逐处**逐字节还原**（14 文件 sha256 全部 `byte-identical`）；⚠️ 本轮记下 **K25** 五坑（整批一次提交 / `BaseReportFragment` 的泛型上界 / 报表枚举顺序 / **同一类日期不止一个控件入口** / 源码守卫锚点要照抄真实串），并实测「**咬合跑到一半改源码 ⇒ 整轮结果作废**；强停按 K24 坑 2 逐处验回读，确有**一处**留在变异态」；全量单测 **111 类 / 1704 例 / 0 失败**（见 E13）；**本轮含 BY**：debug APK `41752098 B` / sha256 `f772c8221f5842d2abd85df2cc03c3566f00207c7c501ee0735e1421e6e42a32`，dex 硬核到 `ManualDate` / `ManualDateRange` / `ManualDateDialogFragment` / `ManualDateRangeDialogFragment` / `SearchReportLoader` / `SearchResultReportFragment` / `MenuFragment` 无关的 `ExtendedViewStore`（`select` / `updateFields`）与菜单项 `menu_extended_view_settings`，`resources.arsc`（UTF-8）硬核到 `手工填写` / `不使用扩展显示` / `设置扩展显示` / `搜索结果` / `起始` / `结束` 与资源键 `btn_manual_date` / `title_manual_date_range` / `menu_report_search_results`）；**v1.26 回填「删图表 + 批量保存 ANR 真根因 + 报表图型展示」（第二十六轮）**（① **删除搜索结果页的「图表」**：`SearchChartDialogFragment`（只会画一张按账户饼图的弹窗）+ `search_results_menu.xml` 里的 `menu_search_chart` + `SearchResultsFragment` 里那个分支 + `search_chart_title` / `search_chart_by_account` / `search_chart_empty` 三条字符串（en+zh）**一并删掉** —— 它的能力被本轮 ③ 的报表图型**完全覆盖**，留着就是两个入口各说一套；删完 `grep -rn SearchChartDialogFragment` 只剩一条**源码守卫**（守卫「这个文件不许再回来」）；② **批量修改保存后 ANR + 数据没写入的真根因**（用户报的第三例）：第二十四轮挪到 IO 线程、第二十五轮整批一个事务，**都还不够** —— 整批一个大事务意味着**写锁被整批持有**，与此同时列表 `notifyDataSetChanged()` 会**立刻重绑每一行**，而 `SearchResultViewHolder.bind()` 每行要跑 3~5 条**主线程**数据库查询（读分录 `buildModelInstance` / 读账户链 `getFullyQualifiedAccountName` / 读标签 `getTagNamesForTransaction`）⇒ 主线程读**饿在写锁后面** ⇒ ANR ⇒ 进程在事务中途被杀 ⇒ **整个事务回滚 = 一笔都没写** + 跳回首页。修复三件事：ⓐ `SearchBulkWriter.delete` / `update` 改**分块提交**（`uids.chunked(BATCH_SIZE = 50)`，**每块一个事务**）⇒ 写锁最长只被 50 笔持有；ⓑ 块间 `breathe()`（2 ms 暂停）**主动让出写锁**，给主线程的绑定查询留窗口；ⓒ `SearchResultsViewModel.search()` 里 `transactions.map { it.toSearchResultRow() }` **移出 `withContext(Dispatchers.Main)`**（逐行 DB 查询本来就不该在主线程做）；⚠️ **为什么不是「再包一个事务」**：R25 的「整批一个事务」恰恰**加大**了写锁持有时间，与本轮用户报的症状同源 —— **事务粒度是「锁持有时间」的旋钮，不是「原子性」的旋钮**，大批量场景要往小调；③ **报表按图表型展示**（用户报：搜索结果只有合计列表，「无法使用饼图/柱状图/折线图展示」）：报表页内加**「图型」下拉**（`Spinner`，四项 **表格 / 饼图 / 柱状图 / 折线图**，默认**表格**），选中即切换「表格滚动区」与「图表面板」的可见性；新增**纯 JVM** `SearchReportChart` 把行分桶：`byAccount`（按账户**严格分区**，按金额绝对值**降序**，无账户归入`UNCATEGORIZED` 桶，**带行数**）与 `byDate`（按 `LocalDate(row.dateMillis, DateTimeZone.forTimeZone(zone))` 分日，**最旧在前** ⇒ 折线图是时间轴而不是金额排序的锯齿）；三个图分别用 MPAndroidChart 的 `PieChart` / `BarChart` / `LineChart`，X 轴文字走 `IndexAxisValueFormatter`（账户名较长时 `labelRotationAngle = -30f`），柱/线用 `index.toFloat()` 作 X；⚠️ **多币种绝不同图相加**：`SearchReportLoader.charted()` 只挑**一个**币种作图画（`primaryCurrency` = **金额绝对值合计最大**的那个币种，LinkedHashMap + 行序保证同分时结果确定），其余币种**只留在合计表里**，并在图表上方用 `search_report_chart_note` **明说「图表只统计 %1$s，%2$s 仅见表格」**（把两种币种画进同一根柱子 = 一个**没有意义的数字**）；饼图/柱状图**丢弃零值桶**、折线图**保留**（断点会骗人）；空数据显示 `search_report_chart_empty`；`SearchReport.hasChart` 为 false 时不进图表分支；⚠️ **Kotlin 2.4 后端的 `SyntheticAccessorLowering` 不允许跨文件访问基类的 `protected companion val`** —— 复用 `BaseReportFragment` 的 `COLORS` 会让编译器 `BackendException` 崩在**另一个文件**上（报 `should not attempt to modify other files!`），故本 fragment 自带**局部 `PALETTE`**（与其它报表 fragment 同形）；**17 处变异逐处咬合 17 条红**（m01 整批一个大事务 · m02 删除不让出写锁 · m03 摘要回到主线程 · m04 折线按金额排 · m05 分桶忽略时区 · m06 账户桶升序 · m07 账户桶丢行数 · m08 图表折多个币种 · m09 图表挑最小币种 · m10 `hasChart` 恒真 · m11 默认图型改饼图 · m12 资源数组顺序对调 · m13 折线图画账户 · m14 被删的图表弹窗文件又回来 · m15 图表菜单项又回来 · m16 块大小≈整批 · m17 分块丢尾块），逐处**逐字节还原**（17 文件 sha256 全部 `byte-identical` + 1 处 `absent`）；⚠️ 本轮记下 **K26** 三坑（**事务粒度 = 写锁持有时间**，不是原子性旋钮 · Kotlin 2.4 跨文件访问基类 `protected companion` 会崩后端 · 多币种报表必须单币种作图并把其余币种**说出来**）；全量单测 **112 类 / 1721 例 / 0 失败 0 错误 0 跳过**（见 E13）；**本轮含 BY**：debug APK `44852969 B` / sha256 `34b031b3e3eee4047932b689b37851fa0a79a4841e1d5f5c139b154cb7f24605`，dex 硬核到 `SearchReportChart` / `SearchReportLoader` / `SearchResultReportFragment` / `SearchBulkWriter`（`chunked` / `breathe` / `BATCH_SIZE`）/ `SearchResultsViewModel`，并硬核到 `menu_search_chart` **缺席**；`resources.arsc`（UTF-8）硬核到 `图型` / `表格` / `饼图` / `柱状图` / `折线图` / `没有可绘制图表的数据` / `图表只统计` / `按账户（` / `按日期（` 与资源键 `label_search_report_chart_type` / `search_report_chart_types` / `search_report_chart_by_account` / `search_report_chart_by_date` / `search_report_chart_note`）
> 方法：结论优先来自**真跑**；凡未跑的一律标注置信度与验证方式

---

## 1. 项目定位与红线

### 1.1 定位

GnuCash 桌面的 **Android 伴侣记账 App**。用户在手机端快速记账，之后把数据导入桌面 GnuCash 做正式账务。本次扩展的目标是把它从一个"能记账的伴侣"升级为**日常主力记账入口**：多来源自动录入（账单导入 / 截图识别 / 语音）、可统计分析（标签）、可多设备协同（双向同步）。

### 1.2 红线（不可违反）

| # | 红线 | 说明 |
|---|---|---|
| R1 | **桌面 GnuCash 的往返兼容** | 导出的 XML/`.xac` 必须仍能被桌面打开；任何扩展字段要么用 GnuCash 既有语义（KVP slot / `#tag` 文本），要么不得写入导出物 |
| R2 | **面向中国区，无 GMS 为前提** | 不得把 Google Play services / Firebase / ML Kit 作为主干能力；`google-services.json` 不应成为构建必需 |
| R3 | **金额不得错** | 所有金额走 `BigDecimal`；ITN（口语数字→数值）与账单导入的金额必须可断言、可对拍 |
| R4 | **许可合规** | 主项目 Apache-2.0；引入的模型/库许可必须兼容且可商用（OCR/ASR 优先 Apache-2.0 / MIT） |
| R5 | **不做静默失败** | 不允许"开关摆了不生效""日志全绿数据不对"；每个新能力都要有可观测判据 |
| R6 | **不引入 C 工具链依赖给纯逻辑层** | 业务逻辑尽量留在 Kotlin/JVM 侧以便单测；native 只用于 OCR/ASR 等必要处 |
| R7 | **隐私** | 账单属敏感金融数据；云能力一律**默认关闭**，上传前必须显式确认 |

---

## 2. 现状审计（2026-10-11 实测，非阅读推测）

### 2.1 技术栈与构建

| 维度 | 实测值 | 备注 |
|---|---|---|
| 语言 / 构建 | Kotlin `2.4.20` + AGP `9.4.1` + **Gradle `9.7.1`**（wrapper） | 全 Kotlin DSL |
| SDK | minSdk **26** / targetSdk **37** / compileSdk **37** | `compileSdk { version = release(37) }`（AGP 9 新 DSL） |
| JVM | Java **11**（source/target/jvmTarget 一致） | JDK 17 运行 |
| Gradle 特性 | `org.gradle.configuration-cache=true`、`org.gradle.caching=true`、`android.enableJetifier=true` | 配置缓存已启用（实测 `Configuration cache entry stored`） |
| Gradle 模块 | **2 个**：`:app`、`:po`（翻译） | 无多模块/无插件基建 |
| `buildSrc` | 仅 `GitCommitValueSource.kt`（为 development flavor 生成版本后缀） | |
| 依赖源 | `google()` / `mavenCentral()` / `jitpack.io`，且 `FAIL_ON_PROJECT_REPOS` | 镜像只能注入 **settings 级**（用户级 `~/.gradle/init.d/mirrors.gradle` 已合规） |
| 版本 | 代码内 `3.3.1`（versionCode 由 3/3/1/0 拼出）；`CHANGELOG.md` 停在 2018 年的 2.4.0 | ⚠️ CHANGELOG 已失效，不可作为版本依据 |

### 2.2 代码结构与能力盘点

源码根包 `org.gnucash.android`，子包：

| 包 | 内容 | 对我们的价值 |
|---|---|---|
| `db/` | `DatabaseSchema` / `DatabaseHelper`（手写 SQLite）/ `adapter/`（**11 个 DAO**） | 所有数据改动都落在这里 |
| `export/` | `Exporter`/`ExporterFactory`/`ExportFormat`/`ExportTarget`/`ExportParams`；`csv/`（账户+交易）、`ofx/`、`qif/`、`sql/`、`xml/`；`DropboxHelper.kt` | 同步与加密的挂载点 |
| `importer/` | `Importer`/`ImporterFactory`/`ImportAsyncTask`；`sql/SqliteImporter`、`xml/GncXmlImporter` + `GncXmlHandler`/`CommoditiesXmlHandler`/`AccountsTemplate` | **只有 SQL/XML 导入**，账单导入是纯新增 |
| `gnc/` | GnuCash 领域模型（XML/桌面语义） | R1 往返兼容的参照实现 |
| `net/` | **仅 `TrustExt.kt`**（TLS 信任） | WebDAV 客户端不在这里 |
| `service/` | `ScheduledActionService.kt` | 定时交易 |
| `work/` | `ActionWorker.kt`、`BackupWorker.kt`（WorkManager） | 定时备份/动作 |
| `receivers/` | `TransactionRecorder`（INSERT intent 收单）、`AccountCreator`、`TransactionAppWidgetProvider` | 外部录入入口的现成范式 |
| `ui/` | `account` / `transaction` / `report` / `search` / `export` / `settings` / `budget` / `price` / `homescreen` / `wizard` / `passcode` / `common` | Compose 与 ViewBinding 混用 |
| `inputmethodservice/` | `CalculatorKeyboard*` —— **是计算器自绘键盘，不是系统输入法** | 与"语音/悬浮球"无关 |
| `math/`、`quote/`、`lang/`、`util/`、`compose/` | 表达式计算、行情、i18n、工具、Compose 公共件 | |

### 2.3 数据层（现状）

- `DatabaseSchema.DATABASE_VERSION = 34`（本节是**开工前**的审计快照 = 30；另有独立的 `BOOK_DATABASE_VERSION = 1`）。已落地：`30→31 search_templates`、`31→32 tags`/`transaction_tags`、`32→33 sync_state`/`tombstones`、`33→34 sync_state.base_etag`/`sync_state.pending_since`（②-v2 的决策内核 + 网络层 + 动作层 + 编排层 + **真实本地 store（账号/交易 + 分录）**已落地；**②-v1 加密备份的容器核心 + 远端编排 + WebDAV 连接校验（`WebDavConnection`）+ 恢复载荷守卫（`BookPayload`）**也已落地；**UI（冲突中心 / 同步设置页）未做**，见 §5② 进度块）。
- 表：`books` / `accounts` / `transactions` / `splits` / `scheduled_actions` / `commodities` / `prices` / `budgets` / `budget_amounts` / `recurrences`。
- **（审计时）没有任何标签表 / 标签列** ⇒ 已在 ⑧ 的第一轮落地为 `tags` + `transaction_tags`（见 §5⑧ 进度块）。
- 已有模板语义：`accounts.is_template`、`transactions.is_template`、`commodities.is_template`，以及 `books.root_template_guid`。
- 有 `SplitsDbAdapter`（**split 是一等实体，且有自己的 GUID**）—— 这对"记录级三路合并"是关键利好。

### 2.4 搜索（现状，2026-10-11 读源码实测）

`ui/search/SearchCriteria.kt` 是一个 **sealed class**，当前 7 个分支：

```
Description · Note · Memo · Number · Date · Numeric · Account
```

配套：`SearchForm.kt` / `SearchFormFragment.kt` / `SearchFormViewModel.kt`。
比较算子已定义：`ComparisonType(All/Any/None)`、`StringCompare(Contains/Equals)`、`Compare`（6 种，注释里对应 QOF 比较码）、`NumericMatch(HasDebitsOrCredits/HasCredits/HasDebits)`。

**实测到的四个能力缺口（决定了 ① 的改造量）：**

| # | 缺口 | 证据（源码位置） |
|---|---|---|
| S1 | **"与/或"是全局唯一一个开关**，不能按字段各设，更不能在一句里混用 | `SearchForm.comparisonType: ComparisonType` 是**单一字段**；`buildSQL()` 用 `compareAll = comparisonType == All` 决定整条 WHERE 用 AND 还是 OR |
| S2 | **一个字段只能填一个关键词** | `SearchCriteria.Description/Note/Memo/Number` 只有 `value: String?` 单值；多关键词必须手工加多条条件，且受限于 S1 只能全 AND 或全 OR |
| S3 | **完全没有标签条件** | 无 `SearchCriteria.Tag`；且底层**无标签表**（见 2.3）→ 多标签检索**依赖 ⑧** |
| S4 | **没有"搜索模板"概念** | 表单条件**不落盘**（每次打开是空的）；`SearchFormFragment` 无保存/载入入口 |

> 🐛 **顺带发现一个"开关摆了不生效"的潜在缺陷（触红线 R5）**：`ComparisonType.None` 这个枚举**定义了但没实现** —— `buildSQL()` 里 `compareAll = (comparisonType == All)`，于是 `None` 落进 `else` 分支，**行为等同 `Any`（OR）**。即：用户若选到 `None` 语义，结果会**静默按 OR 处理**、无任何报错。① 的改造必须把它一并收口（见 §5①）。

→ 结论：**架构"可扩展"（sealed class 好加分支），但"与/或语义"与"多值"两块是结构性缺失，不是加个字段能糊上的**；且查询构造目前**散在 `SearchForm` 内部**，需先收口成独立的 QueryBuilder 才能安全承载新语义。

### 2.5 同步 / 备份（现状）—— **上一轮的"空壳"判断需要更正**

| 事实 | 实测 |
|---|---|
| `ExportTarget` | `URI` / `DROPBOX` / `OWNCLOUD` / `SHARING` / `SD_CARD` |
| `ExportFormat` | `CSVA` / `CSVT` / `OFX` / `QIF` / `SQLITE` / `XML` |
| OWNCLOUD **不是空壳** | `ExportAsyncTask`、`Exporter`、`ui/export/ExportFormFragment`、`ui/settings/BackupPreferenceFragment`、`ui/settings/dialog/OwnCloudDialogFragment.kt`、`ui/settings/OwnCloudPreferences.kt` 都有实现；依赖 `nextcloud 2.22.1` + `apache-http 3.1`（`OwnCloudClient`） |
| 但**语义仍是单向** | 只有"导出/备份到目标"，**没有**远端列举、拉取、差异比对、双向合并 |
| `net/` 只有一个文件 | 说明没有通用的 WebDAV/HTTP 抽象层，WebDAV 逻辑与 OwnCloud SDK 耦合 |

→ 结论：**"能推上去"已经有了；"能同步回来并合并"完全没有**。本次要做的是后者，且要**与 OwnCloud 解耦出通用 WebDAV 层**。

> **2026-10-11 第五轮补充（KFCSBYTX）—— 备份设置新增「导入备份」入口**
>
> 需求：在「备份设置」里能**用文件浏览器选一个备份文件导入**，支持 `.gnca` 与 `.gnucash`。
>
> | 项 | 落地 |
> |---|---|
> | 入口 | `res/xml/fragment_backup_preferences.xml` 新增 `Preference`（key `key_import_backup_file`，位于"从备份恢复…"之后），图标 `ic_import_backup` |
> | 选文件 | `BackupPreferenceFragment#importBackup` → `DocumentExt.chooseContent()`（`ACTION_GET_CONTENT` + `*/*` + `CATEGORY_OPENABLE`），即**系统文件浏览器** |
> | 校验 | 纯函数 `util/BackupFileFormat.isSupportedBackupFileName()`：大小写不敏感；接受 `.gnca`/`.gnucash` 及 `.gnca.gz`/`.gnca.zip`/`.gnucash.gz`/`.gnucash.zip`；拒绝空名、无扩展名、仅含受支持片段（`gnucash.gnca.bak`）、名为 `.gnca` 的隐藏文件 |
> | 失败提示 | 扩展名不符 → 弹窗「不支持的文件 / 支持的格式：`.gnca / .gnucash`」（**在导入开始前拦下**，而不是等导入报错） |
> | 确认 | 合规 → 弹「确认导入备份」（导入必然**新建账簿**）→ `ImportAsyncTask(activity, shouldBackupForImport(activity)).execute(uri)`，复用既有导入链路（`ImporterFactory` 按魔数识别 gzip/zip/SQLite/XML） |
>
> **两条刻意取舍**
> 1. **不做 MIME 过滤**：`.gnca` 没有注册 MIME 类型；一旦按 MIME 过滤，文件浏览器会把用户真正要选的文件**藏起来/置灰**（同族坑见 `android-open-file-forced-chooser`）。所以放开 `*/*`，改在**选完之后按文件名校验**，失败给可操作提示。
> 2. **不新增第二套导入实现**：走既有 `ImportAsyncTask`，因此"导入前先备份当前账簿"这个既有开关（`key_import_book_backup`）自动生效，行为与 FirstRunWizard / AccountPreferences 的导入一致。

### 2.6 清单与权限（现状）

`AndroidManifest.xml` 实际权限只有 4 条：`WAKE_LOCK`、`RECEIVE_BOOT_COMPLETED`、`INTERNET`、`ACCESS_NETWORK_STATE`（另有 2 条自定义 `CREATE_ACCOUNT` / `RECORD_TRANSACTION`）。

- **没有任何 `<service>` 声明**（连 `ScheduledActionService` 也没在清单里声明——需在第 P0 期核实其运行方式）。
- **没有**悬浮窗 / 无障碍 / 截屏 / 录音 / 通知 / 读取图片 相关权限。
- `res/xml/` 有 `transaction_appwidget_info.xml`（AppWidget 在），**没有 `shortcuts.xml`**（动态快捷方式尚未配置）。

### 2.7 测试现状

- 单测 **42 个文件**（Robolectric `4.16.1` + JUnit `4.13.2` + AssertJ `3.27.7`）。
- 仪器测试 **19 个文件**（Espresso `3.7.0` + UiAutomator `2.4.0` + AndroidX Test）。
- `instrumentation-test.sh` + GitHub Actions（`ci-build.yml` / `nightly-build.yml`）。
- ⚠️ **Hilt 已声明未启用**：版本目录有 `hilt 2.60.1` + `ksp 2.3.8`，但 `app/build.gradle.kts` **没有 apply `ksp`、没有 apply hilt 插件**，也没有任何 `@HiltAndroidApp` → 现状是手工单例。

### 2.8 现状结论摘要

| 判断 | 置信度 |
|---|---|
| 数据层是"手写 SQLite + 11 个 DAO"，没有 ID 生成/迁移框架之外的基建 | 高（已读源码） |
| 搜索"框架在、字段少"，扩展成本主要在**查询下推**而非 UI | 高 |
| WebDAV 有"推"无"同步"，需要新建通用层 + 三路合并 | 高 |
| 模板能力**底层已具备**，缺 UI 与"一键套用" | 高 |
| 悬浮球/OCR/语音/分享图片 是**纯新增能力**，连权限都没有 | 高（已读清单） |
| Hilt 接入会**同时影响所有现有页面**（无 `@AndroidEntryPoint` 基类） | 中（未跑；需在 P0 试接后评估存量改造量） |

---

## 3. 环境与构建基线（2026-10-11 实测）

| # | 项 | 结论 |
|---|---|---|
| E1 | Gradle | **9.7.1 可用**（已安装并验证）。旧结论"本机 Gradle 9.x 的 Kotlin DSL 编译是坏的"**已证伪**（详见《Gradle9-KotlinDSL-环境重建与验证.md》）。⚠️ 本机缓存的 `9.3.1` 实例在其深路径下不可用，本项目**不要用它** |
| E2 | 仓库配置 | `./gradlew :app:tasks` → ✅ `BUILD SUCCESSFUL in 2m48s`（AGP 9.4.1 / Kotlin 2.4.20 / Compose / 全部依赖解析成功） |
| E3 | Z 盘 | **本地 SATA SSD**（`INTEL SSDSC2BW120A4`，`DriveType=3`）。原"网络映射盘"表述已勘误；`mmap+close` 实测正常，"网络盘不支持 mmap"不成立 |
| E4 | `google-services.json` | 配置期**不需要**（development flavor 的 `useGoogleGcm=false` + `afterEvaluate` 禁任务生效）。**assemble 也不需要 —— 见 E12 / V1** |
| E5 | SDK 组件 | platforms 到 `android-37.0` ✅；**build-tools 最高 `36.0.0`**，而 compileSdk=37 → build-tools 37.x 是否必需见 V2 |
| E6 | JDK | `D:\jdk-17\jdk-17.0.20.1+1`（Temurin 17）✅ |
| E7 | 模拟器 | 只支持 **x86_64** 镜像；现成 AVD：`mx_atd33`(API33/x86_64/aosp_atd)、`Medium_Phone_API_36.0`；加速器 WHPX 可用 → **所有 native 依赖必须出 x86_64** |
| E8 | 构建位置 | **必须在会话沙箱外**跑 —— ✅ **2026-10-11 实测确认**：沙箱内 `assembleDevelopmentDebug` 跑到 47 个任务后失败，报 `AccessDeniedException`（`app\build\intermediates`、`~\.gradle\caches\9.7.1\transforms`、`%TEMP%` 写/删均被拒）→ **非代码问题，是沙箱写盘拦截** |
| E9 | Gradle 10 前瞻 | 9.7.1 已告警 `Deprecated Gradle features … incompatible with Gradle 10` → 升级前需 `--warning-mode all` 逐条清理 |
| E10 | **单测的 JVM** | ★ **必须 JDK 21**（`D:\jdk-21\jdk-21.0.12.1+1`）。`GnuCashTest` 的 `@Config(sdk=[26,36])` + Robolectric 4.16.1 ⇒ SDK 36 沙箱要求 **Java ≥ 21**；用 JDK 17 会**全量**在类加载阶段挂（`Failed to create a Robolectric sandbox: Android SDK 36 requires Java 21`）。仓库 CI 亦为 JDK 21。详见《Gradle9…验证.md》§5.4.1 |
| E11 | Robolectric 依赖源 | `android-all-instrumented` 经 **Maven Central**（不走国内镜像）拉取，落 `~/.m2/repository/org/robolectric/`；**首次全量实测 31 分钟**，缓存后增量 ~4–12 分钟 |
| E12 | **出包基线（2026-10-12 实测）** | `:app:assembleDevelopmentDebug` → ✅ `BUILD SUCCESSFUL`（EXIT=0，**无 `google-services.json`**），产物 `app/build/outputs/apk/development/debug/app-development-debug.apk`（**第十四轮重建**：**41,372,674 B / 39.46 MiB**，sha256 `934d9d72f39bf46093339bdccf8e8a4948ecf9f445dc8523aa1141efe6bd6946`，2026-10-12 12:21）。⚠️ **本轮首次以 `--rerun-tasks --no-build-cache` 干净重建** ⇒ 体积较第十一轮的 44,182,697 B（42.1 MiB）**回落 2.68 MiB**；干净结果与 **V12 首建的 39.3 MiB** 同量级（+ 九轮代码增量）⇒ 此前那个 44.18 MB 更可能是**增量/缓存态**产物。**判产物仍只认 sha256 / mtime / dex 内容**（本轮 dex 内硬核到 `BEGIN GNUCASH POCKET BACKUP` / `PBKDF2WithHmacSHA256` / `AES/GCM/NoPadding`，K16）。⚠️ `:app:assembleDevelopmentRelease` → ❌ `validateSigningDevelopmentRelease FAILED: Keystore file not set for signing config release` ⇒ **出 release 包前必须先配签名**（未做：涉及密钥，需你决定用哪个 keystore） |
| E13 | 全量单测基线 | JDK 21 + 沙箱外：**114 个测试类 / 1791 个用例 / 0 失败 0 错误 0 跳过**（**第二十七轮** R27「②-v3 冲突解决动作（保留本机／采用云端）」—— 新表 `sync_resolutions`（schema **35→36**、唯一索引 `(record_type, record_key)` REPLACE 语义）+ 纯词汇 `sync/ConflictResolution.kt`（`ResolveSide` = **边**）+ `SyncRoundPlanner.forcedBy` + `SyncRound.commitResolutions`（`resolutionsApplied ∩ landed` 才清）+ 写路径单点 `ConflictResolutionStore.remember`（返回 `Boolean`）+ 冲突中心两个按钮；⚠️ 沙箱下由**分批前台 `--tests` 拼装**并逐批归档 XML（sync 23 类 / 498 例、db 22 类、ui.settings 9 类 + 其余包，归档 `_r27_full_xml/`）；**114 个测试类 / 1791 个用例 / 0 失败 0 错误 0 跳过**；11 处变异逐处咬合 11 条红、逐处字节还原；见环境文档 **K27**）（首轮 505 → 二 533 → 三 614 → 四 642 → 五 672 → 六 738 → 七 792 → 八 837 → 九 906 → 十 960 → 十一 1023 → 十二 1031 → 十三 1035 → 十四 1075 → 十五 1125 → 十六 1173 → **十七/十八 1287** → **十九 1353** → **二十 1357**（bug 修复轮，1h 3m 55s）→ **二十一 1430** → **二十二 1469** → **二十三 1550** → **二十四 1628** → **111 1704**（**第二十五轮** R25 批量单事务 + 扩展视图单选化 + 一级菜单 + 日期手工填写 + 搜索结果接报表；`--rerun-tasks --no-build-cache`，**111 个测试类**；14 处变异逐处咬合 14 条红、逐处字节还原；见环境文档 **K25**）（**第二十六轮** R26 删图表 + 批量保存 ANR 真根因（分块提交 + 让出写锁）+ 报表图型展示；⚠️ **沙箱拦后台 ⇒ 全量单测改由 10 批前台过滤跑拼装**（逐批归档 `TEST-*.xml` 到 `app/build/evidence_r26/`，每批日志含 `> Task :app:testDevelopmentDebugUnitTest` 且**不带 `UP-TO-DATE`**；与 R25 归档 index 逐类对照 ⇒ **+1 类 / +17 例、无类消失**），**112 个测试类 / 1721 个用例 / 0 失败 0 错误 0 跳过**；17 处变异逐处咬合 17 条红、逐处字节还原；见环境文档 **K26**）（**第二十四轮** ①f 交易列表扩展视图 + 借贷同号修复 + 批量写库异步化 + 模板账户模糊搜索；`--rerun-tasks --no-build-cache` 实测 **1h 24m 2s**、测试本身 **1h 16m 51.27s**、`37 actionable tasks: 37 executed`；1550→1628 共 +78 例、**104 个测试类**（+10 新类）；12 处变异逐处咬合 26 条红；⚠️ 本轮收尾生成报告被沙箱拦（`Could not generate test report`，仅 7 个类页写入被拒）⇒ 任务判 `EXIT=1`、XML 未落盘，**权威数字取自 `reports/tests/testDevelopmentDebugUnitTest/index.html`（1628 / 0 / 0 / 100%，表格 104 行求和一致）**，该 index 已另存 `app/build/evidence_r24/full1628_index.html`；见环境文档 **K24**）（**第二十三轮** 冲突中心（只读）+ 接线；`--rerun-tasks --no-build-cache` 实测 **1h 16m 11s**、测试本身 1h 14m 0.37s、`37 actionable tasks: 37 executed`；1469→1550 共 +81 例、**94 个测试类**；12 处变异逐处咬合 17 条红；见环境文档 **K23**）（**第二十二轮** `--rerun-tasks --no-build-cache` 实测 **1h 8m 56s**、测试本身 1h 6m 47.52s、`37 actionable tasks: 37 executed`，新增 3 类 / 34 例 + 既有 2 类各补用例共 +39 例，10 处变异逐处咬合 31 条红；见环境文档 **K22**）（第二十一轮 1h 4m 15s、新增 6 类 / 73 例，5 处变异 → 7 条红；见环境文档 **K21**）（第十九轮 1h 9m 55s，`--rerun-tasks --no-build-cache`，11 处变异 / 2 批咬合 17 条红；见环境文档 K19）（第十七轮的全量跑到关机被截断，故十七、十八合并成这一次基线；第十八轮 `--rerun-tasks --no-build-cache` 实测 **1h9m19s**，其中测试本身 1h3m15.61s；⚠️ 本轮 `Test` 任务的 **HTML 报告**收尾被沙箱拦掉一个文件（`WebDavXmlTest/a-truncated-document-is-malformed.html`）⇒ 任务因此报 FAILED，**但测试全跑完且 0 失败**；因 XML 报告与 HTML 报告同在收尾步骤、被一并中断，本轮权威数字取自 Gradle 自己生成的 `reports/tests/testDevelopmentDebugUnitTest/index.html` 汇总（1287 tests / 0 failures / 0 skipped / 75 个类页全 100%），该 index 已另存 `app/build/full1287_index.html`）；第八轮 31m51s、第九轮 33m41s、第十轮 单测+出包合成一条 35m31s、第十一轮 全量单测 45m13s + 出包增量 3m48s、第十二轮 53m38s、第十三轮 56m47s、第十四轮 单测+出包合成一条 50m33s、**第十五轮 全量单测 50m23s**（均 `--rerun-tasks --no-build-cache` 强制重跑；⚠️ 单次时长随负载在 31~69min 波动，别拿它当基线。⚠️ 第十五轮另立一条便宜跑法：**窄过滤 `--tests` 只跑受影响类**（`BookPayloadTest`+`RemoteBackupTest` 约 1.5~2min、单类约 45s~2min），咬合验证全程用它，全量只在最后跑一次） |

---

## 4. 目标架构

### 4.1 分层原则（先说最容易被做错的）

**能力型 vs 数据/规则型，二者插件化程度不同：**

- **能力型**（悬浮球、无障碍、截屏、前台服务、WebDAV 传输、OCR/ASR 推理、IME）**必须宿主内实现**；脚本/插件只能"调用"。
- **数据/规则型**（账单解析器、标签规则、模板、报表视图、搜索字段）**才是可脚本化的对象**。

按此把架构分四层，本次**只做 L0 + L3**（决策 7）：

```
L0  内部 SPI + 接口注册（编译期）        ← 本次必做，是十项功能的地基
L1  Dynamic Feature Modules（按需下发）  ← 不做
L2  运行时 dex 插件（DexClassLoader）    ← 不做（Android 9+ 隐藏 API/政策/签名校验风险高）
L3  用户脚本层（Lua/QuickJS）            ← 本次做，只对"数据/规则型"开放
```

### 4.2 架构图

```mermaid
flowchart TD
    H["Hilt DI 容器（决策 8）"] --> SPI["L0 SPI 注册表<br/>Importer · SyncBackend · OcrEngine · AsrEngine<br/>SearchField · ReportView · TxnTemplate"]
    SPI --> F1["①全字段搜索"]
    SPI --> F2["②WebDAV + 口令加密 + GUID 三路合并 + 冲突中心"]
    SPI --> F3["③支付宝/微信/云闪付 账单导入"]
    SPI --> F4["④悬浮球 + 截屏 + 离线 OCR（能力型：宿主实现）"]
    SPI --> F5["⑤分享图片记账（复用 ④ 的 OCR 管线）"]
    SPI --> F6["⑥记账模板（复用既有 is_template）"]
    SPI --> F7["⑦语音记账（离线 ASR + NLU）"]
    SPI --> F8["⑧交易标签 + 回写 #tag + 按标签统计"]
    SPI --> F9["⑨桌面动态快捷方式"]
    L3["L3 用户脚本层<br/>导入解析 · 标签规则 · 模板 · 报表"] --> SPI
    H --> SVC["新前台服务层<br/>悬浮球 / 录音 / 同步（Manifest 现无任何 service）"]
```

### 4.3 与既有代码的接口约定

| 新增组件 | 接入既有代码的位置 |
|---|---|
| `Importer` SPI | 扩展 `importer/ImporterFactory`（现只有 sql/xml） |
| `SyncBackend` SPI | 与 `export/ExportTarget` 并列，但**不走 Exporter**（Exporter 是单向产出文件） |
| 数据访问 | 一律经 `db/adapter/*DbAdapter`（11 个），**禁止绕过 DAO 直接 SQLite**，否则三路合并与墓碑逻辑会漏 |
| 模板 | 复用 `transactions.is_template` + `books.root_template_guid`，只加 UI 与套用逻辑 |
| 报表 | 在 `ui/report/` 下加"按标签"维度（现为 pie/bar/line/balance-sheet） |

---

## 5. 十项功能设计

> 每项格式：**现状 → 方案 → 数据/接口改动 → 风险 → 量级**。量级：S（≈1 周内）/ M（≈2–4 周）/ L（>1 月）。

### ① 增强搜索：全字段 + 多关键词与/或 + 多标签 + 搜索模板

> 拆两块：**①a（P1，不依赖标签）** = 全字段 + 多关键词与/或 + 搜索模板；**①b（P2，依赖 ⑧ 的标签表）** = 多标签检索。

- **现状**：见 §2.4 —— 四个缺口 S1–S4（与/或只能全局一刀切、单字段单关键词、无标签、无模板）+ 一个 `ComparisonType.None` 未实现的静默缺陷。

- **目标交互**：
  1. **单字段多关键词**：输入 `打车 地铁` ⇒ 词间默认 **AND**（须同时含两词）；输入 `打车 | 地铁`（或 UI 切"任一"）⇒ **OR**。支持引号短语 `"共享单车"`、全角/半角空格与中英标点混排。
  2. **字段内 vs 字段间各有一套与/或**：例如 `描述 AND 含(打车,加班)` **且** `标签 ANY(交通,差旅)` —— 两层布尔是被分别控制的，不再共用一个开关（这是 S1 的正面解）。
  3. **标签条件**：`标签` 支持 **ALL / ANY / NONE**（同时含全部 / 任一 / 都不含）。
  4. **搜索模板**：把当前表单**命名保存**、列表管理（重命名/删除/排序）、一键套用；模板可导出为文本便于分享。

- **方案**：
  1. **收口 QueryBuilder**：把 `SearchForm.buildSQL()` 从 companion 抽成独立 `SearchQueryBuilder`，作为 sealed class → SQL 的**唯一出口**；顺手修掉 `ComparisonType.None`：`None` ⇒ 该条件整体取反 `NOT (...)`。
  2. **多关键词**：字符串类条件（Description/Note/Memo/Number）由单值 `value: String?` 泛化为 `terms: List<String>` + `op: ComparisonType`；**保留 `value` 的兼容构造**（`value` 非空且 `terms` 为空时退化成单词条件，既有调用零回归）。SQL：`terms` 逐个 `LIKE`，词间按 `op` 拼 AND/OR。
  3. **新增条件分支**：`SplitMemo`、`AccountName`、`AccountCode`、`Commodity`、`ReconciledState`、`IsExported`、`IsTemplate`、`Amount`（区间 + 借/贷方向）、`TransactionId`、**`Tag(tags, op)`**、**`AnyField`**（对 description/memo/note/number 做 OR LIKE 的"任意字段包含"，即"全字段搜索"）。
  4. **标签条件的 SQL**：`Tag` 用**相关子查询**表达（从而仍是纯 `WHERE` 片段）——`ANY` = `EXISTS(SELECT 1 FROM transaction_tags …)`；`ALL` = `(SELECT COUNT(DISTINCT tag) …) = n`；`NONE` = `NOT EXISTS(…)`。**①b 的"能查"不依赖 ⑧ 的表结构改动之外的东西，但"有数据可查"依赖 ⑧ 建表**（依赖见 §11）。

     > ⚠️ **架构约束（读源码确认，决定 ①b 的真实成本）**：搜索条件现在是**裸 `WHERE` 片段**，由 `SearchFormViewModel` 经 `EXTRA_FORM` 传进 `SearchResultsFragment`，最终交给 `TransactionsDbAdapter.fetchSearch(where)`。而该函数的 FROM 是**写死**的：
     > ```sql
     > SELECT DISTINCT t.* FROM transactions t
     >   INNER JOIN splits s1 ON t.uid = s1.transaction_uid
     >   INNER JOIN splits s2 ON t.uid = s2.transaction_uid
     > WHERE (s1._id < s2._id) AND <where>
     > ```
     > → 约束是**"条件必须能被表达成 WHERE 片段"**（否则就得改 `fetchSearch` 的 FROM，牵动 `SearchResultsFragment` → DAO 整链）。**多关键词 / 全字段 OR LIKE / 金额日期区间天然满足**；**`Tag` 也满足**（用相关子查询，见上）；真正会突破该约束的是"需要聚合后排序/分组"的报表类条件 —— 那些走 ⑩ 的 `ReportView`，不走搜索。这是 ① 只动 WHERE 侧、不动 adapter 的依据。
     >
     > 📌 **勘误（v1.1 内自我更正）**：初稿曾写"`Tag` 需 `GROUP BY … HAVING`，光改 WHERE 表达不了、必须改 FROM" —— **该判断过强、不成立**。`EXISTS` / 标量子查询已足以在 WHERE 内表达 ALL/ANY/NONE 三种标签语义，无须触碰 `fetchSearch`。
  5. **搜索模板持久化**：新表 `search_templates(id, name, form_json, sort_order, created_at, updated_at)`；`form_json` 存**整个 `SearchForm` 的序列化**（含 `form_version` 便于演进）。条件与算子形态的演化都收敛进 JSON，**表结构保持稳定**（同 ⑥ 的"只加不改"思路）。
  6. **性能与索引（诚实说明）**：`LIKE '%x%'` **前导通配符走不了普通索引** ⇒ 补 B-tree 索引对"任意字段包含"**没有帮助**。两选项：
     - **选项 A（默认，先做）**：维持 `LIKE`，正确性优先；数据量到万级交易再实测。
     - **选项 B（性能后备）**：为 `description/memo/note` 建 **FTS4/5 外部内容虚拟表**，把多关键词/全字段检索下推到 FTS（真正能走索引），代价是建副表 + 触发器同步 + 迁移复杂度。
     → **默认 A，B 记为后备**（不为性能先引一套同步触发器，避免制造新的"不一致面"）。

- **数据/接口改动**：
  - 新增表 `search_templates`（**纯加法**，迁移 30 → 31，见 §6.1）；
  - 新文件：`ui/search/SearchQueryBuilder.kt`、`ui/search/SearchTemplate.kt`、`db/adapter/SearchTemplateDbAdapter.kt`；
  - **①b 额外依赖** ⑧ 的 `tags` / `transaction_tags`（迁移 31 → 32）。
- **风险**：低—中。
  - **首坑 = "标签剥离"**（见 ⑧ / R-9）：多关键词命中 memo 时必须对**剥离 `#tag` 后的文本**匹配，否则搜"打车"会把标签名 `#打车` 也命中 → 结果变脏；
  - **次坑 = 布尔括号**：`A AND B OR C` 必须由生成器用括号钉死（漏括号 ⇒ 绑定错、结果错但**不报错**，正是 R5 类静默失败）；`None` 取反同理（须 `NOT (A OR B)` 而非 `NOT A OR B`）。
- **验收断言**（进单测；每条都要"先回退修法确认变红"）：
  - `"打车 地铁"` → 2 词、词间 AND；`"打车 | 地铁"` → OR；`"共享单车"` → 1 词（引号短语）；
  - `ComparisonType.None` 生成 `NOT (...)`（咬合：回退后断言必须变红）；
  - 混合 `A AND B OR C` 的括号与预期逐字一致；
  - 模板 round-trip：`SearchForm → JSON → SearchForm` 相等；
  - 含 `#tag` 的 memo 搜索**不**命中标签词。
- **量级**：**①a = M**（多关键词 + 全字段 + 模板）／**①b = S–M**（多标签，UI 与 SQL 都是小增量，但被 ⑧ 卡依赖）

- **✅ 实现进度**：

  **第一轮（2026-10-11，KFCSTX）— ①a 内核**

  | 已交付 | 落点 |
  |---|---|
  | 多关键词与/或（`打车 地铁`=AND，`打车 \| 地铁`=OR，引号短语）+ 全字段 `AnyField` + **`None` 语义修复** | `ui/search/SearchCriteria.kt`、`ui/search/SearchQueryBuilder.kt`（**新**，SQL 单点收口） |
  | 多标签条件 `Tag`（ALL/ANY/NONE，**相关子查询**表达） | 同上（UI 可加、SQL 可生成；**待 ⑧ 建表后才有数据可查**） |
  | 搜索模板：模型 + JSON 编解码（含 `form_version`；账户 uid 兜底不丢条件） | `ui/search/SearchTemplate.kt`（**新**） |
  | UI：新增「Any field」「Tag」两类条件的增删/编辑（复用既有行布局） | `SearchFormFragment.kt`、`SearchFormViewModel.kt`、`values/strings.xml` |
  | 单测 | `SearchQueryBuilderTest`、`SearchTemplateTest` **全绿**；原 `SearchFormTest` **零回归** |

  **第二轮（2026-10-11，KFCSTX）— ①a 持久化 + 模板管理 UI**

  | 已交付 | 落点 |
  |---|---|
  | **新表 `search_templates`（schema `30 → 31`，纯加法）**：`id/name/form_json/sort_order/created_at/updated_at`；建表语句 `IF NOT EXISTS`，迁移**可重复执行** | `db/DatabaseSchema.kt`（`SearchTemplateEntry`）、`db/DatabaseHelper.kt`、`db/MigrationHelper.kt#migrateTo31` |
  | `SearchTemplateDbAdapter`：`insert/update/save/getRecord/getRecordByName/getAll(按 sort_order 排序)/getCount/nextSortOrder/delete/deleteAll`；**损坏 payload 降级为空表单并记日志**（不再静默丢行） | `db/adapter/SearchTemplateDbAdapter.kt`（**新**） |
  | 模板管理 UI：**存为模板**（同名覆盖，不产生重复）/ **模板列表一键套用** / **管理**（套用·重命名·删除＋二次确认）；选项菜单 `search_form_actions.xml`；中英字符串 | `ui/search/SearchFormFragment.kt`、`res/menu/search_form_actions.xml`（**新**）、`values{,-zh-rCN}/strings.xml` |
  | 套用语义修复：模板套用后**按新表单重建行**；`与/或` 下拉**回填**表单状态（原本只显示 All）；`None` 加入下拉（与 `NOT(...)` 实现对齐）；套用前**克隆条件**，编辑不反向污染模板 | `SearchFormFragment.kt#bindComparison`、`SearchFormViewModel#applyTemplate/createTemplate`、`SearchCriteria#copyForApply` |
  | 单测 | `SearchTemplateDbAdapterTest`（12 例）+ `SearchTemplateMigrationTest`（2 例）**全绿**；全量 **505 → 533 例、0 失败** |

  **未做（明确的下一步）**：`#tag` 展示层剥离与搜索的联动（R-9，见下方 ⑧ 的进度块：**刻意推后**，理由是现在没有标签 chip UI，剥离只会把用户自己写的标签藏起来）；模板**拖拽排序** UI（`sort_order` 列已就绪，当前按创建顺序）；模板管理已可用（存为/套用/重命名/删除，见 ⑧ 之后的"第二轮"表）。

  **第三轮（2026-10-12，KFCSBYTX）— ①c 全文搜索入口 + 查询语法（且/或/否）+ 搜索词全中文**

  | 已交付 | 落点 |
  |---|---|
  | **纯核心 `FullTextQuery`**：一行纯文本 → 裸 `WHERE` 片段（词法器 + 递归下降 + 编译） | `ui/search/FullTextQuery.kt`（**新**） |
  | 语法：空格 / `,` / `，` / `、` = **且**；半角竖线或全角 `｜` = **或**；`not`（大小写不敏感）= **否定**；`"…"` 锁词组（`"not"` 即字面词）；`(…)` 分组；优先级 `not` ＞ 且 ＞ 或 | 同上 |
  | 模糊匹配：一个词对**摘要 / 备注 / 编号 / 两条分录备注**做 `LIKE '%词%'`；**叶子谓词委派 `SearchQueryBuilder`**（避免"哪些列、怎么匹配"出现第二份实现） | 同上（`leaf()`） |
  | ⚠️ **否定必须 NULL 安全**：编译为 `NOT COALESCE(谓词, 0)`。SQLite 里 `0 OR NULL` = `NULL`、`NOT NULL` = `NULL` ⇒ 直接 `NOT(谓词)` 会静默排除"未命中且任一文本列为空"的交易 | 同上；`FullTextQueryTest#a negation is null safe` 钉住 |
  | 容错：括号不闭合、悬空 `not`、只有算子 ⇒ 丢弃降级（永不抛、永不空转）；无词 ⇒ `""` | 同上 |
  | **首页右上角「搜索」→「全文」**：原 `SearchView`（按账户名过滤）替换为「全文」入口 → `FormActivity(FormType.FULL_TEXT_SEARCH)` → `FullTextSearchFragment`（单行输入 + 语法提示 + 搜索按钮，回车即搜）→ `SearchResultsFragment` | `res/menu/account_actions.xml`、`ui/search/FullTextSearchFragment.kt`（**新**）、`res/layout/fragment_full_text_search.xml`（**新**）、`ui/common/FormActivity.kt`、`ui/transaction/TransactionsActivity.kt#openFullTextSearch`、`ui/account/AccountsActivity.kt`（摘掉 SearchView 管线） |
  | **左侧导航栏「搜索」→「高级搜索」**（行为不变：仍打开表单式搜索） | `res/menu/nav_drawer_menu.xml`、`values/strings.xml` |
  | **搜索词一律用中文**：新增入口 / 标题 / 提示语以**中文作默认值**；另补 zh-CN 缺失的历史搜索词表 32 条 | `values/strings.xml`、`values-zh-rCN/strings.xml` |
  | 单测 | `FullTextQueryTest` **24 例**（×2 SDK = 48）× 全绿；`SearchFormTest` / `SearchQueryBuilderTest` 零回归 |
  | 咬合（7 处变异 / 3 批 / **66 条红** = 33 用例 ×2 SDK） | 批 A 分词与引号（`not` 大小写 + 引号字面量 + 引号内空格）3 例；批 B 连接词与优先级（`NOT COALESCE` 摘除、且→或、并列 AND 失效、`not` 吞掉整个表达式）12 例；批 C 叶子谓词（Contains→Equals）18 例 |

  ⚠️ **行为变更（按需求）**：首页右上角**不再按账户名过滤账户列表**（原 `SearchView` 被「全文」替换）；`AccountsListFragment#onQueryTextChange` 随之失去调用方（保留为公开接口实现，未删）。instrumented 用例 `AccountsActivityTest#testSearchAccounts` 改写为 `testFullTextSearchEntry`（断言 `R.id.menu_search` → 全文输入框出现）。

  **第四轮（2026-10-12，KFCSBYTX）— ①c 常用搜索（保存/套用/改名/批量删）+ 两页互跳 + 全字段模糊修正**

  | 已交付 | 落点 |
  |---|---|
  | **AnyField 模糊匹配扩到全字段（修正）**：原 5 列（摘要 / 备注 / 编号 / 两条分录备注）→ **7 列**，补**两条分录的账户名**（相关子查询）**+ 标签**（`transaction_tags` 相关子查询）；仍走 `sqlEscapeLike` 转义 | `ui/search/SearchQueryBuilder.kt`（`anyFieldCondition` / `accountNameCondition` / `anyTagCondition`） |
  | **常用搜索共享层 `SearchFavoritesUi`**：保存（同名同 kind **覆盖**）/ 列表套用 / **多选批量删**（先确认）/ 改名（限单条）；两页共用，杜绝各写一份 | `ui/search/SearchFavoritesUi.kt`（**新**） |
  | **全文搜索页三按钮**：「保存为常用搜索」「常用搜索」「高级搜索」（后者跳 `SearchFormFragment`，同容器 + backstack） | `ui/search/FullTextSearchFragment.kt`、`res/layout/fragment_full_text_search.xml` |
  | **高级搜索页也接「常用搜索」**（存为 + 列表 + 批量删），替换原单条管理的模板菜单 | `ui/search/SearchFormFragment.kt` |
  | 数据模型：`SearchTemplateKind(Form / FullText)` + `SearchTemplate.kind` / `query`；`kind` / `query` 藏在**模板 JSON 信封内** ⇒ **零 schema 变更**、Form 载荷逐字节向后兼容 | `ui/search/SearchTemplate.kt`（`SearchTemplateCodec`） |
  | 适配器：`getByKind(kind)`（按信封内 kind 过滤）+ `delete(ids)`（单条 SQL `IN` 批量删） | `db/adapter/SearchTemplateDbAdapter.kt` |
  | ⚠️ **顺带修掉两处既有缺陷**：① `SearchFormFragment` 重写了 `onCreateOptionsMenu` 却**从未 `setHasOptionsMenu(true)`**、`FormActivity` 也不转发菜单 ⇒ 模板菜单**从未显示过**（改为继承 `MenuFragment`）；② 批量删失败误报「保存失败」（新增 `search_templates_delete_failed`） | `ui/search/SearchFormFragment.kt`、`app/MenuFragment.kt`、`res/values*/strings.xml` |
  | 咬合（7 处变异 / 2 批 / **50 条红**）：批 A 3 处（AnyField 扩列：两条分录账户名 + 标签判据）19 用例 ×2 SDK = 38 红；批 B 4 处（载荷 kind/query、`kindOf` 判别、`getByKind` 过滤、批量删 `IN`）6 用例 ×2 SDK = 12 红 | `app/build/bite/mutate.py`（临时，已清） |
  | 字符串：两页统一「常用搜索」措辞；`values` 与 `values-zh-rCN` 各 **17 条**对齐 | `res/values*/strings.xml` |
  | 单测 | 见 E13（全量） |

  ⚠️ **本轮踩坑**：咬合跑到一半**断电重启** ⇒ 变异**留在源码里**（备份 `bite/backup/` 仍在）；恢复现场只需 `restore` + `grep` 逐处回读，**不要盲目重跑**（重跑前先确认源码已是变异态）。

  **第五轮（2026-10-13，KFCSBYTX）— ①d 交易模板全链 + 表单借贷双账户 + 搜索结果排序/合计/统计/图表**

  | 已交付 | 落点 |
  |---|---|
  | **交易模板数据层**：新表 `transaction_templates`（schema **34 → 35**，纯加法、建表 `IF NOT EXISTS`、迁移**可重复执行**）；模型 + `TransactionTemplateCodec` —— **「空值金额」= JSON 里省略 `amount` 键**（`null` 绝不退化成 `0`，空串也不写）；适配器 `TransactionTemplateDbAdapter`（`saveByName` 同名覆盖 / `getAll` 按 `sort_order` / `delete(id)` + `delete(ids)` 单条 SQL `IN` / 损坏载荷标 `decodeFailed` 而**不丢行**） | `db/DatabaseSchema.kt`、`db/DatabaseHelper.kt`、`db/MigrationHelper.kt#migrateTo35`、`ui/template/TransactionTemplate.kt`（**新**）、`db/adapter/TransactionTemplateDbAdapter.kt`（**新**） |
  | **交易模板 UI**：抽屉「交易模板」入口 → 列表页（FAB 新建 / 条目溢出菜单「套用·编辑·删除」+ 删除二次确认）/ 编辑对话框（**借贷两个账户下拉 + 可选金额**，留空 = 空值） | `ui/template/{TransactionTemplatesActivity,TransactionTemplatesFragment,TransactionTemplateEditFragment}.kt`（均**新**）、`res/layout/{activity_transaction_templates,fragment_transaction_templates,item_transaction_template,dialog_transaction_template}.xml`（**新**）、`res/menu/transaction_template_item_actions.xml`（**新**）、`res/menu/nav_drawer_menu.xml`、`ui/common/BaseDrawerActivity.kt`、`AndroidManifest.xml` |
  | **模板快速新建**：点条目/「套用」⇒ `FormActivity(TRANSACTION)` + `SELECTED_TEMPLATE_ID` ⇒ 表单预填摘要/备注/编号/金额（**空值不填**，留给当次输入） | `ui/common/UxArgument.kt`、`ui/transaction/TransactionFormFragment.kt#applyTemplatePrefill` |
  | **表单借/贷双账户可改**：新增「借方账户」下拉（**筛选口径与转出不同**：不排除起始账户，否则默认值不可选），原转出下拉改为「贷方账户」语义；`extractSplitsFromView` 按位置直接定 `DEBIT`/`CREDIT`（**不再依赖被隐藏的 `input_transaction_type`**）；`splitEditorUsed` 改由 `splitEditorActive = splitsSize > 2` 驱动 | `res/layout/fragment_transaction_form.xml`、`ui/transaction/TransactionFormFragment.kt` |
  | **搜索结果排序**：日期↑/↓、金额↑/↓；`SearchSortOrder` 产出裸 `ORDER BY` 片段（搜索包仍是 SQL 的唯一产地），适配器新增 `fetchSearch(where, orderBy)`（空 WHERE 走字面量 `1`，**保持同一查询形状** ⇒ 换排序绝不改变命中行）；⚠️ **金额 = 借方分录合计**（`SUM(value_num * 1.0 / value_denom) WHERE type = 'DEBIT'` 的相关子查询）—— 分录值在库里是**无符号**的、配平交易借贷等额 ⇒ 直接 `SUM` 恒为 0、排序无意义 | `ui/search/SearchSortOrder.kt`（**新**）、`db/adapter/TransactionsDbAdapter.kt`、`ui/search/SearchResultsViewModel.kt`、`ui/search/SearchResultsFragment.kt`（改继承 `MenuFragment` 才能拿到菜单） |
  | **合计 / 分类统计 / 图表**：纯函数 `SearchSummary`（**按币种**合计 / **按账户严格分区** / **按标签分桶且允许重叠**）；合计与统计走填充式对话框，图表用项目已有的 MPAndroidChart `PieChart`（**只画「按账户」这一严格分区**：按标签会重叠、加不成整体，故只列不画）；排序/合计/图表入口统一在搜索结果页溢出菜单 | `ui/search/SearchSummary.kt`（**新**）、`ui/search/SearchSummaryDialogFragment.kt`（**新**）、`ui/search/SearchChartDialogFragment.kt`（**新**）、`res/menu/search_results_menu.xml`（**新**）、`res/layout/dialog_search_summary.xml`（**新**） |
  | 单测（**新增 6 类**） | `.../ui/search/{SearchSortOrderTest,SearchSummaryTest}.kt`（纯 JVM）、`.../test/unit/db/SearchResultSortIntegrationTest.kt`（真库跑四种排序 + 「排序不改变命中行」+ 空 WHERE 分支）、`.../ui/template/TransactionTemplateCodecTest.kt`、`.../test/unit/db/TransactionTemplateDbAdapterTest.kt`（含 `TransactionTemplateMigrationTest`：35 / 34→35 / 30→35 全链路） |
  | ⚠️ **顺带修掉三处** | ① `TransactionTemplatesActivity` **漏注册** `AndroidManifest.xml` ⇒ 运行时 `ActivityNotFoundException`；② 编辑对话框两个账户下拉**先 load 后挂 adapter** ⇒ 预选位置可能丢（改为「先挂再 load」并**兜掉 `getPosition` 返回的 `-1`**）；③ 账户名缺失时误用「金额空值」文案（新增 `label_transaction_template_no_account`） |
  | 咬合（5 处变异 / 2 批 / **19 条红**） | 批 A 3 处（`AmountDesc` 反转、`AMOUNT_EXPR` 的 `DEBIT`→`CREDIT`、空 WHERE 的 `"1"`→`""`）共 **16 红**（`SearchSortOrderTest` 4 红 + `SearchResultSortIntegrationTest` 6 例 ×2 SDK = 12 红）；批 B 2 处（标签桶 `.take(1)`、桶排序由「绝对值降序」改成「原值升序」）共 **3 红**；两批均以 `mutate.py restore` + `grep` 逐处回读确认**零残留** |
  | 单测 | 见 E13（全量） |
  | **本轮含 BY** | debug APK `41,490,280 B`（39.57 MiB）/ sha256 `bc415c2ea272b48e472c271dcadcb2bc88d631972eeee5f4c6cb6340e1c131fa`；dex 硬核到 `TransactionTemplateCodec` / `TransactionTemplatesActivity` / `SearchSortOrder` / `SearchSummaryDialogFragment` / `SearchChartDialogFragment` / `transaction_templates` / `transaction_template_id`；`resources.arsc` 硬核到 `交易模板` / `合计与统计` / `按账户` / `按标签（分类可能重叠）` / `借方账户` / `贷方账户` / `无账户` |

  ⚠️ **本轮踩坑（构建）**：`--rerun-tasks --no-build-cache` 的全量单测在**收尾生成 HTML 报告**时被沙箱拦掉一个文件 ⇒ Gradle 把整任务判 FAILED。**判据教训**：`EXIT=1` 不等于测试失败 —— 先看 `index.html` 的 summary（tests/failures）与 `binary/` 时间戳，再决定要不要重跑。另：**后台任务可能“假活”**（任务状态 running 但日志 48 分钟不写、进程 CPU 为 0）⇒ 用 `wmic process get ProcessId,UserModeTime` 两次采样比内存更有判别力。；**v1.19 回填 ①e「借贷账户模糊搜索 + 搜索结果批量改值/批量删除」**（① **账户模糊搜索**：交易表单借方/贷方两行各加搜索按钮 → 新增 `ui/account/AccountSearchDialogFragment`（输入即过滤的账户选择对话框，选定后经 `FragmentResult` 回传 uid）+ 纯函数 `ui/account/AccountSearchFilter`（**多词元 AND**、大小写不敏感、限定名与短名双通道；**前缀命中优先**（先限定名后短名）＋其余保持输入序；空查询原样返回）；⚠️ 对话框的 WHERE 与 spinner 的 adapter **同源**（表单抽出 `debitAccountConditions()` / `creditAccountConditions()`）⇒ **绝不让对话框提供 spinner 会拒绝的账户**；顺手兜住 `setSelectedTransferAccount` 的 `position < 0`（`setSelection(-1)` 会清空下拉而不是保留当前账户）；② **搜索结果批量操作**：新增纯类 `SearchSelection`（**UID 键控**选择集 —— 结果列表每次排序/过滤/批量动作后都会重查，按位置存的选择会指向别的行）+ 适配器/ViewHolder 多选渲染（`cardview_transaction.xml` 加 `GONE` 的 `selection_check`；GONE 在 ConstraintLayout 里塌成一点 ⇒ **非选择模式布局逐像素不变**）（长按进选择模式、选择模式下点击 = 勾选）+ **CAB**（`menu_search_bulk.xml`：全选 / 批量修改 / 批量删除，选中 0 笔时后两项禁用；溢出菜单另加「批量选择」入口）；批量删除 = 二次确认 → `SearchBulkWriter.delete`（逐个 `deleteRecord`、未知 uid 只记日志不打断整批、返回**真实删除数**并如实提示）；批量修改 = `BulkEditTransactionsDialogFragment` 选**描述/备注/编号/日期/标签**之一 + 新值 → `SearchBulkWriter.update`；⚠️ **写入走模型（`addRecord`）而不是裸列 UPDATE** —— 那是交易表单同一条路径，也是唯一会重算分录并**从文本重导标签镜像**的一条；③ **两条值语义**：(a) **留空 = 清空该字段**（「把这 40 笔的编号清掉」是合理请求，不是非法输入）；(b) **日期只改“日”、保留各笔原时分秒**（否则同一天全部被压到 00:00）；标签增删**文本 + 镜像双写**（加 = 备注追加 `#标签`；删 = 备注**与描述**都摘掉 —— 描述里残留的 token 会在同一次保存的 `syncFromTransaction` 里被重新提取回镜像）；④ **顺手修掉一处**：写 `SearchBulkWriter` 时漏了 `?: continue` ⇒ 日期解析失败仍被计入「已修改」（**列没改错，错在“没跳过”**），本轮测试第一次跑就抓到了这条红；**11 处变异 / 2 批咬合共 17 条红**（批 A 6 处：`all→any`、限定名前缀反转、空查询返回空、**短名前缀反转**、`toggle` 返回值反转、日期改午夜 ⇒ 7 例红；批 B 5 处：标签加/删不写文本、日期不跳过、删除计数无条件、空标签名不拦 ⇒ 5 例 ×2 SDK = 10 条红）；⚠️ **咬合又抓出两处弱断言**：排序用例原先的数据**没有任何前缀命中** ⇒ 反转比较器也不变红；补「限定名前缀优先」「短名前缀优先」两条后，**两条 selector 各有能单独区分的用例**（教训：`compareBy` 多 selector 时每条都要有单变量用例）；全量单测 **79 类 / 1353 例 / 0 失败**；**本轮含 BY**：debug APK `41,540,766 B` / sha256 `5d0f7b8585c1849b62a9a291eff47e57f523a1a7…1e0858`，dex 硬核到 `AccountSearchDialogFragment` / `AccountSearchFilter` / `SearchSelection` / `SearchBulkWriter` / `BulkEditTransactionsDialogFragment` / `menu_search_bulk`，`resources.arsc` 硬核到 `搜索借方账户` / `搜索贷方账户` / `没有匹配的账户` / `批量选择` / `批量修改` / `批量删除`）；**v1.20 修复「任意交易点『修改』直接弹回主界面」**（**症状**：交易列表任一条目点「修改」→ 进不去编辑页，直接回到主界面。**根因**：第十九轮把表单改成**借贷双下拉**后，`bindSplits` 会在**账户列表尚未加载完**时就对借方键调 `setSelectedDebitAccount(binding, 借方 split 的 uid)`，而该函数当时写成 `setSelection(if (position >= 0) position else 0)` —— `QualifiedAccountNameAdapter.getPosition()` 在异步 `load{…}` 回填之前对**任何** uid 一律返回 `-1`，于是回落 `setSelection(0)`；同一时刻 spinner 的 adapter **还没挂上**（`setAdapter` 写在 `load` 之后）⇒ **空 spinner 上 `setSelection(0)` 抛 `IndexOutOfBoundsException`** ⇒ 表单视图构建失败、Activity 收场回列表。**修复**：`setSelectedDebitAccount` 改为 `position < 0` 时**直接返回**（与既有 `setSelectedTransferAccount` 同形，真正的选中交给 adapter 自己的 `load` 回调在列表就位后施加）；`TransactionTemplateEditFragment.selectAccount` 补 `adapter.count > 0` 前置闸（空列表不再 `setSelection(0)`）。⚠️ 教训：**异步填充的列表**上做「按 uid 定位」，`getPosition()` 的 `-1` 有两种含义 —— "没这个账号"（应保留原选）与"还没加载完"（应什么都不做），**两种都不能回落成 `0`**（后者会崩，前者会静默改错账号）。**首次给表单页补测试**：新增 `TransactionFormEditTest`（Robolectric `buildActivity(FormActivity, INTENT).setup()` + `SELECTED_TRANSACTION_UID` + `setDoubleEntryEnabled(true)`），断言落位在 `TransactionFormFragment`、`isFinishing == false`、摘要/编号/备注/金额回显、借贷两行 `VISIBLE`；⚠️ **回归断言已咬合**：把修复还原成 `if (position >= 0) position else 0` 后**4 例全红**（`IndexOutOfBoundsException`，与真机同一异常、同一路径），还原后复绿；**dex 字节级对照**同时证明修复进包 —— 同源代码重建 **sha256 逐字节可复现**（`debae437…b6e0` 两次一致），带旧 bug 的对照包**仅 `classes9.dex` 一个文件不同**；全量单测 **80 类 / 1357 例 / 0 失败**（1h3m55s）；**本轮含 BY**：debug APK `41,540,766 B` / sha256 `debae437b98e3ffa9745a937747e922ac2fd9e123413658bce788a79c3bbf6e0`（⚠️ 与第十九轮**同体积、不同哈希** ⇒ K16；本轮改动是**纯控制流、无新字面量**，dex 内没有唯一串可锚，故硬证改为「干净重建 + 与旧 bug 包的 dex 逐文件对照」）；**v1.21 回填 ②-v1「接线第二半」—— WebDAV 设置页 + 凭据存储 + 备份/恢复入口**（把第十四轮的 `RemoteBackup`、第十五轮的 `WebDavConnection` / `BookPayload` 真正接上界面：① **校验一律在拨号之前** —— 新增纯编排 `sync/WebDavSyncController`，`checkedFirst(connection)` 是**唯一漏斗**：`WebDavConnection.check()` 判 `Invalid` 就直接 `NotUsable(problem, detail)`，**绝不发起任何网络请求**（半填的地址不该产生一次 4xx/超时）；② **口令策略收成纯函数** `sync/RemotePassphrasePolicy`（4 条规则、**顺序即优先级**：空口令 / 只填确认框 / 两次不一致 / 未勾"我知道远端那份是密文"）；`Use` 持 `CharArray` 且 `toString()` **脱敏**（不进日志、不进 `toString` 链）；③ **远端状态一律经类型表达**：13 态 `BackupNotice` + 5 态 `ConnectionNotice`（`sync/BackupNotice`），且把 `status == STATUS_NO_RESPONSE`（**根本没连上**）与 4xx/5xx（**连上了但被拒**）**分成两条** —— 两者的用户下一步动作完全不同；④ **空导出拒上传**：`sync/AndroidBookTransfer` 导出后若书为空/读不出 ⇒ 直接 `WebdavExportFailed`，**绝不把远端那份覆盖成空文件**（这正是 `RemoteBackup` "远端是密文而无口令 ⇒ 零字节返回"守卫在写入侧的对称物）；下载路径**只有成功才带字节**；⑤ **凭据存储** `ui/settings/WebDavPreferences` 读**默认** SharedPreferences（与 `androidx.preference` 写的是同一份），刻意不引第二个存储后端；**口令不落盘**（远端密文由坐落在同一 app-private 目录的秘密保护 ≡ 等于没加密）；⑥ **偏好页只加 8 行 UI**（`res/xml/fragment_backup_preferences.xml` 追加 WebDAV 分类）+ 口令对话框 `res/layout/dialog_backup_passphrase.xml` + 恢复二次确认；值→文案由 `ui/settings/WebDavNoticeText` **单点**映射，`preferenceKeyOf` 把出错项**点到具体那一行**；⑦ ⚠️ **新增 K21**：设置页 inflate 只有一种姿势（`addPreferencesFromResource` 是 **Fragment** 的方法，`PreferenceScreen` 上应走 `PreferenceManager.inflateFromResource(context, xml, screen)`，且 inflate 出的 screen 要 `by lazy` 缓存）+ 读那份配置**必须**读**默认** SharedPreferences（照抄 `OwnCloudPreferences` 的 `getSharedPreferences("owncloud_pref", MODE_PRIVATE)` 会读到**另一份文件**，表现为"改了地址它还是连旧的"）；新增 **6 类 / 73 例**（`RemotePassphrasePolicyTest` / `BackupNoticeTest` / `WebDavSyncControllerTest` / `WebDavNoticeTextTest` / `WebDavPreferencesTest` / `BackupPreferenceScreenTest`）；**5 处变异 → 7 条红**（A1 去掉"两次输入必须一致"⇒2 红 · A2 无响应判反⇒1 · A3 `Transport` 说成"不是 WebDAV 根"⇒1 · A4 加密标志取反⇒2 · A5 放开空导出上传⇒1）；全量单测 **86 类 / 1430 例 / 0 失败**；**本轮不含 BY**，APK 沿用第二十轮 `debae437…b6e0`）

  **第六轮（2026-10-13，KFCSBYTX）— ①e 借贷账户模糊搜索 + 搜索结果批量改值/批量删除**

  | 已交付 | 落点 |
  |---|---|
  | **账户模糊搜索（借贷两侧）**：表单借方/贷方行各加搜索按钮 → 输入即过滤的账户选择对话框，选定后经 `FragmentResult` 回传 uid 到对应 spinner | `ui/account/AccountSearchFilter.kt`（**新**，纯函数：多词元 AND / 大小写不敏感 / 限定名+短名双通道 / **前缀命中优先** / 其余保持输入序 / 空查询原样返回）、`ui/account/AccountSearchDialogFragment.kt`（**新**）、`res/layout/dialog_account_search.xml`（**新**） |
  | **筛选口径单一来源**：picker 的 WHERE 与 spinner 的 adapter **同源**，杜绝"对话框能选、spinner 拒绝" | `ui/transaction/TransactionFormFragment.kt`（抽出 `debitAccountConditions()` / `creditAccountConditions()` 两处共用；顺手兜住 `setSelectedTransferAccount` 的 `position < 0`） |
  | **搜索结果多选**：长按进选择模式 / 溢出菜单「批量选择」；选择模式下点击 = 勾选；勾选态跨重载存活 | `ui/search/SearchSelection.kt`（**新**，纯类，**UID 键控** —— 列表每次重查都换游标）、`ui/search/SearchResultsAdapter.kt`、`ui/search/SearchResultViewHolder.kt`、`res/layout/cardview_transaction.xml`（`GONE` 的 `selection_check`，GONE 在 ConstraintLayout 里塌成一点 ⇒ 非选择模式布局不变） |
  | **批量删除 / 批量修改（CAB）** | `res/menu/menu_search_bulk.xml`（**新**：全选 / 批量修改 / 批量删除）、`ui/search/SearchResultsFragment.kt`（`ActionMode.Callback`；选中 0 笔时后两项禁用；删除二次确认）、`ui/search/BulkEditTransactionsDialogFragment.kt`（**新**）+ `res/layout/dialog_bulk_edit.xml`（**新**） |
  | **批量写库（同步、可单测）** | `ui/search/SearchBulkWriter.kt`（**新**：`delete` 逐个 `deleteRecord`、未知 uid 只记日志不打断整批、返回**真实删除数**；`update` **走模型 `addRecord`** 而非裸列 UPDATE —— 唯一会重算分录 + **从文本重导标签镜像**的一条） |
  | **两条值语义** | (a) **留空 = 清空该字段**；(b) **日期只改“日”、保留各笔原时分秒**；标签**文本 + 镜像双写**（加 = 备注追加 `#标签`；删 = 备注**与描述**都摘掉） |
  | 单测：**新增 4 类** | `.../ui/account/AccountSearchFilterTest.kt`（12 例，纯 JVM）、`.../ui/search/{SearchSelectionTest,BulkEditTest}.kt`（6 + 14 例，纯 JVM）、`.../test/unit/db/SearchBulkWriterTest.kt`（17 例 ×2 SDK，真库：批量删 / 批量改 / 标签双写 / 日期保时分 / 编辑不丢分录） |
  | ⚠️ **本轮测试抓到的真 bug** | `SearchBulkWriter` 的日期分支漏了 `?: continue` ⇒ 解析失败仍被计入"已修改"（用户被告知"40 笔已改"而日期全是旧的）—— **列没改错，错在“没跳过”** |
  | 咬合（**11 处 / 2 批 / 17 条红**） | 批 A 6 处 ⇒ 7 例红；批 B 5 处 ⇒ 5 例 ×2 SDK = 10 条红（两批都先跑 clean 基线先验） |
  | ⚠️ **咬合抓出的弱断言** | 排序用例原先的数据**没有任何前缀命中** ⇒ 反转比较器也不变红；补两条单变量用例后两条 selector 各得 1 红（见环境文档 **K19**） |
  | 单测 | 见 E13（全量 79 类 / 1353 例 / 0 失败） |
  | **本轮含 BY** | debug APK `41,540,766`（39.62 MiB）/ sha256 `5d0f7b8585c1849b62a9a291eff47e57f523a1a7a532d1b0b8f439754e1e0858`；dex 硬核到 `AccountSearchDialogFragment` / `AccountSearchFilter` / `SearchSelection` / `SearchBulkWriter` / `BulkEditTransactionsDialogFragment` / `menu_search_bulk`；`resources.arsc` 硬核到 `搜索借方账户` / `搜索贷方账户` / `没有匹配的账户` / `批量选择` / `批量修改` / `批量删除` |

  ⚠️ **已知限制**：① 批量写库是**同步**执行（与既有单条删除同一条路径）⇒ 选中数百笔时可感到卡顿，尚未改为后台任务；② 标签批量删只摘「备注 + 描述」里的 token，**分录备注（memo）里的同名标签会在同一次 `syncFromTransaction` 里重新入镜像**（文本是唯一真源，这是兼容桌面版的既定契约）、③ 批量修改**不含账户与金额**（改这两个要重算每条交易的分录配对，是另一个量级的功能）。

  ⚠️ **本轮踩坑（环境）**：全量单测**连续两次**死在 `Gradle build daemon disappeared unexpectedly`（第一次 10m47s、另一次在咬合中途）；内存充足（33.5 GB 总 / 21.9 GB 空闲）、守护进程日志无 OOM ⇒ 属环境抖动、非代码问题。**处置**：`./gradlew --stop` 后再跑，并以 `wmic process ... UserModeTime` 隔 18 秒两次采样确认"真在跑"（见环境文档 **K19**）。

  **第七轮（2026-10-14，KFCSBYTX）— ①f 扩展视图（紧凑／扩展互斥 + 多套 · 重命名 · 设默认）+ 借贷同号修复 + 批量写库异步化 + 模板账户模糊搜索**

  | 已交付 | 落点 |
  |---|---|
  | **借贷同号修复**（用户报：修改交易时两侧变成同一账户） | `ui/transaction/TransactionSides.kt`（**新**：按 `Split.type` 取 DEBIT/CREDIT 账户，取代「不是起始账户的那一条」）、`ui/account/AccountConditions.kt`（**新**：`selectable()` 借贷**共用一份** WHERE —— 旧条件排除起始账户 ⇒ 从贷方打开时真贷方账号不可选、下拉回落成借方）、`ui/transaction/TransactionFormFragment.kt`（贷方改用 `TransactionSides.creditAccountUid`；两行与两个选择器同源；**保存前拒绝两侧同号**） |
  | **批量写库异步化**（用户报：批量修改保存后卡住） | `ui/search/BulkActionRunner.kt`（**新**：`withContext(IO)` 执行 / `Main.immediate` 回结果）、`ui/search/SearchResultsViewModel.kt`（`deleteTransactions` / `bulkUpdate` 改**异步 + 回调**）、`ui/search/SearchResultsFragment.kt`（**先 `finishSelection()` 再提交**，大批量期间列表可用） |
  | **模板借贷账户模糊搜索** | `ui/template/TransactionTemplateEditFragment.kt`（两行各挂搜索按钮 + `REQUEST_DEBIT/CREDIT_ACCOUNT_SEARCH`；条件复用 `AccountConditions.selectable()`）、`res/layout/dialog_transaction_template.xml`（`btn_search_debit_account` / `btn_search_credit_account`） |
  | **扩展视图 · 纯层** | `ui/transaction/view/ExtendedViewField.kt`（`ACCOUNT/NOTES/NUMBER/TAGS` + `DEFAULT`）、`ExtendedView.kt`、`ExtendedViews.kt`（纯函数：`newId` 取**最高幸存后缀**、`remove` 删默认时**交棒**、`setDefault` 拒未知 id、`active` 指针悬空回退首个）、`ExtendedViewCodec.kt`（record `\n` / part `\t` / field `,`，反斜杠转义 + **坏行丢弃**）、`ExtendedRowContent.kt`（**空值不画空行**） |
  | **扩展视图 · 存储与界面** | `ui/transaction/view/ExtendedViewStore.kt`（book preferences 三键 + 无配置回退全字段）、`ExtendedViewsActivity`（继承 `PasscodeLockActivity`）、`ExtendedViewsFragment`（列表 + FAB + 溢出菜单「设默认／改名／删除」）、`ExtendedViewEditFragment`（名称 + 4 个字段勾选，空名/空字段拒绝）、`res/layout/{activity_extended_views,fragment_extended_views,item_extended_view,dialog_extended_view_edit}.xml`（**新**） |
  | **二级菜单「设置扩展显示」** | `res/menu/transactions_list_actions.xml`（`menu_extended_view` 嵌套 → `menu_toggle_extended`（checkable）+ `menu_extended_view_settings`）、`ui/transaction/TransactionsListFragment.kt`（与紧凑视图**互斥**；`onResume` 重读 `activeFields()`；卡片按字段画扩展行） |
  | 单测：**新增 10 类** | `ExtendedViewsTest` 15 / `ExtendedViewCodecTest` 10 / `ExtendedRowContentTest` 6 / `BulkActionRunnerTest` 3 / `SearchBulkWiringTest` 3 / `ExtendedViewWiringTest` 6（均纯 JVM）、`TransactionSidesTest` 4 / `ExtendedViewStoreTest` 7 / `TransactionTemplateSearchTest` 4（Robolectric）、`TransactionFormSidesGuardTest` 3（**源码守卫**）；`TransactionFormEditTest` 补「从贷方打开两侧仍分开」1 例 |
  | 咬合（**11 处 / 逐处自备份还原**） | 见环境文档 **K24** |
  | 单测 | 见 E13 |
  | **本轮含 BY** | debug APK `41,702,489 B`（39.77 MiB）/ sha256 `0ae9a2ea…a6315`；`45 actionable tasks: 45 executed`（1m58s，`--rerun-tasks --no-build-cache`） |


### ② WebDAV 同步（加密可选）+ 冲突中心

> 这是全项目**风险最高**的一项。分两段做，**不要一次到位**。

- **现状**：`ExportTarget.OWNCLOUD` 有实现（nextcloud SDK + apache-http），但**只有推送**；没有远端列举/拉取/差异/合并；`net/` 只有一个 `TrustExt.kt`。
- **方案**：
  1. **新建通用 WebDAV 客户端**（`PROPFIND`/`PUT`/`GET`/`MKCOL`/`DELETE`/`MOVE`），用**已依赖的 OkHttp 5.5.0** 自实现，**与 OwnCloud SDK 解耦**（便于替换坚果云/Nextcloud/自建）；
  2. **v1 = 单向加密备份 + 下载恢复**（低风险、可交付）；
  3. **v2 = 记录级双向合并**（决策 D4）：
     - 粒度：按 `books/accounts/transactions/splits` 的 **GUID** 做**字段级 diff**；
     - 需要 **base 基线**（上次同步时的快照）+ **墓碑**（删除标记）+ **modified_at / 设备标识**；
     - **冲突中心**：列出 A/B/基线三方差异，支持**逐字段或整条批量采纳**；
     - 幂等：同一份快照重放不产生新变更。
  4. **口令与加密**见第 8 章。
- **数据/接口改动**：
  - 新增表：`sync_state`（每条记录的 base 快照 / 水位）、`tombstones`；
  - **schema 版本 +1**（见第 6 章迁移方案）。
- **风险**：**高**。数据安全类，最怕"日志全绿、交易少了"。
  - 必须照抄既有教训：**全量快照与水位必须在同一个只读事务里读齐**，否则会拼出自相矛盾的快照，下游拿它覆盖即**永久丢数据**；
  - 删除必须靠**"已确认的缺席"**，不能靠"这一轮没列到"（远端列举被截断会误判成删除 → 双端丢数据）。
- **量级**：**L**（拆：v1 单向 S；v2 双向 L）

- **✅ 实现进度（2026-10-11，第六轮 KFCSBTX）：②-v2 的「决策内核 + 持久化」层落地（无网络）**

  本轮只做 **v2 的纯决策层与数据层**，**刻意不碰 WebDAV 网络层**——先把最危险的那部分（"合并算错 = 永久丢交易"）做成**可穷举断言、无 Android 依赖**的纯函数 + 可断言的两张表。

  | 已交付 | 落点 |
  |---|---|
  | **schema 32 → 33**：`sync_state`（base 快照 + `base_modified_at` 水位）与 `tombstones`（删除标记），纯加法、建表 `IF NOT EXISTS`、迁移 `migrateTo33` 可重入 | `db/DatabaseSchema.kt`（`SyncStateEntry`/`TombstoneEntry`）、`db/DatabaseHelper.kt`、`db/MigrationHelper.kt` |
  | **三路合并纯函数核心**（零 Android 依赖，可逐分支穷举）：`decide(base, local, remote)` 覆盖 §7.2 全部形态（单侧创建/单侧改/同改/三方互异→冲突/**删除×修改**）；`planDeletions(...)` 用"**已确认的缺席** + **两轮晋升** + **两道整轮放弃闸**"推断删除 | `sync/ThreeWayMerge.kt`（新）、`sync/SyncRecordType.kt`（新） |
  | **`SyncStateDbAdapter`**：同时 owning 两表（仿 `BudgetsDbAdapter`/`TagsDbAdapter`）；`putBase/getBase/getBases/removeBase`、`addTombstone/getTombstones/isTombstoned/clearTombstone`；`readSnapshot()` **在单事务里把 base 行与水位一并读齐，且水位由已读行派生**——结构上不可能产出"水位 > 行数"的自相矛盾快照（§7.3 约束 1 / R-1） | `db/adapter/SyncStateDbAdapter.kt`（新） |
  | **新增断言** | `sync/ThreeWayMergeTest`（30 例，纯 JVM，不跑 Robolectric）；`db/SyncStateDbAdapterTest` + `db/SyncStateMigrationTest`（16 + 2 例 × `@Config(sdk=[26,36])` = 32 + 4） |

  **两条设计决定（本轮定死）**：
  1. **删除只认"已确认的缺席"**：列举未完整跑完（截断/失败）⇒ **整轮放弃、一个字节都不写**（连 pending 都不记）；列举回来**全空**而本地又知道有记录 ⇒ 同样放弃（代价是"删掉最后一条不同步"，远小于"随机删光"）。首次缺席只记 `pending`，**第二次连续缺席**才晋升为墓碑；本机自己删的（`locallyDeleted`）一轮即墓碑——那是唯一"自明"的删除。
  2. **删除×修改 = 修改胜出 + 仍上报复核**：`DeleteVsEdit(winner, diffs)` —— 修改可恢复、删除不可逆，所以采纳修改；但**必须**进冲突中心。且 `diffs` **只列存活侧相对 base 真正改动过的字段**（删掉的一侧按 `null` 呈现 = 空白单元格），**不是**"把存活侧每个字段都标成差异"——后者会把没人动过的字段也渲染成冲突（本轮真实踩到并修掉）。

  **咬合验证**：临时把 `planDeletions` 里的 `watched + previousPending.keys` 改成只用 `watched`（即去掉 pending 并集）⇒ `a key that only lives in the previous pending set still reaches confirmation` **立刻变红**，恢复后转绿。证明"缺席键会从 `watched` 掉出去、必须靠 pending 并集才能收敛"这条不变量真被钉住。

  **仍未做（明确的下一步）**：WebDAV 客户端（`PROPFIND`/`PUT`/`GET`/`MKCOL`，OkHttp 自实现、与 OwnCloud SDK 解耦）；v1 单向加密备份 + 下载恢复；把 `ThreeWayMerge.decide` 接上真实的 local/remote 列举与落盘；冲突中心 UI（§7.2 的三方差异视图）；`device_id` / `modified_at` 的传播。

- **✅ 实现进度（2026-10-12，第七轮 KFCSTX）：②-v2 的 WebDAV 传输层（协议核心 + 列举完整性）**

  承接第六轮的「决策内核」，本轮补上**网络半场的第一块**：把"远端到底有什么"变成一个**可断言、且不可能说谎**的结果。仍不含 UI、不含落盘（下一轮）。协议约定见 §7.5。

  | 已交付 | 落点 |
  |---|---|
  | **路径算术**（RFC 3986 `pchar`）：**逐段**百分号编解码（`/` 不当分隔符编码、空格的 `+` 陷阱、`%2520` 只解一次、畸形转义不抛出）、`normalize` 归一身份、`join`/`name`/`isUnder` | `sync/webdav/WebDavPath.kt`（新，零 Android 依赖） |
  | **`PROPFIND` 请求体 + `207 Multi-Status` 解析**：命名空间前缀无关（`D:`/`d:`/无前缀默认 ns）、`<resourcetype/>` 自闭合、etag 去引号与 `W/`、href 归一；**拒绝 DOCTYPE**（防 XXE，含对拍用例） | `sync/webdav/WebDavXml.kt`（新） |
  | **`ListingResult`（`Complete` / `Incomplete` + 5 种原因）+ `confirmsAbsence`** | `sync/webdav/WebDavListing.kt`（新） |
  | **`WebDavClient`**：`Depth: 1` + **自递归**；任一层非 207 / malformed / 含失败 `<response>` ⇒ 整轮 `Incomplete`；丢弃 Depth:1 回显的 collection 自身（**与请求路径比较**，不只与根比较）；`isUnder` 拒横向/向上 href；访问集合去重 + `maxDepth` 防环 | `sync/webdav/WebDavClient.kt`（新） |
  | **OkHttp transport**（平台接缝，唯一碰网络的类）：Basic auth、可注入 `OkHttpClient`、`trustAllCertificates` **复用** `net/TrustExt.kt` 的 `TrustAllCertificatesManager`（§8「复用而非新写」） | `sync/webdav/WebDavOkHttpTransport.kt`（新） |
  | **新增断言** | `sync/webdav/{WebDavPathTest,WebDavXmlTest,WebDavClientTest}`（19 + 17 + 18 例）；**全部纯 JVM、不跑 Robolectric**（协议核心无 Android 依赖） |

  **三条设计决定（本轮定死）**：
  1. **`Depth: 1` + 本地递归，不用 `Depth: infinity`**：`infinity` 的截断**不可检测**——服务器大目录列到一半就停，客户端收到的仍是一份格式完好的 `207` 子集，无从判断。自递归把遍历权收回客户端，于是"哪一层失败了"变成可见信息，可放弃整轮。
  2. **`Incomplete` 绝不退化成"空"**：非 2xx、body malformed、`207` 里夹一个非 2xx 的 `<response>`、递归越界、href 越界 —— 五条路径全部产出 `Incomplete`。失败方向刻意选成"今天不同步"，而不是"双端删账本"（§7.3 约束 2 / R-1）。唯一的接线点是 `ListingResult.confirmsAbsence`。
  3. **`<propstat>` 缺 `<status>` 一律当失败**（RFC 要求有，Nextcloud / mod_dav / SabreDAV 都合规）：宁可放弃整轮，也不猜某个资源是否存在——猜错的方向正是"sync engine 笃信一条并不存在的记录"。

  **咬合验证（三处安全闸门，各自拆掉后对应用例变红）**：
  - 去掉 self 条目过滤 ⇒ `the collection itself is dropped even when the server omits the collection type`（服务器漏填 `<resourcetype>` 时文件夹会变成一个"文件"）与 `a nested collection echoing itself is dropped one level down too` 变红；
  - 去掉 `isUnder` ⇒ `a Depth-1 response reaching sideways is refused` 变红；
  - 把 `confirmsAbsence` 改成恒 `true` ⇒ `an incomplete listing can never produce a tombstone, for any reason` 变红。
  ⚠️ 第三条第一次写时**假绿**：原用 `present = emptySet()`，被合并层的第二道闸（"列空而本地有记录"）挡住，用例照样通过 ⇒ 改成 `present = {T1}`（非空）才把完整性闸门**单独**逼出来。教训：**"测试过了"与"测试咬住了它要测的那条"是两件事。**

- **✅ 实现进度（2026-10-12，第八轮 KFCSTX）：②-v2 的 WebDAV 动作层（写动词 + 下载）**

  第七轮把"远端有什么"变成了可信结果，本轮补上**"怎么改远端、怎么取回来"**：`PUT`/`GET`/`MKCOL`/`DELETE`/`MOVE` 五个动词的**纯策略分类** + 客户端方法。仍不含落盘、不含冲突中心 UI。协议约定见 §7.6。

  | 已交付 | 落点 |
  |---|---|
  | **`ActionOutcome` 五态**（`Done` / `AlreadyDone` / `Stale` / `Missing` / `Failed`）+ `succeeded` / `requiresReMerge`：把"成功/失败"拆成**四个后续动作完全不同**的语义，任一合并都会导致"永远重试"或"静默丢变更" | `sync/webdav/WebDavActions.kt`（新，零 Android 依赖） |
  | **五个动词的状态分类**：`classifyPut/Delete/Mkcol/Move/Get`。含三处易错判：`DELETE 404/410 → AlreadyDone`（目标态已达成）、`MKCOL 405 → AlreadyDone`（对端并发建了同一个目录）、`PUT 412 → Stale`（快照过期，必须重新列举+重合并而非重试）；另有"`202 Accepted` **不算** Done"、"`206 Partial Content` **不算**整条记录"、"`207` 的 DELETE 不算完成" | 同上 |
  | **路径祖先推导 `collectionsToEnsure(path, root)`**：root-first 顺序（`MKCOL /a/b` 先于 `/a` 必 `409`）、**排除客户端根**（根是用户配置的目录，在别处创建；也不会把写入扩到同步子树之外）、文件直接在根下时返回空 | 同上 |
  | **头部构造**：`PutPrecondition`（`None` / `MustNotExist`⇒`If-None-Match: *` / `MustMatch`⇒`If-Match`）**结构上互斥**（一次只出一个条件头）；`moveHeaders` 显式给 `Destination`（绝对 URL）与 `Overwrite` | 同上 |
  | **`WebDavClient` 动作方法**：`put`（返回的 ETag 回灌 `Done.etag` 作新基线）、`download`（`DownloadOutcome.Got`/`Refused`，缺失≠空文件）、`delete`、`mkcol`、`move`、`ensureCollections`、`putCreatingCollections`（先建目录后上传）；传输异常一律收敛为 `Failed(STATUS_NO_RESPONSE)` 并**保留异常原文** | `sync/webdav/WebDavClient.kt` |
  | **OkHttp 写动词与下载**：`body == null` 原样透传给 OkHttp（`MKCOL` 带 body 会被某些服务器判 `415`）；错误响应**不下载 body**；上传/下载超时放宽到 60s | `sync/webdav/WebDavOkHttpTransport.kt` |
  | **断言** | `sync/webdav/WebDavActionsTest`（21 例）+ `sync/webdav/WebDavClientActionsTest`（24 例）；**全部纯 JVM、不跑 Robolectric** |

  **三条设计决定（本轮定死）**：
  1. **幂等 ≠ 成功**：一轮同步可能被重跑（崩溃后、`412` 后、用户连点两次）。所有"目标态已成立"的情形（`DELETE` 已不存在、`MKCOL` 已存在）映射成 `AlreadyDone` 而**不是**错误，重跑才会收敛而不是卡死；但它与 `Done` 分开，因为"我们改了远端"和"远端本来就是那样"对基线更新是两件事。
  2. **写远端一律带条件**：新建用 `If-None-Match: *`、更新用 `If-Match: <基线 etag>`。无条件的 `PUT` 会在"列举之后、上传之前对端写了同一条"时**静默覆盖**（经典丢更新），两侧日志全绿。条件写把这种情况变成 `412` → `Stale` → 重新列举+重合并。
  3. **建目录与上传分离且按序**：`PUT` 进一个不存在的集合必得 `409`；若把它读成"这条不可写"，就会**永远不上传**。`ensureCollections` 逐层 root-first 建，**遇到第一个真失败就停**（继续走会把"哪一层坏了"埋掉）。

  **咬合验证（五处，各自拆掉后对应用例变红，恢复后转绿）**：
  - `classifyDelete` 的 `404/410 → AlreadyDone` 改成 `Failed` ⇒ `a DELETE of a record that is already gone is AlreadyDone` 与 `deleting a record that is already gone counts as done` 变红；
  - `classifyMkcol` 的 `405 → AlreadyDone` 改成 `Failed` ⇒ `MKCOL of a collection that already exists is AlreadyDone` 与 `mkcol of an existing collection counts as done` 变红；
  - `classifyPut` 的 `412 → Stale` 改成 `Done` ⇒ `a rejected conditional PUT is Stale, never Done` 与 `a rejected conditional put is not reported as applied` 变红；
  - `collectionsToEnsure` 返回值加 `.reversed()` ⇒ 四条"root-first 顺序"用例变红；
  - `classifyPut` 的 `200/201/204` 放宽成 `in 200..299` ⇒ `a 2xx that is not a completed write is not Done` 变红。

  ⚠️ **本轮踩到两次同一个坑（值得记）**：编辑 Z 盘文件时**报告成功但内容未落盘**（5 处变异里有 2 处、还原时又有 3 处）。第一次表现为"变异后用例没变红"，一度以为断言写错了；**回读文件**才发现变异根本没写进去。⇒ **Z 盘上任何"改动 → 验证"的链路，必须回读文件本体确认，不能只信写操作的回执。**

  **仍未做（明确的下一步）**：把 `listRecursive()` 的 `Complete` + `WebDavActions` 接上 `ThreeWayMerge` 做**一轮真实同步的编排**（列举 → 合并 → 动作 → 落盘 → 写回基线/墓碑）；v1 单向加密备份 + 下载恢复；冲突中心 UI（§7.2 的三方差异视图）；`device_id` / `modified_at` 的传播。

- **✅ 实现进度（2026-10-12，第九轮 KFCSTX）：②-v2 的编排层（一轮真实同步）**

  第七、八轮把网络半场做完了"远端有什么"和"怎么改远端"；本轮把它们与决策内核接通，做出**一轮可断言的真实同步**：列举 → 合并 → 动作 → 落盘 → 写回基线/墓碑/pending。仍不含 UI（冲突中心）、不含 v1 单向加密备份。协议约定见 §7.7。

  | 已交付 | 落点 |
  |---|---|
  | **`SyncLayout`**：`SyncIdentity`（即记录身份，`recordType` + GnuCash GUID）与远端路径的严格双向映射（`/<type>/<key>.gncs`，扁平一层）。严格拒绝：非本布局、未知类型、多层、空键。**身份就是四处视图（本地/列举/基线/墓碑）的 join key**，拼法漂移会让所有记录看起来缺席 | `sync/SyncLayout.kt`（新，零 Android 依赖） |
  | **`RecordCodec`**：字段 ↔ 规范文本（`key=value` 按 key 排序、`%`/`\n`/`\r`/`=` 转义）。**无损且确定**是硬性要求——基线快照要跟"刚解码的记录"做相等比较，丢一个字段差异的编码会让未变的记录每轮都报冲突/删除。解码对无分隔符的行**抛错**（`decodeOrNull` 给不可信输入） | `sync/RecordCodec.kt`（新） |
  | **`SyncPlan`/`SyncRoundPlanner`**：把 `local / remote / base` 三方 + `planDeletions` 合成一轮动作（`PUSH`/`PULL`/`DELETE_REMOTE`/`DELETE_LOCAL`/`NONE`/`REVIEW`）。**两道放弃闸**（不完整列举 ⇒ 整轮不动；空列举而有已知记录 ⇒ 整轮不动）。删除候选**复用 `decide`**（所以"一方删、一方改"自动落到 §7.2 的"采用修改那方 + 记冲突"）。条件写前置条件由**规划器**给定，轮次无 `PUT` 能不带条件落到线上 | `sync/SyncPlan.kt` + `sync/SyncRoundPlanner.kt`（新，纯 JVM） |
  | **`LocalRecordStore` 接缝 + `SyncRound` 驱动**：列举 → **按 etag 决定是否 GET**（etag 等于基线 ⇒ 内容等于基线 ⇒ 不下载）→ 规划 → 执行（条件 `PUT`/`GET`/`DELETE`）→ 写回 base/tombstone/pending。`Stale`（`412`）⇒ 停轮、下轮重列举（**不重试同一个写**） | `sync/LocalRecordStore.kt` + `sync/SyncRound.kt`（新） |
  | **基线补两列（schema 33→34）**：`sync_state.base_etag`（**条件写的 `If-Match` 值**，也是"跳过下载"的依据）与 `sync_state.pending_since`（首见缺席标记）。`pending` 挂在基线行上而非旁表 ⇒ "可能被删了"不会与记录自身状态脱节。迁移 `migrateTo34` 幂等（`ALTER TABLE ADD COLUMN` 无 `IF NOT EXISTS`，先查 `PRAGMA` 再改） | `db/DatabaseSchema.kt`、`db/DatabaseHelper.kt`、`db/MigrationHelper.kt`、`db/adapter/SyncStateDbAdapter.kt`、`model/SyncBase.kt` |
  | **断言** | `SyncLayoutTest`（8）+ `RecordCodecTest`（9）+ `SyncRoundPlannerTest`（23，纯 JVM）+ `SyncRoundTest`（8 例 ×2 SDK，Robolectric 端到端：假 transport + 内存 store + 真 DB） |

  **三条设计决定（本轮定死）**：
  1. **不完整的列举不是"更小的真列举"，是不可信的列举** ⇒ 整轮放弃，**连上传都不做**。只挡删除是不够的：没有完整远端视图，无法区分"本地改动"与"与一个没看到的对端改动相撞"，此时唯一不丢数据的答案是"什么都不做"。
  2. **基线带 etag**：它同时承担两件事——`If-Match` 的值（防丢更新），以及"远端没变就不必下载"的判据。没有它，条件写构造不出来、每轮都要把整本书拉下来。
  3. **`pending` 是记录自身同步状态的一部分**，存到 `sync_state.pending_since` 而不是旁表：首见缺席记一次，**第二次连续缺席**才晋升墓碑；重现即清除。本机自己删的（`locallyDeleted`）一轮即墓碑。

  **咬合验证（七处，各自拆掉后对应用例变红，恢复后转绿）**：
  - 去掉**闸 1**（不完整列举⇒放弃）⇒ `an incomplete listing abandons the round and touches nothing` 变红；
  - 去掉**闸 2**（`planDeletions` 的 `abandoned` 传播）⇒ `an empty listing while records are known abandons the round` 变红；
  - 把"首见缺席 ⇒ 记 pending"改成什么都不做 ⇒ `a peer absence seen once is watched, never deleted`（该记录会掉进合并循环被判 `DELETE_LOCAL`）与 `a first absence is stamped, and a second one promotes rather than re-stamps` 变红；
  - 去掉"远端不可读 ⇒ 不动"分支 ⇒ `an unreadable peer record is left alone and never deleted` 变红（否则会以"远端为空"采纳，把本地记录清空）；
  - 把新建的前置条件从 `MustNotExist` 改成 `None` ⇒ `a record created only here is pushed as a create` 与 `the chosen precondition turns into the right conditional header` 变红；
  - 去掉"etag 相等则不下载" ⇒ `a second round with nothing changed reads nothing and writes nothing` 变红（GET 次数上升）；
  - 去掉 `migrateTo34` 的 `columnExists` 守卫 ⇒ 两个迁移幂等用例变红（第二次 `ALTER` 报 duplicate column）。

  ⚠️ **本轮的一个"假绿"教训（写断言时踩到）**：最初"不完整列举⇒放弃"的用例里 **`remote` 是空的**，于是它被**闸 2** 顺带挡住；把闸 1 拆掉用例**照样通过**——断言测到的是闸 2，不是它标称的闸 1。改成"不完整列举 **+ 远端有可取记录**"后，闸 1 才被单独逼出来。⇒ **一条断言若同时被两道闸覆盖，拆任意一道都可能不变红；写"守某一道闸"的用例时，必须先确认其余各道闸对它都是放行的。**

  **仍未做（明确的下一步）**：**真实的 `LocalRecordStore` 实现**（把 `transactions`/`accounts` 的字段映射出来；本轮的 `SyncRound` 只用内存 store 与假 transport 验证接线）；v1 单向加密备份 + 下载恢复；冲突中心 UI（§7.2 的三方差异视图，`ConflictReview` 已产出）；`device_id` / `modified_at` 的传播（墓碑已带 `device_id`，尚未上传）。

- **✅ 实现进度（2026-10-12，第十轮 KFCSBYTX）：②-v2 的落盘层（真实 `LocalRecordStore`：账号）**

  编排层把"一轮该改什么"跑通了，但它只对**内存 store + 假 transport** 验证过接线。本轮补上**真实的本地存储实现**：`DbLocalRecordStore` 把 `accounts` 表接进接缝（含纯映射 `AccountFields`），并顺手修掉接缝两端的两个「**把失败说成没有**」的坑。协议约定见 §7.8。仍不含交易/分录、不含 UI、不含加密备份。

  | 已交付 | 落点 |
  |---|---|
  | **`AccountFields`**（纯）：账号的**唯一**线上形状，**键 = `accounts` 的列名**。往返无损；布尔一律写出、可空量省略；严格解码（缺 uid/name/type/currency ⇒ `null`，未知键忽略）。刻意不同步 `full_name` / `color` / `created_at` / `modified_at` / 余额（各有理由，见 §7.8） | `sync/AccountFields.kt`（新，零 Android 依赖） |
  | **`DbLocalRecordStore`**：`list` 用 `getAllRecords(null, null)`（**含隐藏账号**，只排除 ROOT）／`write` 走 `REPLACE` 同 uid 就地更新／`delete` 用**非级联**的 `deleteRecord`；四种拒绝（载荷 uid 与 record key 不符 / ROOT / 商品解析不出 / 父账号尚未到本机） | `java/.../sync/DbLocalRecordStore.kt`（新） |
  | **接缝契约收紧**：`LocalRecordStore.write` 返回 `Boolean`（"能否落地"）＋新增 `supportedTypes`；`list` 遇不支持的类型**抛 `UnsupportedRecordTypeException`**（**不返回空表**） | `sync/LocalRecordStore.kt` |
  | **编排侧配合**：`PULL` 尊重 `write` 的返回值（`false` ⇒ **绝不回写基线**，记入新增的 `SyncRoundResult.deferred`）；`materialize` 跳过不在本轮 `types` 里的远端类型 | `sync/SyncRound.kt` |
  | **断言** | `AccountFieldsTest`（14，纯 JVM）+ `DbLocalRecordStoreTest`（19 ×2 SDK，Robolectric 打真库）+ `SyncRoundTest` 补 1 例（被拒的 `PULL` 不回写基线、对端副本跨轮存活） |

  **两条设计决定（本轮定死）**：
  1. **"我做不到"绝不能表达成"没有"**：`list` 对不支持的类型**抛异常**（空表会被合并读成"本地全删了"⇒ 删掉对端）；`write` 被拒时**不写基线**（记了基线的"未写入记录"下一轮会被读成"本地删了它"⇒ 删掉对端）。两处都是"形状相同、含义相反"。
  2. **隐藏账号是用户数据**：`list` 只能用原生 `getAllRecords`，**不能**用界面查询 `fetchAllRecords()`（它排除隐藏账号与 ROOT）—— 否则"用户隐藏了账号"会被读成"用户删了账号"。

  **咬合验证（六处，各自拆掉后对应用例变红，恢复后转绿）**：
  - `list` 改用 `fetchAllRecords()` ⇒ `a hidden account is still listed` 变红；
  - `list` 对不支持类型改为返回空表 ⇒ `asking for a type the store cannot represent is an error, not an empty answer` 变红；
  - `write` 去掉商品可解析检查 ⇒ `a record whose commodity cannot be resolved is refused` 变红；
  - `write` 去掉父账号在场检查 ⇒ `a child whose parent is not in the book yet is deferred, not written` 变红；
  - `delete` 改用 `recursiveDeleteAccount` ⇒ `deleting an account does not take its sub-accounts with it` 变红；
  - 编排忽略 `write` 的返回值（无条件回写基线）⇒ `a declined pull is not re-based, and the peer's copy survives the next rounds` 变红。

  ⚠️ **本轮最值钱的一处修正**：`LocalRecordStore.write` 原本返回 `Unit`，"store 拒绝写入"这件事在编排层**没有任何表达方式**。沿用原签名时，`execute` 会对一条**没写进本地**的记录照常 `putBase` —— 下一轮 `local 缺席 / base 在场 / remote 在场` 恰好就是"本地删除了它"，于是**删掉对端的记录**。这是"把一种失败压成一个沉默"的典型：不是逻辑写错，而是**类型系统里没有地方放这个事实**。

  **仍未做（明确的下一步）**：**交易 / 分录（`SPLIT`）的字段映射**（一条交易离开 splits 没有意义，需要子编码如 `split.0.uid=…` 与顺序保证；这也让 `sync_state`/`tombstones` 的多类型设计真正上场）；v1 单向加密备份 + 下载恢复；冲突中心 UI；`device_id` / `modified_at` 的传播。

- **✅ 实现进度（2026-10-12，第十一轮 KFCSTX）：②-v2 的落盘层扩展（真实 `LocalRecordStore`：交易 + 分录）**

  第十轮只把 `accounts` 接进接缝；而**一条交易离开它的 splits 没有意义**，所以本轮把 `transactions` 与其 `splits` 作为**一条整体记录**接进来（分录子编码 `split.<index>.<列名>`，索引唯一确定顺序），并顺手堵上"这轮不写"的两处**静默失败**。协议约定见 §7.8。仍不含 UI、不含加密备份。

  | 已交付 | 落点 |
  |---|---|
  | **`TransactionFields`**（纯）：交易的**唯一**线上形状，**键 = `transactions` 的列名**（连 `name`=description / `description`=notes 这对**名字反了**的列也逐一钉住）；**分录内联**为 `split.<index>.<列名>`，索引 = 顺序（**必须连续 `0..n-1`**，缺一个整条 `null`）；金额走**原始分数** `value_num/denom`（交易商品）与 `quantity_num/denom`（分录账号商品）；布尔一律写出；**`NEVER` 的 `reconcile_date` 以「键缺席」表达**；`isDeclaredKey`/`declaredSubset` 支撑"载荷 == 落库结果"的前向兼容比较 | `sync/TransactionFields.kt`（新，零 Android 依赖） |
  | **`DbLocalRecordStore` 扩展**：`supportedTypes` = {ACCOUNT, TRANSACTION}；`list` 用 `getAllRecords(null,null)` 过滤 `isSyncable`（**排除模板**）；`writeTransaction` 五道闸（见下）；`delete` 拒绝模板、走 `deleteRecord`（分录随 `ON DELETE CASCADE` 一起走）；**`list`/`write`/`delete` 遇 `SPLIT` 仍抛异常** | `java/.../sync/DbLocalRecordStore.kt` |
  | **断言** | `TransactionFieldsTest`（21，纯 JVM）+ `DbLocalRecordStoreTest`（19 → **40** ×2 SDK，Robolectric 打真库） |

  **交易写入的五道闸**：① 载荷不可解 / uid 与 record key 不符 / **模板** ⇒ 拒；② 交易商品解析不出 ⇒ 拒；③ 某条分录的账号不在本机 ⇒ **整条延后**（不是跳过那一条分录 —— splits 的外键与分录的 quantity 商品都指向它）；④ splits **不配平** ⇒ 拒（`TransactionsDbAdapter.addRecord` 会**随手建一个"不平衡"账号 + 分录**来"修"它 —— 采用对端记录绝不能在本机凭空造账号）；⑤ **"落库结果 ≠ 载荷" ⇒ 拒**（模型会规范化：description `trim`、`Money` 按商品小数位重推分子分母、未知 split type 静默退化 `DEBIT`、重复 split uid 被丢弃 —— 任一发生，下一轮就把差异读成本地编辑，**永久来回推**）。第⑤道刻意做成**一处中心比较**，而不是逐条枚举规范化。

  **本轮最值钱的一处修正（咬合验证挖出来的）**：原代码在写之前先 `transactions.getRecordOrNull(uid)?.let { transaction.id = it.id }`，注释称"不给出 id，适配器就不会清扫被删掉的分录，残留分录会让记录永远对不上基线"。**把这个变异拆掉后，用例全绿** —— 顺着查下去发现：`addRecord` 默认 `UpdateMethod.Replace` ⇒ `REPLACE INTO transactions` ⇒ 父行被删重插 ⇒ `splits.transaction_uid` 的 **`ON DELETE CASCADE`**（`PRAGMA foreign_keys=ON`）**已经把该交易的分录全清了**，于是适配器自己那句"清扫不在集合里的分录"在此路径上是**空操作**（且 `bindBaseModel` 只绑 uid，`model.id` 根本不进 SQL）。⇒ 那行是**死代码 + 一句不成立的注释**，已删除；把**真正的机制**钉进测试：新增 `the adapter drops a transaction's old legs through REPLACE alone`（拿一个 **id=0 的新模型**走适配器，证明无需被告知"行已存在"也能替换整套分录）。

  **咬合验证（十处，各自拆掉后对应用例变红、恢复后转绿；统一 `--rerun-tasks --no-build-cache`）**：
  - `list` 的交易过滤改成恒真 ⇒ `a template transaction is never listed` 变红；
  - 去掉模板写入拒绝 ⇒ `a template payload is refused` 变红；
  - 去掉配平闸 ⇒ `a transaction whose splits do not balance is refused, and mints no imbalance account` 变红（会凭空多出一个账号）；
  - 去掉"落库≠载荷"闸 ⇒ `a payload the model cannot reproduce is refused` 与 `a payload with two legs sharing one uid is refused` 变红；
  - 去掉模板删除拒绝 ⇒ `a template transaction cannot be deleted` 变红；
  - `isDeclaredKey` 对任意 `split.<i>.<field>` 都返回 true ⇒ `the columns that are device-local are not on the wire` 变红（对端新字段会被误判成致命）；
  - 分录索引空缺改成 `continue` ⇒ 严格索引连续性用例变红；
  - 去掉分母 `≤ 0` 检查 ⇒ 解码严格性用例变红；
  - `reconciledDate` 改成恒带 ⇒ `every transaction in the book is listed with its splits` 变红（`NEVER` 会被当成一个巨大负日期发给对端）；
  - **`PRAGMA foreign_keys=OFF`** ⇒ `the adapter drops a transaction's old legs through REPLACE alone` + `writing a transaction replaces its whole split set` + `deleting a transaction removes it and its splits` **同时变红**（证明"整套分录被替换 / 随记录删除"这条性质真的被机制守着）。

  ⚠️ **教训（已进环境文档 K17）**：变异"没咬"**未必**是断言空壳 —— 先确认**保护是不是来自你变异的那一行**。此处保护来自 `REPLACE` + `ON DELETE CASCADE`（schema 与适配器默认），**不在** store 那一行。判据：把**机制**本身（`PRAGMA foreign_keys` / schema / 默认 `UpdateMethod`）变异一次，看对应用例是否变红。

  **仍未做（明确的下一步）**：**父账号被删留下的悬空 `parent_account_uid`**（使该子账号**永久延后**，可见于 `SyncRoundResult.deferred`，修它是独立改动）；**账号删除与交易删除的交叉**（删账号经 `ON DELETE CASCADE` 会带走该账号的分录，等于**间接**改动某条交易，尚未与交易的合并规则对齐）；v1 单向加密备份 + 下载恢复；冲突中心 UI；`device_id` / `modified_at` 的传播。

- **✅ 实现进度（2026-10-12，第十二轮 KFCSTX）：父引用不可解一族（ROOT 归一化 + 删账号修悬空子引用）**

  第十一轮留了两条"父引用"缺口：唯一的**不收敛**延后情形（父被删留下的悬空 `parent_account_uid`），以及顶级账号的父引用。动手前先确认了一条**比预期严重得多**的事实：顶级账号的 `parent_account_uid` **就是本书 ROOT 的 uid**（`bind` 给无父账号补上），而 ROOT 的 uid 是**建书设备铸的** ⇒ 照发上线，对端**每一个顶级账号**都永久延后。⇒ 两者是同一族问题，一起收口：**父引用只允许是双方都能解出的账号**。

  | 已交付 | 落点 |
  |---|---|
  | **ROOT 归一化（读侧）**：`list` 把"父 = 本书 ROOT"读成**无父**（`toFields(rootUid)`）—— 线上「无父」正是「顶级账号」的表达，接收端 `bind` 落到**它自己的** ROOT | `java/.../sync/DbLocalRecordStore.kt` |
  | **删账号修悬空子引用**：`delete()` 成功后 `reassignDescendantAccounts(uid, rootAccountUID)` —— **库里真的**把后代重挂到本机 ROOT 并重算 `full_name`（原 `deleteRecord` 只修内存缓存，而 `bind` 走父链 ⇒ 悬空子账号永久不可写） | 同上 |
  | **断言** | `DbLocalRecordStoreTest` 40 → **44** ×2 SDK（新增 4 例；sync 包共 **311** 例） |

  **咬合验证（两处，各自拆掉后对应用例变红、恢复后转绿；统一 `--rerun-tasks --no-build-cache`）**：
  - 读侧 `parentUid = parentUID.takeIf { it != rootUid }` 改回 `parentUID` ⇒ `a top level account travels without a parent reference` + `a peer's top level account is attached to the local root` 变红（对端会拿到一个它永远解不出的 uid）；
  - 去掉 `reassignDescendantAccounts(...)` ⇒ `deleting an account re points its children at the local root` + `a child can be written again once its parent is deleted` 变红（两处各 2 例 ×2 SDK，共 8 红）。

  ⚠️ **一处断言写法上的自省**：修好子引用后 `getRecordOrNull(child.uid).parentUID` 会是 ROOT —— 但这个值**可能来自内存缓存**（`deleteRecord` 的缓存修复），证明不了"库里真改了"。改用 `getAttribute(uid, COLUMN_PARENT_ACCOUNT_UID / COLUMN_FULL_NAME)` **直接读行**：缓存修复**不会**重算 `full_name`，所以"父 = ROOT **且** `full_name` = 自己名字"同时成立，才说明库被真修过。

  **仍未做（明确的下一步）**：v1 单向加密备份 + 下载恢复；冲突中心 UI；`device_id` / `modified_at` 的传播。（原先列的"账号删除与交易删除的交叉"已由第十三轮收口，见下。）

- **✅ 实现进度（2026-10-12，第十三轮 KFCSTX）：删账号 × 交易的交叉（删账号不再静默改写交易）**

  第十二轮 §7.8 留了已知限制 ④：store 删账号用的是**非级联**的 `deleteRecord`，但**数据库会**级联 —— `splits.account_uid` 是 `ON DELETE CASCADE` 且 `PRAGMA foreign_keys=ON`。后果：删一行账号会**悄悄**带走它的分录，某条交易因此**少一腿（甚至变成无腿）**、**不配平**；而交易写入的第 8 道闸**拒收不配平载荷** ⇒ 这条交易**永远推不出去**，两侧永久不一致。即：一次账号删除**间接改写了另一个 record type**（交易），而合并从未同意过。

  ⇒ 本轮把 store 的**意图变成可执行的约束**：**账号只有在无人引用时才允许删除**。

  | 已交付 | 落点 |
  |---|---|
  | **删账号前置闸**：`deleteAccount` 在**该账号仍被任何分录引用**时**拒绝（延后）** —— 返回 `false`、不写任何东西 | `java/.../sync/DbLocalRecordStore.kt` |
  | 依赖：构造增加 `splits: SplitsDbAdapter`（用 `getRecordsCount("account_uid = ?")` 判引用） | 同上 |
  | **断言** | `DbLocalRecordStoreTest` 44 → **46** ×2 SDK（新增 2 例；sync 包共 **315** 例） |

  **为什么是"延后"而不是"连带删交易"**：交易是**它自己的** record type，有自己的墓碑与两轮确认规则；合并没同意删它，store 就不能替它做决定。交易的腿自己消失（由交易的合并规则决定）之后，账号**下一轮**就能删 —— 与第十二轮"父引用"一族同形：**明确地延后，绝不静默改写**。

  **与 app 自身规则的一致性**：界面侧的快捷删除本就**先查** `getTransactionCount(uid) == 0 && getSubAccountCount(uid) == 0`，满足才走普通 `deleteRecord`（有交易则弹确认框走 `recursiveDeleteAccount` / 先移动分录）。而 `getTransactionCount` 按 `splits.account_uid` 统计 ⇒ **"0 交易" ⟺ "0 分录"** ⇒ 界面那条路**不会**触发级联损坏。本轮补的正是**同步路径**上缺失的同一个不变量。

  **咬合验证（两处，统一 `--rerun-tasks --no-build-cache`）**：
  - 闸改成 `if (false)`（永不拒绝）⇒ `deleting an account that still has splits is deferred, and damages nothing` + `an account can be deleted once nothing posts to it` 变红（2 例 ×2 SDK = **4 红**；报"应为 false 实为 true"）；
  - 闸改成 `>= 0L`（**过度拒绝**：连无分录账号也删不掉）⇒ `deleting removes the account and is idempotent` + `deleting an account does not take its sub-accounts with it` + `deleting an account re points its children at the local root` + `an account can be deleted once nothing posts to it` 变红（4 例 ×2 SDK = **8 红**）⇒ 闸的**粒度**（`> 0L` 而非 `>= 0L`）同样被钉住。

  **仍未做（明确的下一步）**：v1 单向加密备份 + 下载恢复；冲突中心 UI；`device_id` / `modified_at` 的传播。

- **✅ 实现进度（2026-10-12，第十四轮 KFCSTX）：②-v1 单向加密备份 —— 自描述容器 + 远端备份/恢复编排**

  承接第十三轮下一步的第 ① 项。②-v2（双向合并）那条线已到落盘层；本轮把规划里明确**更早、风险更低**的 **v1（单向备份 + 下载恢复）** 做掉，并且**只做它的纯核心**（真实 WebDAV 与设置页的接线留下一步）。理由：**"加密写错"的代价是永久数据报废**，这一层必须先成为**可穷举断言、无 Android 依赖**的东西 —— 与第六轮先把合并内核做成纯函数同理。

  | 已交付 | 落点 |
  |---|---|
  | **自描述加密容器**：文本头（magic + `version`/`kdf`/`iterations`/`cipher`/`keybits`/`salt`/`iv`）+ 二进制密文体；PBKDF2-HMAC-SHA256 → AES-256-GCM；**头部字节作 AAD**；`isEncrypted()` 魔数判定；`Malformed`/`UnsupportedVersion`/`WrongPassword` 三类**类型化拒收**；迭代数**可注入**（测试 1000 / 生产 600k） | `sync/EncryptedBackup.kt`（新，零 Android 依赖） |
  | **单向远端备份/恢复编排**：`/backup/<bookUid>.gnca` **单一固定路径**（内容 magic 区分加不加密）；无口令 ⇒ 明文原样上行、有口令 ⇒ 容器；**硬守卫**：远端是密文而无口令 ⇒ `PassphraseRequired`、**零字节返回** | `sync/RemoteBackup.kt`（新，零 Android 依赖） |
  | **断言** | `EncryptedBackupTest` **24** 例 + `RemoteBackupTest` **16** 例（均纯 JVM、各跑一次）⇒ sync 子集 13 类/315 例 → **15 类/355 例** |

  **四条设计决定（本轮定死）**：
  1. **头部必须被认证（AAD）**：不认证时，改 `iterations`/`salt` 要**等到产出乱码**才暴露；认证之后，头部篡改与口令错**共用同一条失败路径** ⇒ 失败面从两条收敛为一条。
  2. **版本号是唯一前向兼容闸**：算法名按版本钉死 ⇒ 头部里"不认识的算法"判**损坏**，不判"更新的文件"（真正的更新会以新版本号到来）。
  3. **远端只有一条路径**：按模式分两个文件名会让"切换模式的上传"留下**陈旧兄弟文件**，之后恢复得**猜**哪个是当前的 —— 猜错就恢复出一本已被淘汰的书；扩展名还是"人"和 GnuCash 认这个文件**身份**的地方。
  4. **硬守卫（密文 + 无口令 ⇒ 拒收、零字节）**：把密文当书交回 = "恢复说成功、账本变乱码"的**持久静默**故障 —— 这正是本功能存在的理由。

  **咬合验证（5 处变异 / 分两批，纯 JVM 各跑 1 次 ⇒ 共 18 红）**：
  - **批 A**（① 头部 AAD 关掉 ② 版本闸关掉 ③ 无口令硬守卫关掉）：`a header that gains a line is refused`（**只有它**能隔离"头部被认证"这件事 —— 它改的是**不参与解析**的头部字节）、`a container from a newer format version is refused as such`、`an encrypted backup is refused rather than returned when no passphrase is given`、`an empty passphrase does not open an encrypted backup`、`a backup written by a newer version is reported as unreadable` 变红（**5 红**）；
  - **批 B**（① GCM 认证失败被吞成"返回未认证字节" ② 解析忽略头部迭代数）：`a wrong passphrase is refused as a wrong passphrase`、`a body byte flipped after the fact is refused`、`a header whose iterations were rewritten is refused`、`a synthetic container with a valid header but made-up key material is refused`、`a wrong passphrase is reported as a wrong passphrase`（`WrongPassphrase` → `Got`）族，加上四条往返用例（密钥用错 ⇒ 解出乱码）与 `a container with a non-numeric iteration count is refused`、`an upload with a passphrase stores an encrypted container`、`a download of an encrypted backup returns the book in the clear` 变红（**13 红**）。
  - 两批各自点名到**具体用例**；结束后 `grep -rn BITE app/src` 清零。

  **出包（本轮含 BY）**：`:app:testDevelopmentDebugUnitTest :app:assembleDevelopmentDebug --rerun-tasks --no-build-cache` → `BUILD SUCCESSFUL in 50m33s`（EXIT=0，`55 actionable tasks: 55 executed`）；产物 `app-development-debug.apk` = **41,372,674 B / 39.46 MiB**，sha256 `934d9d72f39bf46093339bdccf8e8a4948ecf9f445dc8523aa1141efe6bd6946`（2026-10-12 12:21）。
  - **dex 字面量硬核**（证明新代码真进包、不是打了旧包）：`BEGIN GNUCASH POCKET BACKUP` / `PBKDF2WithHmacSHA256` / `AES/GCM/NoPadding` 在 `classes*.dex` 中**各命中 1**。
  - ⚠️ **体积异常（已记录，非故障）**：自第十一轮的 **44,182,697 B（42.1 MiB）回落 2.68 MiB** 至 39.46 MiB。本轮是**首次对出包也加 `--rerun-tasks --no-build-cache` 的干净重建**，结果与 **V12 首建的 39.3 MiB** 同量级（差额 = 九轮新增代码）⇒ 此前那个 44.18 MB 更可能是**增量/缓存态**产物。按 **K16**：判产物只认 sha256 / mtime / dex 内容，不看体积。

  **仍未做（明确的下一步）**：把 `RemoteBackup` 接到真实 WebDAV 与设置页（口令输入 / 首次二次确认 + 不可逆警示 / "加密后桌面 GnuCash 打不开"提示 / 备份与恢复入口）；冲突中心 UI（`ConflictReview` 已产出）；`device_id` / `modified_at` 的传播。

- **✅ 实现进度（2026-10-12，第十五轮 KFCSTX）：②-v1 接线第一半 —— WebDAV 连接校验 + 恢复载荷守卫（纯核心）**

  承接第十四轮下一步的第 ① 项。第十四轮把“加密写错就永久报废”的那一层做成了纯函数；本轮做**同一句“接到真实 WebDAV”里不需要网络的那一半**：**用户在设置页填的东西到底能不能用**，以及**从网上拿回来的字节到底是不是一本书**。设置页 UI、口令输入、Android 侧凭据存储与 transport 装配留下一轮 —— 与第六轮先把合并内核做成纯函数、第十四轮只做 v1 纯核心同理。

  | 已交付 | 落点 |
  |---|---|
  | **连接配置的类型化校验 + 归一化**：`WebDavConnection(serverUrl, username, password, trustAllCertificates, allowPlaintext)` → `check()` → `Check.Ready(baseUrl, …)` / `Check.Invalid(Problem, detail)`；11 类问题（`BLANK` / `MISSING_SCHEME` / `UNSUPPORTED_SCHEME` / `MISSING_HOST` / `CREDENTIALS_IN_URL` / `QUERY_OR_FRAGMENT` / `UNESCAPED_CHARACTER` / `ENCODED_SEPARATOR` / `RELATIVE_SEGMENT` / `PLAINTEXT` / `PASSWORD_WITHOUT_USER`）；`Ready.connect(WebDavTransportFactory)` 是纯核心与 OkHttp 之间**唯一**的接缝；`toString()` 脱敏 | `sync/webdav/WebDavConnection.kt`（新，零 Android 依赖） |
  | **恢复载荷守卫**：魔数**单点**（gzip / zip×3 / sqlite / XML）+ `classify(prefix)` / `inspect(bytes)` → `Acceptable(kind)` / `Rejected(EMPTY / WEB_PAGE / UNRECOGNISED)`；`ImporterFactory.getInputStream` **改为委派 `classify`**（消除双嗅探漂移） | `util/BookPayload.kt`（新，零 Android 依赖）+ `importer/ImporterFactory.kt`（改） |
  | **接线**：`RemoteBackup.open()` 在**明文与解密后**统一过守卫 ⇒ 新增第六态 `BackupDownloadOutcome.NotABook(detail)` | `sync/RemoteBackup.kt`（改） |
  | **断言** | `BookPayloadTest` **18** 例（纯 JVM）+ `WebDavConnectionTest` **27** 例（纯 JVM）+ `RemoteBackupTest` 16 → **21** 例 ⇒ sync+util 子集 23 类 / **481** 例全绿（实测 4m37s） |

  **四条设计决定（本轮定死）**：
  1. **“200 不等于一本书”必须自成一道闸**：恢复流程会把**下载到的字节先写回本地账本**、再交给导入器，所以“服务器回了个登录页”若等到导入器报错，账本**已经被覆盖**了 —— 错误是响的，但**已经晚了**。守卫把失败变成“响亮且无害”，并且**报 `NotABook` 而不是 `Failed`**：网络被拒、被喂了一个登录页、文件是我们自己的但坏了，三者用户的下一步动作不同。
  2. **魔数只能有一份**：`ImporterFactory` 认识哪些容器，恢复就该接受哪些 —— 两份拷贝会在任一方学会新容器的那天漂移。集中到 `BookPayload` 之后，**顺带把一个死分支暴露了出来**（见下）。
  3. **连接校验必须是“值”而不是异常**：`Problem` 让设置页能说清“是地址少了 `https://`，还是填了口令却没填用户名”，而不是抛一个没人接的异常。校验**从不抛**（用例把 19 种畸形输入跑一遍）。
  4. **接缝用工厂而不是传值**：`connect(newTransport)` 把**刚校验过的**凭据交给工厂，调用方在结构上**不可能**拿一个校验过的连接去配一个用别的东西搭出来的 transport。

  **⚠️ 顺带修掉的既有缺陷（第十四轮之前就在）**：`ImporterFactory.getInputStream` 把 4 字节 zip 魔数按**小端**拼装（`b0 | b1<<8 | b2<<16 | b3<<24`），而常量写的是**大端** ASCII `PK\x03\x04`（`0x504B0304`）⇒ 三个 `ZIP_MAGIC*` 比较**永远不相等**、那个分支是**死代码**，任何 `.gnca.zip` / `.gnucash.zip` 备份都会落到 XML 导入器上失败（而 `BackupFileFormat` 一直**接受**这些文件名 ⇒ “被接受的文件名”与“能导入的内容”对不上）。集中到 `BookPayload.classify` 后按大端修正，由 `every zip signature is accepted` + `every container a book can arrive in still comes back as a book` 两例咬住（本轮**批 D** 专门只注入这一处变异）。**无既有测试依赖旧行为**（无 zip 测试资源、无单测走那条分支）。

  **咬合验证（8 处变异 / 分 4 批，纯 JVM 窄过滤各跑一次 ⇒ 共 32 红）**：
  - **批 A**（① `inspect` 的 `when` 把一切当 SQLITE ⇒ 全部放行）：**18 红** —— 接受类 7 例（gzip / zip / xml / BOM / 两字节 gzip / 容器压过网页 / XML 根）+ 拒绝类 8 例（网页 / 注释后网页 / 大小写网页 / JSON / 代理纯文本 / 任意二进制 / 截断 sqlite / detail 文案）+ `RemoteBackupTest` 3 例（登录页 / JSON 体 / 加密容器内非书）。⚠️ `an empty 200 …` **没红** —— 它在更早的 `EMPTY` 闸就被拦下，正说明两道闸是分开的。
  - **批 B**（① 去掉 `EMPTY` 闸 ② zip 端序改回小端 ③ `RemoteBackup` 守卫整段去掉）：**7 红** —— `an empty payload is refused as empty`、`the detail of each refusal names what was wrong`（①②）、`an empty 200 is refused rather than treated as an empty book`（①③）、`every zip signature is accepted`（②）、`a login page served with a 200 …`、`a body with no recognisable container is refused`、`the guard also runs on what an encrypted container holds`（③）。
  - **批 C**（① 去掉 `CREDENTIALS_IN_URL` 闸 ② 去掉 `PLAINTEXT` 闸 ③ 不做 authority 大小写折叠 ④ 去掉 `PASSWORD_WITHOUT_USER` 闸）：**5 红** —— `credentials inside the address are refused …`（①）、`plain http is refused unless it is allowed on purpose`（②）、`the scheme and host are lower cased while the path keeps its case` + `connect hands the checked credentials over and writes to the normalised root`（③）、`a password without a user name is refused`（④）。
  - **批 D**（只注入 zip 端序一处）：**2 红** —— `every zip signature is accepted` + `every container a book can arrive in still comes back as a book` ⇒ 缺陷修复被**独立**钉住（批 B 里它被“守卫整段去掉”掩盖了一例）。
  - 四批结束后临时注入脚本删除，逐处 `grep` 回读原始行（`shl 24` / `bytes.isEmpty()` / `authority.lowercase()` / `Problem.CREDENTIALS_IN_URL` / `Problem.PLAINTEXT` / `Problem.PASSWORD_WITHOUT_USER` 各就位），`grep -rn BITE app/src` **清零**。

  **仍未做（明确的下一步）**：冲突中心 UI（`ConflictReview` 已产出，并已随一轮结果回传给界面，见**第二十二轮**）；`device_id` / `modified_at` 的**跨设备传播**。（本条原有第 ① 项「设置页 UI + `Problem` 出文案 + Android 侧凭据存储与 transport 装配」已由**第二十一轮**完成；第 ② 项「把一轮 `SyncRound` 接到界面」已由**第二十二轮**完成，均见下。）

- **✅ 实现进度（2026-10-14，第二十一轮 KFCSTX）：②-v1 接线第二半 —— WebDAV 设置页 + 凭据存储 + 备份/恢复入口**

  承接第十五轮下一步的第 ① 项：第十五轮做完了"**填的东西能不能用**"（`WebDavConnection.check()` 的 11 类 `Problem`）与"**拿回来的字节是不是书**"（`BookPayload`），本轮把这些接到**用户在设置页真的能按的按钮**上。纯核心一点没动，只加编排 + UI + 存储。

  | 已交付 | 落点 |
  |---|---|
  | **校验在拨号之前（唯一漏斗）**：`checkedFirst(connection)` —— `check()` 判 `Invalid` 就返回 `NotUsable(problem, detail)`，**一个网络请求都不发** | `sync/WebDavSyncController.kt`（**新**） |
  | **口令策略纯函数**：4 条规则、**顺序即优先级**（空口令 / 只填确认框 / 两次不一致 / 未勾"我知道远端那份是密文"）；`Use` 持 `CharArray`、`toString()` **脱敏** | `sync/RemotePassphrasePolicy.kt`（**新**） |
  | **远端状态一律用类型表达**：13 态 `BackupNotice` + 5 态 `ConnectionNotice`；`status == STATUS_NO_RESPONSE`（**根本没连上**）与 4xx/5xx（**连上了但被拒**）**分成两条** | `sync/BackupNotice.kt`（**新**） |
  | **空导出拒上传 / 下载只有成功才带字节**：书为空或读不出 ⇒ `WebdavExportFailed`，**绝不把远端那份覆盖成空文件**（＝ v1 守卫在写入侧的对称物） | `sync/AndroidBookTransfer.kt`（**新**）、`sync/WebDavSyncController.kt` |
  | **凭据存储**：读**默认** SharedPreferences（与 `androidx.preference` 写的是**同一份**）；地址/用户名/口令/两个开关**可选存**，半填地址允许存（用户正在打字）；**备份口令不落盘**、按动作输入 | `ui/settings/WebDavPreferences.kt`（**新**） |
  | **值→文案单点**：`WebDavNoticeText` 把每条 `Problem` / `BackupNotice` / `ConnectionNotice` 映到具体措辞，`preferenceKeyOf` 把出错项**点到具体那一行** | `ui/settings/WebDavNoticeText.kt`（**新**） |
  | **设置页接线（只加 8 行 UI）+ 口令对话框 + 恢复二次确认** | `ui/settings/BackupPreferenceFragment.kt`（改）、`res/xml/fragment_backup_preferences.xml`（改）、`res/layout/dialog_backup_passphrase.xml`（**新**） |
  | **断言（新增 6 类 / 73 例）** | `.../test/unit/sync/{RemotePassphrasePolicyTest,BackupNoticeTest,WebDavSyncControllerTest}.kt`（纯 JVM）、`.../ui/settings/{WebDavNoticeTextTest,WebDavPreferencesTest,BackupPreferenceScreenTest}.kt` |
  | 咬合（5 处变异 / **7 条红**） | A1 去掉"两次输入必须一致"⇒2 红 · A2 无响应判反⇒1 · A3 `Transport` 说成"不是 WebDAV 根"⇒1 · A4 加密标志取反⇒2 · A5 放开空导出上传⇒1；逐处点名到用例、`grep` 回读**零残留** |
  | 单测 | 见 E13（全量 **86 类 / 1430 例 / 0 失败**） |
  | **本轮不含 BY** | APK 沿用第二十轮 `debae437b98e3ffa9745a937747e922ac2fd9e123413658bce788a79c3bbf6e0` |

  **三条设计决定（本轮定死）**：
  1. **校验不是"顺手也校验一下"，而是唯一入口**：设置页**允许存半填的地址**（那正是用户在打字的时刻），所以"能不能用"只能在**动作发生的那一刻**判定。`checkedFirst` 把这条读法变成**结构上的**：任何新动作只要走这个漏斗，就拿不到未校验的连接 —— 半填地址的代价上限是**一条 `NotUsable` 提示**，而不是一次超时/一个 401。
  2. **"没连上"与"被拒"必须分开**：`STATUS_NO_RESPONSE`（超时 / DNS / 拒绝连接）告诉用户"查地址与网络"；4xx/5xx 告诉用户"查凭据 / 权限 / 服务端"。合成一个"备份失败"会让人去改**已经对**的那一项。
  3. **口令不进存储**：远端那份是密文，而解它的秘密若与账本**同处 app-private 目录**，加密就只是形式。口令按动作输入、用完即弃（`CharArray`，不进 `toString`、不进日志）。

  **⚠️ 新增 K21（写测试时踩的两处）**：① **设置页 inflate 只有一种姿势** —— `addPreferencesFromResource` 是 **Fragment** 的方法，`PreferenceScreen` 上没有，正确写法是 `PreferenceManager.inflateFromResource(context, R.xml.…, manager.createPreferenceScreen(context))`，且 inflate 出的 screen 要 `by lazy` **缓存**（每次 lookup 重建一份，"某行只在其中一份里缺失"会被自己掩盖）；② **读那份配置必须读** ***默认*** **SharedPreferences** —— `androidx.preference` 写的是 `PreferenceManager.getDefaultSharedPreferences(context)`（文件 `<包名>_preferences`），照抄 `OwnCloudPreferences` 的 `context.getSharedPreferences("owncloud_pref", MODE_PRIVATE)` 读的是**另一份文件**，症状是"我改了地址，它还是连旧的"（那是**历史遗留**写法，新代码别跟）。详见环境文档 **K21**。

- **✅ 实现进度（2026-10-14，第二十二轮 KFCSTX）：②-v2 接线第一半 —— 手动「一轮同步」+ 冲突评审回传**

  承接 §7.9：§7.7 / §7.8 的 `SyncRound` 已经能在纯 Kotlin 里跑完一轮，本轮把它接到设置页（「立即同步」行 + 双向开关），并把原先被丢掉的 `ConflictReview` 一路带出来。**不含自动同步、不含冲突中心 UI、不含出包**。

  | 已交付 | 落点 |
  |---|---|
  | **一轮结果的描述收成纯函数**：8 态 `SyncNotice`（`NOTHING_TO_DO`/`SYNCED`/`DEFERRED_SOMETHING`/`CONFLICTS_TO_REVIEW`/`SOMETHING_FAILED`/`RESTART_NEEDED`/`WRONG_FOLDER`/`LISTING_INCOMPLETE`）+ 2 态 `SyncAbandon` + `SyncNotices.of(result)` 按**严重度**取胜出分支；`deservesCounts` 仅 `NOTHING_TO_DO` 为 false | `sync/SyncNotice.kt`（新，零 Android 依赖） |
  | **`SyncOutcome` 三态 + 薄适配层 `SyncRunner`**：`connection.check()` 是**唯一漏斗**（`Invalid` ⇒ `NotUsable`，**零请求**）；`Ready` ⇒ `try { SyncRound(...).run() } catch (e) { SyncOutcome.Failed(e.message) }` | `sync/SyncRunner.kt`（新） |
  | **设备 id read-or-create**：读**默认** SharedPreferences 的 `key_sync_device_id`，空则生成 UUID 并写回（**永不 regenerate** —— 那会让墓碑表整体失效、已删记录在对端复活） | `sync/AndroidSyncDeviceId.kt`（新） |
  | **值 → 文案单点映射** | `ui/settings/SyncNoticeText.kt`（新）：`of(notice)` 8 分支 + `of(outcome)`（`NotUsable` 委派 `WebDavNoticeText.of(problem)`、`Failed` ⇒ `webdav_sync_broke`、`Ran` ⇒ 委派回 `of(notice)`） |
  | **`SyncRoundResult` 修形**：`abandonReason: SyncAbandon?` 新字段；`reviews: List<ConflictReview>` **取代** `conflicts: Int`（`conflicts` / `movedAnything` 降为**派生**属性） | `sync/SyncRound.kt`（改） |
  | **双向开关是显式 opt-in**：`var twoWaySync` 独立于 `isConfigured`、默认 false（否则"只填了个地址"就被默许接受对端删除） | `ui/settings/WebDavPreferences.kt`、`res/values/donottranslate.xml`（`key_webdav_two_way_sync` / `key_webdav_sync_now` / `key_sync_device_id`） |
  | **设置页新增两行**：`SwitchPreference` 双向同步（用 `setOnPreferenceChangeListener`：确认前**返回 `false`** 让行保持 off，确认后才写 store 并 `setChecked(true)`）+ `Preference` 立即同步（未开双向时**禁用**）；`syncNow()` 从 store **重读**开关，不信任行的显示态 | `ui/settings/BackupPreferenceFragment.kt`（`syncNow` / `setupTwoWaySync` / `showSyncOutcome` / `syncRunner` / 两参 `showNotUsable`）、`res/xml/fragment_backup_preferences.xml`、`res/values/strings.xml`（②-v2 块，含 `webdav_sync_counts` 六个占位） |
  | **新增断言 4 类** | `.../test/unit/sync/SyncNoticeTest.kt`（13 例，纯 JVM：8 态映射 + 严重度排序 + `deservesCounts` + 派生属性）、`.../test/unit/sync/SyncRunnerTest.kt`（11 例：真 `SyncRound` + 计数假 transport + 可抛假 store；不可用零请求 / 首轮上传并写 base / 空 peer 与 `WRONG_FOLDER` / store 抛异常 ⇒ `Failed` 且零请求）、`.../ui/settings/SyncNoticeTextTest.kt`（10 例）、`WebDavPreferencesTest`（补 `twoWaySync` 默认 false / 可写 / 经默认 store 持久化）、`BackupPreferenceScreenTest`（补两行在场 + `two way sync starts off`） |

  **三条设计决定（本轮定死）**：
  1. **界面只负责"把这一轮如实说一遍"**：所有"该说哪句话"的判断都在 `SyncNotices.of` 里、按**严重度**排一次序（放弃 ＞ 需重启 ＞ 有失败 ＞ 有冲突 ＞ 有延后 ＞「一条都没动」＞ 同步完成）。散在界面里的 `if` 迟早分叉；排成一条序列后，"这一轮该怎么描述"变成**一条规则、一处实现**，可逐条断言。
  2. **冲突不再蒸发**：`SyncRound` 原先把 `plan.reviews` 只数成 `conflicts: Int` 就丢掉 —— 合并算对了、界面却无从展示。本轮改成 `reviews` 随结果回传（`conflicts` 降为派生 `reviews.size`）。
  3. **"什么都没动" ≠ "同步成功"，且只有前者不显示计数**：两台设备本来就一致时弹「上传 0 · 下载 0 · …」既不诚实也没信息量，由 `deservesCounts` 单点决定。

  **咬合（10 处变异 → 逐处红）**：A1 优先级（放弃不再最优先）⇒ **6 红** · A2 `EMPTY_REMOTE` 误说成"列举不完整"⇒ 4 · A3 `movedAnything` 恒真⇒ 6 · A4 `reviews` 不回传⇒ 2 · A5 `NotUsable` 误说成"同步坏了"⇒ 2 · A6 计数占位前两位互换⇒ 2 · A7 `abandonReason` 归因写错⇒ 2 · A8 异常不再兜成 `Failed`⇒ 2 · A9 `twoWaySync` 默认打开⇒ 2 · A10 开关 XML `defaultValue` 改 true⇒ 2（`two way sync starts off …` ×2 SDK）；逐处自备份**逐字节还原**，收尾 `diff` 7 文件全 OK + `grep BITE` = 0。

  | 项 | 值 |
  |---|---|
  | 单测 | 见 E13（全量） |
  | **本轮不含 BY** | APK 沿用第二十轮 `debae437b98e3ffa9745a937747e922ac2fd9e123413658bce788a79c3bbf6e0` |

  **仍未做（明确的下一步）**：冲突的**解决动作**（保留本地 / 采用远端，需要一条持久化覆盖，见 §7.10 已知限制 ①）；`device_id` / `modified_at` 的**跨设备传播**；自动 / 定时同步；§8.2 的 OwnCloud 路径迁移。

- **✅ 实现进度（2026-10-14，第二十三轮 KFCSTX）：②-v2 接线第二半 —— 冲突中心（只读）**

  承接 §7.10：上一轮把 `ConflictReview` 随一轮结果带了出来，但**界面没有消费它的地方**（字符串 `webdav_sync_conflicts` 已经写好却没人用 —— 典型的"配了但不读"）。本轮把它消费掉：一轮结束后只要有记录被扣下，就弹出冲突中心，逐条列出「哪一条、什么情况、几处字段不一样」，点开看三方逐字段对照。**不含解决动作、不含自动同步、不含出包。**

  | 已交付 | 落点 |
  |---|---|
  | **冲突分类收成纯函数**：`ConflictKind` 四态（`BOTH_EDITED` / `DELETED_THERE_EDITED_HERE` / `DELETED_HERE_EDITED_THERE` / `UNREADABLE`），由 `decision` 的**结构**决定（**不读 `note`**）；`MergeDecision.fieldDiffs` 扩展取出两类可评审 decision 的差异 | `sync/ConflictDigest.kt`（新，零 Android 依赖） |
  | **字段差异分类收成纯函数**：`FieldDeltaKind` 八态，从 `(base, local, remote)` 三值判定，含删改冲突特有的「**两端都没了这个字段**」 | 同上 |
  | **载荷编解码**：两层的 `RecordCodec`（外层 `r0 = <内层>`；内层 `id` / `note` / `decision` / `d.<i>.<side>`）；⚠️ **某一侧没有值 ⇒ 省略键**（`null` 绝不写成 `""`）；上限 `MAX_REVIEWS = 100` / `MAX_VALUE_CHARS = 512`，**截断位在截断那一刻记录**（不事后按 `…` 推断），`dropped` / `capped` / `truncated` 随载荷回传 | `ui/settings/ConflictCenterCodec.kt`（新，纯 JVM 可测） |
  | **值 → 文案单点**：`of(ConflictKind)` / `of(FieldDeltaKind)` / `of(SyncRecordType)`，`when` **穷举**（新增枚举值会**编译失败**，而不是渲染成空行） | `ui/settings/ConflictCenterText.kt`（新） |
  | **对话框**：标题 + 提示行（丢了几条 / 列表被截断 / 值被截断）+ 逐行摘要；点一条 ⇒ 二级对话框显示「记录：类型 / key 前 8 位 + 情况 + 每字段三列对照」；空态与列表态**走同一个渲染函数** | `ui/settings/ConflictCenterDialogFragment.kt`（新）：`build(context, payload)` / `contentView(context, payload)` **不要求 attach**（可单测构建），`newInstance(reviews)` 把冲突编进 dialog arguments |
  | **入口**：一轮结束后 `reviews` 非空即弹冲突中心，且**排在 `deservesCounts` 提前返回之前** | `ui/settings/BackupPreferenceFragment.kt`（改：`showSyncOutcome` + 新 `showConflicts`） |
  | **新增断言 5 类** | `.../test/unit/sync/ConflictDigestTest.kt`（15 例，纯 JVM）、`.../ui/settings/ConflictCenterCodecTest.kt`（19 例，纯 JVM：往返 / `null` vs `""` / 上限 / 截断边界 / 坏载荷不炸 / 未知 decision）、`.../ui/settings/ConflictCenterTextTest.kt`（7 例）、`.../ui/settings/ConflictCenterDialogTest.kt`（15 例）、`.../ui/settings/ConflictCenterWiringTest.kt`（3 例，纯 JVM：**源码守卫**「入口在场 + 早于计数闸」） |

  **三条设计决定（本轮定死）**：
  1. **冲突中心是一面镜子，不是一只手**：显示「哪边改了什么、哪边删了什么」，但不提供「保留哪边」的动作 —— 那需要一条能被**下一轮规划器读到**的持久化覆盖，有自己的失败面。本轮只把"看得见、看得懂"做扎实，把"改得对"留给下一轮明确交付。
  2. **`note` 只给日志**：分类只看 decision 的结构。`note` 是自由英文，靠它显示等于让界面跟着一句日志的措辞走 —— 改一次措辞就把每一条悄悄重新归类。
  3. **"读不出来"不许被说成"没差异"或"都改了"**：`UNREADABLE` 独立成一种情况，且**不显示差异条数**（"0 处差异"读起来就是"两边一致"，这是唯一绝不能从读不出的载荷里得出的结论）。

  **咬合（12 处变异 → 逐处红）**：A1 `null` 决策误报「两端都改了」⇒ 2 · A2 不可评审决策误报 ⇒ 1 · A3 删改冲突的赢家说反 ⇒ 1 · A4「两端都没了」误报「本机删了」⇒ 1 · A5「云端改过」说成「本机改过」⇒ 1 · A6 行序不再按 token 排 ⇒ 1 · A7 `null` 编成空串 ⇒ 2 · A8 截断了却不记 ⇒ 1 · A9 上限放宽一条 ⇒ 1 · A10 capped 提示不再出现 ⇒ 2 · A11 缺值画成空白 ⇒ 2 · A12 **接线被摘掉**（同步结果不再打开冲突中心）⇒ 2（`ConflictCenterWiringTest`）—— 共 **17 条红**（纯 JVM 类 ×1、继承 `GnuCashTest` 的类 ×2 SDK）；逐处自备份**逐字节还原**，收尾 `diff` 4 文件全 OK + `grep BITE` = 0。

  | 项 | 值 |
  |---|---|
  | 单测 | 见 E13（全量） |
  | **本轮不含 BY** | APK 沿用第二十轮 `debae437b98e3ffa9745a937747e922ac2fd9e123413658bce788a79c3bbf6e0` |

  **仍未做（明确的下一步）**：冲突的**解决动作**（保留本地 / 采用远端）；`device_id` / `modified_at` 的**跨设备传播**（`device_id` 已写进墓碑，但尚未上线）；自动 / 定时同步；§8.2 的 OwnCloud 路径迁移。

### ③ 导入支付宝 / 微信 / 云闪付账单

- **现状**：`importer/` 只有 `sql/` 与 `xml/`；**没有 CSV 导入**（导出侧有 CSV）。依赖里已有 `opencsv 5.12.0`。
- **方案**：新增 CSV 导入器族（走 L0 `Importer` SPI），每家一个解析器 + 共用管线：
  1. **编码探测**：GBK / GB18030 / UTF-8（带/不带 BOM）；
  2. **跳过文件头元信息行**（三家都有若干行"导出说明"才到表头）；
  3. **列名归一**（交易时间/交易类型/交易对方/商品/收/支/金额/支付方式/交易状态/交易号/商户单号）；
  4. **去重**：以**交易号/商户单号**为幂等键，重复导入不产生重复交易；
  5. **剔除**：退款、交易关闭、不计收支；
  6. **自动分类映射**：交易对方/商品 → 账户/分类（可脚本化，见第 10 章）；
  7. ⚠️ **微信账单是加密 zip**（密码在微信服务通知里取），需 `zip4j`/`commons-compress` 或**引导用户先解压**。
- **数据/接口改动**：无 schema 变更（新增导入器 + 一个"导入映射规则"的本地偏好）。
- **风险**：中——**格式漂移**（三家随时改列名/加列）。对策：解析器**声明其期望列**并在缺失时给出可读错误；夹具用**真实导出文件**做 golden（含脱敏）。
- **量级**：**M**

### ④ 悬浮球点击识别当前页面 / 图片

- **现状**：Manifest **无**任何悬浮窗/截屏/前台服务权限或代码；**全项目没有任何 `<service>`**。
- **方案**：
  1. `SYSTEM_ALERT_WINDOW` 悬浮球 + **前台 Service**（Android 14+ 需 `foregroundServiceType="mediaProjection"` 等）；
  2. `MediaProjection` 截屏（用户首次授权）+ 通知常驻；
  3. 走离线 OCR（第 9 章）→ 结构化 → 预填记账表单；
  4. 备选一路：`AccessibilityService` 读屏文字（**不用截屏但隐私更敏感**，默认不启用）。
- **数据/接口改动**：新增 Service + 权限 + 通知渠道；无 schema 变更。
- **风险**：**高**——权限合规（上架需隐私说明）、机型碎片、截屏权限被回收、后台被杀。
- **量级**：**L**

### ⑤ 分享图片后记账

- **现状**：`receivers/TransactionRecorder` 已有 `INSERT` intent 收单范式；无 `ACTION_SEND` 图片接收。
- **方案**：新增/扩展 Activity 接管 `ACTION_SEND` / `ACTION_SEND_MULTIPLE` + `image/*`，收到即走 ④ 的 OCR 管线 → 预填表单。
- **风险**：低。**这是"复用 ④ 管线"的性价比之王，建议在 ④ 之前或同时做**（先做 ⑤ 可以先打磨 OCR 管线，再上悬浮球）。
- **量级**：**S–M**

### ⑥ 记账模板

- **现状**：**底层已具备** —— `transactions.is_template`、`books.root_template_guid`、`scheduled_actions` 亦有模板引用；缺的是"模板 CRUD UI + 一键套用"。
- **方案**：模板列表页（新建/编辑/复制/删除）+ 记一笔时"从模板填充"；可选**动态快捷方式直达某模板**（与 ⑨ 联动）。
- **数据/接口改动**：优先复用既有列；若需"模板分类/排序"，再加本地偏好而非新表。
- **风险**：低。注意与 ⑧ 标签的双写/剥离规则共用同一套文本处理。
- **量级**：**S–M**

### ⑦ 语音记账

- **现状**：无 `RECORD_AUDIO`、无 ASR、无 NLU。
- **方案**：`RECORD_AUDIO` + 离线 ASR（第 9 章）+ **NLU 抽取**：日期（"昨天/上周五"）、金额、账户/支付方式、备注、标签。**ITN 是关键**（"三千五" → 3500）。
- **数据/接口改动**：无 schema 变更；模型分发见第 9 章 D5。
- **风险**：中——模型体积（228MB）、ABI（须含 x86_64 才可上模拟器）、首启体验。
- **量级**：**M–L**

### ⑧ 交易标签 + 按标签统计

- **现状**：**无标签表/列**。桌面 GnuCash 的"标签"实际是 description/memo 里的 `#tag` 文本，**不是一等实体**。
- **方案**（决策 5）：
  1. 新增 `tags` + `transaction_tags` 表（或 `splits` 级）；
  2. **双写**：写入时把 `#tag` 回写到 memo/description，保证**桌面 XML 往返与桌面自定义报表可用**；
  3. **展示层剥离**：UI 与**搜索**都要把 `#tag` 从正文里剥掉再显示/匹配，否则污染现有 Description/Note 搜索结果；
  4. 报表加"按标签"维度（`ui/report/`）。
- **数据/接口改动**：`DATABASE_VERSION 30 → 31` + `DatabaseHelper`/迁移步骤；`GncXmlExporter`/`GncXmlImporter` 需对 `#tag` 往返做**对拍**。
- **风险**：中——迁移 + 往返兼容。**最大的坑是"剥离不彻底"**（搜索结果变脏）与"双写不一致"（App 内标签有、桌面没有）。
- **量级**：**M**

- **✅ 实现进度（2026-10-11，第三轮 KFCSBYTX）：标签数据层 + 双写 + ①b 真实数据**

  | 已交付 | 落点 |
  |---|---|
  | `tags` + `transaction_tags` 表（**schema 31 → 32，纯加法**；`IF NOT EXISTS` 可重复执行） | `DatabaseSchema.kt`（`TagEntry`/`TransactionTagEntry`）、`DatabaseHelper.kt`、`MigrationHelper.migrateTo32` |
  | `#tag` 编解码**单点收口**（`extract`/`stripTags`/`appendTags`/`normalizeName`） | `.../util/TagCodec.kt`（**新**） |
  | `Tag` 模型 + `TagsDbAdapter`（一适配器管两表：查/建/改名/删除/`deleteUnused`/按事务设标签/用量统计） | `.../model/Tag.kt`、`.../db/adapter/TagsDbAdapter.kt`（**新**） |
  | **双写钩子**：事务保存（含导入 `bulkAddRecords`）时自动把description/notes/split memo 里的 `#tag` 镜像进表 | `TransactionsDbAdapter.addRecord` / `bulkAddRecords` |
  | **①b 落地**：`Tag` 条件的 ALL/ANY/NONE 现在**有真实数据可查** | `SearchQueryBuilder.appendTag`（上轮已写，本轮接真数据） |

  **本轮做出的三个设计决定（与初稿不同，已生效）**：
  1. **标签写进 transaction 的 `notes`**（事务级、单一位置），不是 split memo —— 与本项目"标签是事务级"的模型一致；列表/详情不显示 notes，因此**列表文本天然不被污染**。
  2. **`transaction_tags.tag` 存标签名并声明 `COLLATE NOCASE`**，`tags.name` 同样是 `NOCASE` + 唯一索引 ⇒ "Food"/"food" 是同一个标签，搜索也大小写无关。
  3. **注册表不会被静默修剪**：从文本里删掉某个 `#tag` 只删关联行，`tags` 里的孤儿行要显式 `deleteUnused()`（避免"用户只是暂时删掉一次、标签颜色/统计就被吃掉"）。
  4. `#tag` 的**起始边界**：`#` 前是空白/标点/开头 **或 CJK 汉字** 才算标签 —— `午饭#餐饮` 是标签，而 `issue#5` / `C#` 不是（面向中文用户的关键取舍，已有断言）。

  **上一轮明确未做（不是遗漏，是刻意的）**：
  - **展示层剥离**（把 notes 里的 `#tag` 从文本中剥掉、只留 chip）：现在**没有标签编辑器**，用户自己敲进正文的 `#tag` 就是标签本身，剥掉反而把信息藏起来 —— 留到"标签 chip UI / 标签编辑器"那一轮一起做（届时写回用 `TagCodec.appendTags`）。
  - 按标签**统计报表界面**（`getUsageCounts()` 已就绪，未接 `ui/report/`）。

- **✅ 实现进度（2026-10-11，第四轮 KFCSBYTX）：V7 往返闭环 + 标签 chip 展示层**

  | 已交付 | 落点 |
  |---|---|
  | **V7 闭环**：`#tag` 经 XML 导出 → 导入后**文本不丢**（notes slot + `<split:memo>`），且**标签镜像被重建** | 新测试 `TagXmlRoundTripTest`（4 例 × SDK 26/36，全绿） |
  | 导入路径**确实经过双写钩子**（`GncXmlHandler` → `TransactionsDbAdapter.insert` → `addRecord` → `syncFromTransaction`）—— 已用**咬合验证**钉死：临时注释掉钩子 → 该 4 例立刻变红，恢复后转绿 | 同上 |
  | **标签 chip UI**（决策 D2）：搜索结果行下方一条可横滑的标签条，**只在搜索/筛选结果页填充**（共享布局默认 `gone`，`TransactionsListFragment` 不受影响） | `cardview_transaction.xml`（新增 `tag_chips_scroll`/`tag_chips`）、`item_tag_chip.xml`、`bg_tag_chip.xml`（**新**）、`SearchResultViewHolder.renderTags` |
  | **点击 chip = 叠加标签筛选**：谓词由 `SearchQueryBuilder.tagWhere()` 生成、用 `andWhere()` **AND 到原 WHERE 上**（两侧都加括号，防止原查询里的 `OR` 被重绑定） | `SearchQueryBuilder`（新增 `tagWhere`/`andWhere`）、`SearchResultsFragment.filterByTag` |
  | 新增断言 | `TagChipFilterTest`（10 例 × SDK 26/36）：SQL 生成、空标签不当作"全匹配"、AND 组合的优先级、真数据窄化、chip 渲染/隐藏/点击回调 |

  **决策 D2 的落地取舍**：**只做 chip 展示 + 点击筛选，不做正文剥离**。因为 chip 是 `TextView` 而非 Material `Chip` —— 本应用主题父级是 `Theme.AppCompat.DayNight`（**不是** `Theme.MaterialComponents`），直接用 Material `Chip` 会在运行期抛"requires your app theme to be Theme.MaterialComponents"。

  **仍未做（明确的下一步）**：正文剥离（等标签编辑器）；标签统计报表界面（`getUsageCounts()` 已备）；标签颜色编辑（`color_code` 列已就绪）；模板拖拽排序。

### ⑨ 桌面快捷菜单（可自定义）

- **现状**：`res/xml/` **无 `shortcuts.xml`**；`minSdk 26` 已支持 Dynamic Shortcuts。
- **方案**：`ShortcutManagerCompat` 动态注册；设置页让用户勾选展示项（记一笔 / 扫一扫 / 语音 / 指定模板 / 某账户）。
- **风险**：低（注意动态 vs 静态 shortcut 的**数量上限**与"被系统降级"的行为）。
- **量级**：**S**

### ⑩ 是否可用插件形式实现（结论）

| 层 | 做什么 | 本次 |
|---|---|---|
| **L0 内部 SPI** | `Importer` / `SyncBackend` / `OcrEngine` / `AsrEngine` / `SearchField` / `ReportView` / `TxnTemplate` 接口 + Hilt 注册表 | ✅ **必做**（十项功能的地基） |
| L1 Dynamic Feature | 按需下发（如"语音包"） | ❌ 不做（中国区无 GMS 需自建下发，收益低） |
| L2 运行时 dex | `DexClassLoader` + 稳定 API + 签名校验 | ❌ 不做（Android 9+ 隐藏 API 限制 + 安全面） |
| **L3 用户脚本** | 导入解析、标签规则、模板、报表 | ✅ **做**（决策 7；引擎选型见第 10 章） |

**一句话**：**不能一刀切**——能力型必须宿主内实现，只有数据/规则型才适合脚本化。

---

## 6. 数据与迁移方案

### 6.1 schema 变更清单

| 版本 | 变更 | 说明 |
|---|---|---|
| 30 → 31 | 新增 `search_templates`（①a 搜索模板） | **✅ 已实现**：纯加法；条件形态全存 `form_json`，表结构稳定；建表 `IF NOT EXISTS` 所以迁移可重复执行 |
| 31 → 32 | 新增 `tags`、`transaction_tags`（⑧；①b 依赖它） | **✅ 已实现**（第二轮）：`tags.name` 与 `transaction_tags.tag` 均 `COLLATE NOCASE`；两外键 `ON DELETE CASCADE`；建表/索引 `IF NOT EXISTS` + 触发器先 `DROP IF EXISTS` ⇒ 迁移可重复执行 |
| 32 → 33 | 新增 `sync_state`、`tombstones`（②） | **✅ 已实现**（第六轮）：纯加法；`sync_state` 存 base 快照 + `base_modified_at` 水位，`tombstones` 存删除标记；建表 `IF NOT EXISTS` + 触发器先 `DROP IF EXISTS` ⇒ 迁移可重复执行。**决策内核（`ThreeWayMerge`）不再有网络依赖**，见 §5② 进度块 |
| 33 → 34 | `sync_state` 补 `base_etag`、`pending_since`（②-v2 编排层） | **✅ 已实现**：纯加法；`base_etag` 存对端 etag（未变则不下载、省一次 GET）、`pending_since` 存「首见缺席」水位（二轮才晋升墓碑）；建表/索引 `IF NOT EXISTS` ⇒ 迁移可重复执行 |
| 34 → 35 | 新增 `transaction_templates`（①d 记账模板） | **✅ 已实现**：纯加法；`payload_json` 存模板载荷（**空值 = 省略 key**）；建表/索引 `IF NOT EXISTS` ⇒ 迁移可重复执行 |
| 35 → 36 | 新增 `sync_resolutions`（②-v3 冲突解决覆盖） | **✅ 已实现**（第二十七轮）：纯加法；唯一索引 `(record_type, record_key)` 保证「同一记录只有一条答案」（`REPLACE` 语义，后答覆盖先答）；`side` 存 `local`/`remote` 字面量；**读不出的 type/side 一律跳过而非猜默认**；建表 `IF NOT EXISTS` ⇒ 迁移可重复执行 |
| 附 | 检索索引：`transactions(description/memo/note/number)`、`splits(memo)` | ① 的性能保障（⚠️ 对 `LIKE '%x%'` **无加速**，见 §5① 第 6 点；真加速需 FTS） |

> ⚠️ 上表版本号是**加法式**、与实际落地顺序绑定（每加一张表 +1）。**不要为"凑整数"把两张表塞进同一次迁移** —— 迁移步数越多，"新装正常、升级炸"的漂移越难定位。

### 6.2 迁移原则

1. **只做加法**（新增表/列/索引），**不改名、不删列**（改名会让老版本 App 读到新库时行为不可预期）。
2. 每步迁移必须**可重入**（重复执行不产生副作用）。
3. `DATABASE_VERSION` 单调递增；`DatabaseHelper.onCreate/onUpgrade` 两路都要能建出等价结构（**这两路漂移是最常见的"新装正常、升级炸"来源**）。
4. 迁移完成后要有**自检**（表存在 + 列存在 + 关键索引存在 + 行数与迁移前一致）。

### 6.3 标签 ↔ `#tag` 双写契约

| 方向 | 规则 |
|---|---|
| App → 库 | 用户打标签 ⇒ 写 `tags`/`transaction_tags` **且**把 `#tag` 追加进 memo（去重、稳定排序） |
| 库 → App 展示 | 展示前**剥离** memo 中的 `#tag` 片段（含前导空格/标点），**只显示标签 chip** |
| 检索 | Description/Note/Memo 的匹配必须对**剥离后文本**做（否则搜"打车"会把标签名也命中） |
| 导出（XML/CSV/QIF） | memo 里保留 `#tag`（桌面依赖它）；CSV 导出可另加一列标签 |
| 导入 | 从 memo 解析 `#tag` → 建标签；**幂等**（已有则不重复） |

> ⚠️ 这四条里任意一条漏做，都会出现"App 看着对、桌面看着不对"或"搜索结果变脏"。**每条都要有断言。**

---

## 7. 同步与冲突合并协议（② 的核心）

### 7.1 角色与数据

| 概念 | 含义 |
|---|---|
| **记录键** | `books/accounts/transactions/splits` 的 GUID（GnuCash 原生稳定 ID） |
| **基线（base）** | 上次成功同步时的记录快照，存 `sync_state` |
| **墓碑（tombstone）** | "这条被删了"的显式标记，随同步传播 |
| **水位** | 本次快照对应的"已同步到哪"的标记 |

### 7.2 三方合并判据

对每条记录，比较 `本地 / 远端 / 基线`：

| 情形 | 处置 |
|---|---|
| 本地 == 基线 | 取远端 |
| 远端 == 基线 | 取本地 |
| 本地 == 远端 | 无冲突 |
| 三方互不相同 | **冲突**：进冲突中心，按字段列出 A/B/基线差异，用户批量采纳 |
| 一方删除、另一方修改 | 采用修改的那方（但**记入冲突列表**让用户可见） |
| 两方都删除 | 都删 |

### 7.3 必须遵守的两条硬约束（血泪）

1. **快照与水位必须同一只读事务读齐** —— 分两次读会拼出"N 行 / 水位 M>N"的自相矛盾快照，下游拿它覆盖已有状态后**永久丢数据**，且两侧日志全绿。
2. **删除判据 = "已确认的缺席"**，不是"这一轮没列到" —— 网络截断/分页只回了一部分时，把"没列到"当删除会**双端丢数据**。做法：列举必须**完整成功**才允许生成墓碑；失败则本轮**不判删除**。

### 7.4 幂等与回放

- 同一快照重放 ⇒ 不产生新变更（用 base 判据保证）。
- 冲突采纳后要**重算 base**，否则下一轮会重复报同一个冲突。

### 7.5 远端列举协议（②-v2 网络层，第七轮已落地）

列举是**删除判据的唯一证据来源**，所以协议本身必须保证"列举不完整时**给不出证据**"，而不是"给出一份看起来完整的证据"。

| 约定 | 原因 |
|---|---|
| `PROPFIND` + **`Depth: 1`**，由客户端**自递归**；**不用 `Depth: infinity`** | `infinity` 的截断（服务器列到一半就停）返回的仍是格式完好的 `207`，客户端无从检测；自递归把"哪一层失败"变成可见信息 |
| 请求固定 4 个属性：`resourcetype` / `getetag` / `getcontentlength` / `getlastmodified` | 比 `allprop` 体量小且形状可预测（解析可防御） |
| **只有 `207`** 能承载列举结果 | 其它任何状态（含 `200`）一律 `Incomplete`，绝不退化成"空目录" |
| `207` 内任一 `<response>` 非 2xx ⇒ 整轮 `Incomplete` | 忽略它就会静默丢该资源，下一轮把"丢掉"读成"删除" |
| `<propstat>` 必须带 2xx `<status>` | 缺失时拒绝而非猜测（猜错 = 笃信一条不存在的记录） |
| `Depth: 1` 回显的 collection 自身必须丢弃，且**与请求路径比较** | 否则文件夹变成一个"文件"记录，其下一轮缺席即被读成删除（只与根比较的写法在下一层就失效） |
| href 三步归一：绝对 URL → 路径 / 去 base 前缀 → 相对路径 / **解码一次** | Nextcloud 回的是 `/remote.php/dav/files/<u>/...`，不与相对路径比较会全判缺席；解码两次会把 `%2520` 变成空格 |
| href 越出所请求集合（横向/向上）⇒ `Incomplete` | 防止行为异常的服务器把遍历引到别的子树或成环 |
| 客户端根 = `baseUrl` 自身，`listRecursive()` 不带参数 | 消除"你指的是哪个路径"这一整类歧义 |
| 拒绝 `DOCTYPE` 的响应体 | DAV 响应没有合法 DOCTYPE 用途，接受它等于给不可信服务器一个 XXE 原语 |

- 传输层用**已依赖的 OkHttp 5.5.0** 自实现，与 ownCloud SDK 解耦（便于替换坚果云 / Nextcloud / 自建）；`trustAllCertificates` 复用 `net/TrustExt.kt`（§8）。

### 7.6 写远端协议（②-v2 动作层，第八轮已落地）

列举解决"远端有什么"，动作层解决"怎么改远端、怎么取回来"。与 §7.5 同一条原则：**每个状态码只允许有一种对同步轮次的含义**，而"错的那一种"必须是"这一轮不动数据"，不是"悄悄动错"。

| 约定 | 原因 |
|---|---|
| 结果分五态：`Done` / `AlreadyDone` / `Stale` / `Missing` / `Failed` | 四类非成功各有**完全不同的后续动作**（记基线 / 继续 / 重新列举重合并 / 建目录 / 记日志不动），压成"成功/失败"就会"永远重试"或"静默丢变更" |
| **幂等映射**：`DELETE` 的 `404`/`410` ⇒ `AlreadyDone`；`MKCOL` 的 `405` ⇒ `AlreadyDone` | 一轮可能被重跑（崩溃 / `412` / 用户连点）。"目标态已成立"若报失败，已应用的删除会永远重试；`405` 正是两设备并发建同一目录 |
| **条件写**：新建 `If-None-Match: *`，更新 `If-Match: <基线 etag>` | 无条件的 `PUT` 在"列举之后、上传之前对端写了同一条"时**静默覆盖**（丢更新），两侧日志全绿。`412` ⇒ `Stale` ⇒ 重新列举 + 重合并，**不重试同一个写** |
| `202 Accepted` **不算** `Done` | 服务器收下了但还没落地；当作成功就会为不存在的内容记基线，下一轮把它读成删除 |
| `206 Partial Content` 的 `GET` 拒绝 | 部分内容会被当成整本书存下来 ⇒ "记录损坏"比"记录缺失"难发现得多 |
| `207` 的 `DELETE` 拒绝 | 只对集合定义的部分删除；用于记录意味着"删了一半"，不能报"已删除" |
| **建目录与上传分离**，目录 root-first 逐层建 | `PUT` 进不存在的集合必得 `409`；读成"不可写"就**永远不上传**。`MKCOL /a/b` 先于 `/a` 也必 `409`，所以顺序不能改 |
| **绝不对客户端根做 MKCOL** | 根是用户配置的目录（Nextcloud 账号根等），在别处创建；动它是同步子树之外的写入 |
| 每层建目录**遇第一个真失败即停** | 继续走会把"哪一层坏了"埋掉，且后续 `409` 不带来新信息 |
| 写动词的 `body == null` 原样透传（`MKCOL`/`DELETE`/`MOVE`） | `MKCOL` 带 body 是协议违规，某些服务器答 `415` |
| 传输异常 ⇒ `Failed` 且**保留异常原文**，不抛出 | 收敛成哨兵状态会让日志只剩"HTTP 0"，正是最难查的那种条目 |
| `MOVE` 的 `Destination` 用**绝对 URL**、`Overwrite` 显式给出 | 相对 destination 是服务器分歧点，落错地方 = 丢一条 + 多一条幽灵墓碑；"静默替换"与"已存在就拒绝"是两种策略，必须由调用方选 |

- 已知限制（诚实记录）：`If-Match` 用**强比较**，而 `WebDavXml` 解析时会剥掉 `W/` 前缀 ⇒ 若某服务器只发弱 etag，条件写会一直 `412`、同步不收敛。目标服务器（Nextcloud / 坚果云 / mod_dav / SabreDAV）均发强 etag；方向的代价是"不同步"而非"丢数据"，但确实是限制。

### 7.7 一轮同步的编排（②-v2 编排层，第九轮已落地）

前两节解决了"远端有什么"（列举）与"怎么改远端"（动作）；本节把它们与 §7.2 的合并判据、§7.3 的两条硬约束接成**一轮**。原则不变：**每个"不确定"都只允许收敛成"这一轮不动数据"，不允许收敛成"悄悄动错"。**

| 约定 | 原因 |
|---|---|
| **记录身份 = `(recordType, GUID)`**，远端路径 `/<type>/<key>.gncs`，扁平一层 | 身份是"本地/列举/基线/墓碑"四处视图的 join key；路径解析**严格**（非本布局一律拒绝，绝不"近似匹配"），否则半解析出来的会是"一条幽灵记录 + 一次删除" |
| 记录内容 = `RecordCodec` 的规范文本（按 key 排序、`%`/换行/`=` 转义），**无损且确定** | 基线快照要与"刚解码的记录"做**相等比较**；有损编码会让未变的记录每轮报冲突甚至删除。解码遇无分隔符的行**抛错**（`decodeOrNull` 供不可信输入），绝不"少读几个字段"当作新内容采纳 |
| **闸 1：列举不完整 ⇒ 整轮放弃**（不只跳过删除，**上传也不做**） | 没有完整远端视图就无法区分"本地改动"与"与一个没看到的对端改动相撞"。只挡删除不够 |
| **闸 2：列举回来全空而本地仍有已知记录 ⇒ 整轮放弃** | 与 §7.3 同源：这是"指错目录/视图被截断"的形态；继续会把每条记录读成"远端已删"。代价是"删掉最后一条不同步"，远小于"随机删光" |
| **删除候选复用 `decide(local, remote=null)`**，只在"已确认"后才动作 | 让"远端删了、本地改了"自动落到 §7.2 的"采用修改方 + 记冲突"，不必手写这些分支；确认由 §7.3 的 `planDeletions` 提供 |
| **首见缺席记 `pending`（存 `sync_state.pending_since`），第二次连续缺席才晋升墓碑**；重现即清除 | `pending` 挂在基线行上，"可能被删了"不会与记录自身同步状态脱节。本机自己删的（`locallyDeleted`）一轮即墓碑——那是唯一**自明**的删除 |
| **远端不可读（解码失败/取回被拒）⇒ 当作"在场但不动"** | 它是**在场**的（列举里有），只是读不出；当作缺席就会触发删除路径。动作 = `NONE` + 记冲突中心 |
| **基线存 `base_etag`**：`If-Match` 的条件值 + "未变则不下载"的依据 | 列举只给 etag。etag 等于基线 ⇒ 内容等于基线 ⇒ 无需 `GET`（否则每轮把整本书拉下来）；条件写也需要它。etag 缺失（服务器不发）⇒ 一律走保守路径（下载 + 无条件/`None`） |
| **条件写的前置条件由规划器给定**：新建 `MustNotExist`、更新 `MustMatch(远端 etag)` | 轮次里没有一处 `PUT` 能不带条件落到线上。`412` ⇒ `Stale` |
| **`Stale`（`412`）⇒ 停轮，下轮重列举**，**不重试同一个写** | 条件被拒意味着"列举之后对端又改了"，本轮据此做出的后续决策全部建立在过期快照上。已提交的写**保留**（它们是服务器接受过的条件写，仍然成立） |
| 写回顺序：先执行动作、再提交 base/墓碑/pending | 崩溃时最坏是"这一轮少提交了几个基线"（下轮重做，幂等），而不是"基线说改了、其实没改" |

- 已知限制（诚实记录）：`SyncRound` 目前只对**内存 store + 假 transport** 验证接线；把 `transactions`/`accounts` 的字段映射成 `RecordState` 的**真实 `LocalRecordStore` 实现是下一步**。`device_id` 已写入墓碑，但尚未上传（跨设备删除传播是 v3）。

### 7.8 本地存储接缝（②-v2 落盘层，第十 / 十一 / 十二 / 十三轮已落地）

§7.7 的 `SyncRound` 决定"要改什么"，`LocalRecordStore` 是它落到本机那一半的接缝。第十轮把 `accounts` 接进来（纯映射 `AccountFields`）；第十一轮把 `transactions` 与其 `splits` 作为**一条整体记录**接进来（纯映射 `TransactionFields`）；第十二轮把"父引用不可解"一族（ROOT 归一化 + 删账号修悬空子引用）收口；第十三轮把**删账号 × 交易的交叉**收口。总原则：**接缝的两端都不允许把"我做不到"表达成"没有"，也不允许一次删除顺带静默改写另一种记录。**

| 约定 | 原因 |
|---|---|
| **记录形状唯一**：`AccountFields`（纯 Kotlin，**键 = `accounts` 的列名**） | 合并、基线、冲突 diff、传输全用同一张字段表 ⇒ "什么算一条账号改了"只有一处定义。`DbLocalRecordStoreTest` 把键与 `AccountEntry` 的列名逐一钉住，防漂移 |
| **刻意不同步**：`full_name`、`color`、`created_at`/`modified_at`、余额 | `full_name` 由 `bind` 每次从父链重算（传它 ⇒ 一端改名就和自己对冲）；`color` 的"未设置"哨兵与显式浅灰不可分辨；`bindBaseModel` **只绑 uid** ⇒ `REPLACE` 一律取 `DEFAULT CURRENT_TIMESTAMP`，承诺不了保真；余额由 splits 派生。诚实记录好过"看起来同步了" |
| **布尔量一律写出**（`"true"`/`"false"`），**可空量省略** | 布尔若靠"键在不在"表达，`true → false` 的编辑就没有痕迹可合并；可空量省略则天然表达 `null` |
| **`list()` 用 `getAllRecords(null, null)`**，**不用** `AccountsDbAdapter.fetchAllRecords()` | 后者是**界面**查询，**排除隐藏账号与 ROOT**。拿它当同步源 ⇒ 用户隐藏的账号在合并眼里 = 已删除 ⇒ 下一轮删掉对端副本（R-1 同族） |
| 只排除 **ROOT**（`AccountFields.isSyncable`）；**隐藏 / 收藏 / 模板账号照常同步** | ROOT 是每本书自己的锚点（uid 由建书设备生成），两台设备永远对不上；隐藏等是用户数据，静默丢弃才是事故 |
| **ROOT 既不上线，也不作为"父"上线**：父引用等于**本书 ROOT** ⇒ 读作「无父」 | ROOT 的 uid 由建书设备铸成，对端**永远解不出**；照发 ⇒ 对端**每一个顶级账号**都永久延后（`parentIsPresent` 永久 false，`bind` 也拒）。而"无父"正是"顶级账号"的线上表达，接收端落到**它自己的** ROOT（第十二轮） |
| **`list()` 遇到不支持的类型 ⇒ 抛 `UnsupportedRecordTypeException`**，**绝不返回空表** | "本地没有这类记录"与"这个 store 看不见这类记录"形状相同、含义相反；合并把前者读成"本地全删了"。`supportedTypes` 由 store 声明，调用方用 `types = store.supportedTypes` 取，不重复写一份 |
| **`write()` 返回 `Boolean`**："这条记录能不能落地"。返回 `false`（依赖未就绪）时**绝不回写基线** | 记了基线的"未写入记录"下一轮会被读成"用户在这里删了它"⇒ 删掉对端副本。**延后一轮只是慢，回写基线会丢数据** |
| `write()` 拒绝的四种情形：① 载荷 uid 与 record key 不一致；② ROOT；③ 无法解析的商品；④ 父账号尚未在本机 | ① 落库后 uid 与基线键错位 ⇒ 两侧都看到幽灵删除；② 对端的根不是我们的书；③ `accounts.commodity_uid` 有**真外键**，且 `buildModelInstance` 会把它反查回商品行 —— `Commodity.USD` 这类**静态常量不一定有行**，写进去要么被外键拒、要么让之后每次列举都炸；④ `AccountsDbAdapter.bind` 在写语句构造完之后才沿父链取 `full_name`，悬空 uid 会**写到一半抛异常** |
| **`write()` 走 `REPLACE` 语义**：同 uid 就地更新（不产生重复行） | `DatabaseAdapter.addRecord` 即 `REPLACE`，`accounts.uid` 有 `UNIQUE` |
| **`delete()` 用非级联的 `deleteRecord`**，**不用** `recursiveDeleteAccount` | 采用对端的"删账号"不等于同意删它下面的交易与子账号 —— 那是另外的 record type，有自己的墓碑与确认规则 |
| **删账号前置闸**：该账号仍被任何分录引用（`splits.account_uid = ?` 计数 > 0）⇒ **拒绝（延后）**，一个字节都不写 | 光"不用 `recursiveDeleteAccount`"不够：`splits.account_uid` 是 `ON DELETE CASCADE` + `PRAGMA foreign_keys=ON`，删行仍会带走分录 ⇒ 交易少腿/无腿、**不配平** ⇒ 被交易写入闸拒收 ⇒ **永远推不出去**。一次账号删除**间接改写另一个 record type**，而合并从未同意（第十三轮）。与 app 自身一致：界面快捷删除本就先查 `getTransactionCount == 0 && getSubAccountCount == 0`（`getTransactionCount` 按 `splits.account_uid` 统计 ⇒ "0 交易" ⟺ "0 分录"） |
| **删账号后把它的后代重挂到本机 ROOT**（`reassignDescendantAccounts`），**库里真的改**，不只改内存缓存 | `deleteRecord` 只把缓存里子账号的 `parentUID` 改到 ROOT（**库里照旧指向已删 uid**），而 `bind` 走父链算 `full_name` ⇒ 这种子账号**永久不可写** = `deferred` 唯一不收敛的情形（第十二轮修；`full_name` 一并重算，否则一行自相矛盾） |
| **编排只解析"本轮类型集合内"的远端条目**（`SyncRound.materialize` 跳过不在 `types` 里的类型） | 否则规划器每轮想 `PULL`、store 每轮拒绝 ⇒ 永久空转，且用户看不见原因 |
| **交易 = 交易行 + 它的全部分录，作为一条记录**（`TransactionFields`；分录内联为 `split.<index>.<列名>`） | 一条交易离开 splits 没有意义；索引即顺序。**独立同步 `SPLIT` 不可能**（它的写要经父交易整体替换）⇒ `list/write/delete(SPLIT)` 一律抛异常 |
| **分录索引必须连续 `0..n-1`**（缺一个 ⇒ 整条 `null`） | 缺失不是"少一条分录"，而是**双分录少了一条腿** + 后续索引会重编号 ⇒ 永远对不上基线 |
| **金额走原始分数**：`value_num/denom`（交易商品）、`quantity_num/denom`（账号商品） | 钱不能过浮点；`Money` 的分子分母由商品小数位派生，存原始分数才能逐字节往返 |
| **`NEVER` 的 `reconcile_date` 以「键缺席」表达** | `NEVER` = `Long.MIN_VALUE`，过不了列的 UTC 文本时间戳；判"有日期"用 app 自己的 `> 0L`（`CsvTransactionsExporter`） |
| **模板交易完全不进合并**：列举排除、写入拒、删除拒 | 模板是计划交易的脚手架：每个模板以**全新 uid** 创建（⇒ 不列它 ≠ 删它对端），且带 `scheduled_action_uid` 外键指向**不参与本合并**的 `scheduled_actions` |
| **`is_exported` / `scheduled_action_uid` 不上线** | 前者是设备本地状态（上线 ⇒ 一台设备的"已导出"压制另一台）；后者是真外键，指向未合并的表 |
| 交易写入**五道闸**：不可解/uid 不符/模板；商品解析不出；某分录账号不在本机；**不配平**；**"落库 ≠ 载荷"** | ③ 延后、④ 拒绝，都是为了**不在本机凭空造行**（`addRecord` 会建"不平衡"账号来"修"交易）；⑤ 是为了不进入"永久来回推"（模型规范化见 §5② 第十一轮） |
| **"载荷 == 落库结果"按 `declaredSubset` 比较**（未知键忽略） | 否则更高版本对端带来的新字段会让**每一条**交易都被拒 |
| **交易落库不做任何"提醒适配器"的额外动作**：默认 `UpdateMethod.Replace` + `splits.transaction_uid ON DELETE CASCADE` 已经把旧分录清掉 | 曾被误加一行 `transaction.id = 现存 id`（注释称"不清扫就会残留分录"）；实测**该行去掉用例仍全绿** ⇒ 死代码，已删除；机制由 `the adapter drops a transaction's old legs through REPLACE alone` 钉住（K17） |

- 已知限制（诚实记录）：① **独立 `SPLIT` 不是同步单位** —— 分录随其交易整体走（`list/write/delete(SPLIT)` 抛异常）；② **父账号晚于子账号到达**时，子账号延后一轮（收敛，不丢）；③ 父账号被删留下的**悬空 `parent_account_uid`** **已在第十二轮修掉**（`delete()` 把后代重挂到本机 ROOT，库与缓存一致）；**残留**：界面 / 导入路径的删除走的是原 `deleteRecord`，仍只修缓存 —— 但那种子账号在**对端**被修好后会经一轮推送（父引用消失）把本机一并拉正，故收敛；④ **删账号 × 交易的交叉** **已在第十三轮收口**：`delete()` 在该账号仍被分录引用时**拒绝（延后）**，账号只在无人引用时才删（交易由它自己的 record type 删、自己的墓碑确认）。**残留**：`accounts` 的**界面 / 导入路径**删除不经过本闸；但界面快捷删除本就有 `getTransactionCount == 0 && getSubAccountCount == 0` 前置守卫（等价于"无分录"），确认框路径则**显式**删交易（属**用户决定**，非合并决定）⇒ 实际不会产生级联损坏。

### 7.9 ②-v2 的接线第一半（②-v2 界面层，第二十二轮已落地）

§7.7 / §7.8 让一轮同步能在纯 Kotlin 里跑完；本轮把它接上设置页，让用户能真的点出这一轮，并把原先被丢掉的 `ConflictReview` 带出来。原则：**这一轮的结果必须如实落在界面上 —— 既不把「什么都没做」说成「同步成功」，也不把「冲突 / 失败」说成「完成」。**

| 约定 | 原因 |
|---|---|
| **一轮结果的描述收成一个纯函数** `SyncNotices.of(result)`：8 态 `SyncNotice`（`NOTHING_TO_DO`/`SYNCED`/`DEFERRED_SOMETHING`/`CONFLICTS_TO_REVIEW`/`SOMETHING_FAILED`/`RESTART_NEEDED`/`WRONG_FOLDER`/`LISTING_INCOMPLETE`），按**严重度排序**取胜出分支：放弃 ＞ 需重启 ＞ 有失败 ＞ 有冲突 ＞ 有延后 ＞「一条都没动」⇒ `NOTHING_TO_DO` ＞ 其余 ⇒ `SYNCED` | 散落在界面里的 `if` 迟早分叉（同一次运行两处给出不同的句子）。排序把「这一轮该怎么描述」变成**一条规则、一处实现**，且可被单测逐条钉住 |
| **「什么都没动」与「同步成功」是两句话**（`NOTHING_TO_DO` ≠ `SYNCED`），且**只有前者不显示计数行**（`deservesCounts` 仅对它为 false） | 两台设备本来就一致时，弹「上传 0 · 下载 0 · …」既不诚实也没有信息量 |
| **放弃必须带原因**：`SyncAbandon.LISTING_INCOMPLETE` / `EMPTY_REMOTE` 随结果回传，界面据此说「列举不完整」或「**地址指到别的目录了**」 | 两种放弃的用户下一步动作不同：前者重试，后者去改地址。只说「已放弃」等于让用户猜 |
| **冲突不再被丢弃**：`SyncRoundResult.reviews: List<ConflictReview>` 取代原来的 `conflicts: Int`（`conflicts` 降级为派生 `reviews.size`）；`SyncRound` 末尾改 `outcome.copy(reviews = plan.reviews)` | 原实现只数了个数，冲突内容当场蒸发 ⇒ 界面无从展示、用户无从处理。这是「合并算对了但看不见」的静默缺口 |
| **`movedAnything` 由四个计数派生**（`uploaded > 0 \|\| downloaded > 0 \|\| remoteDeleted > 0 \|\| localDeleted > 0`），不用独立状态位记 | 状态位会与计数脱节（说「动了」却一条没动）；派生的判据永远与计数一致 |
| **`SyncRunner` 是薄适配层**：`connection.check()` 是**唯一漏斗**（`Invalid` ⇒ `NotUsable`，**零请求**）；`Ready` ⇒ `try { SyncRound(...).run() } catch (e) { SyncOutcome.Failed(e.message) }` | 半填的地址不该产生一次 4xx / 超时（与 §8.2 一致）；未捕获的异常会让界面**停在「同步中」**并把这一轮说成成功 —— 必须兜成**显式失败** |
| **`SyncOutcome` 三态**（`NotUsable` / `Failed` / `Ran(notice, result)`） | 界面的 `when` 因此穷举：不可用走「配置问题」文案（点到具体那一行），失败走运行期文案，只有 `Ran` 才谈计数 |
| **设备 id read-or-create，永不 regenerate**（`AndroidSyncDeviceId.of(context)` 读**默认** SharedPreferences 的 `key_sync_device_id`，空则生成 UUID 并写回） | id 是墓碑的归属标记（§7.3）；每次启动重新生成 ⇒ **整个墓碑表失效** ⇒ 已删除的记录在对端**复活** |
| **双向同步是显式 opt-in**：`WebDavPreferences.twoWaySync` 独立于 `isConfigured`，默认 false | 双向同步允许**对端删本机记录**。若由「填了地址」派生，则用户只是配了个备份地址就被默许接受删除 —— 这是能删数据的功能，必须由用户**知情**开启 |
| **开关用 `setOnPreferenceChangeListener`**：`newValue != true` 直接放行；`true` 则先弹确认，**返回 `false`** 让行保持 off，确认后才写 store 并 `setChecked(true)` | `SwitchPreference` 只在监听器返回 `true` 时才落库；「先落库再问」会留下「被拒绝但已打开」的状态。`setChecked()` 不是用户动作、不会重入监听器 ⇒「确认 ⇒ 回写 ⇒ 再问」不会变成死循环 |
| **`syncNow()` 从 store 重读开关**，不信任行的显示状态 | 「行显示开、store 说关」正是「打开 → 拒绝确认 → 返回」的终态；store 是是否允许合并的唯一真源 |
| **计数行由 `getString(R.string.webdav_sync_counts, …)` 一次性格式化**（六个占位） | 六个数字分散拼接会错位；占位的**顺序**本身也要有断言 |

- 新增：`sync/SyncNotice.kt`、`sync/SyncRunner.kt`、`sync/AndroidSyncDeviceId.kt`、`ui/settings/SyncNoticeText.kt`；改动：`sync/SyncRound.kt`（`abandonReason` + `reviews` 回传 + `movedAnything`）、`ui/settings/WebDavPreferences.kt`（`twoWaySync`）、`ui/settings/BackupPreferenceFragment.kt`（`syncNow` / `setupTwoWaySync` / `showSyncOutcome` / `syncRunner`）、`res/xml/fragment_backup_preferences.xml` + `strings.xml` + `donottranslate.xml`（3 个键）。
- 已知限制（诚实记录）：① **只有手动点「立即同步」** —— 尚未接定时 / 每次改动后触发；② **冲突的可视化已由第二十三轮收口**（一轮结束后有记录被扣下即弹冲突中心，逐条看三方差异，见 §7.10）；**仍未做**的是「选择保留哪边」的**解决动作**（§7.10 已知限制 ①）；③ `SyncRound` 在 `runWebDav{}` 的后台线程里**同步阻塞**，无取消 / 无进度；⚠️ 该辅助方法在**界面已离屏时丢弃结果**（动作已经发生、结果只是没人看）—— 属既有约定，本轮沿用。

### 7.10 冲突中心的界面约定（②-v2 界面层，第二十三轮已落地）

§7.9 让「一轮同步」点得出来，并把 `ConflictReview` 随结果回传；本轮把它**消费掉** —— 一轮结束后只要有记录被扣下，就弹出冲突中心：逐条显示「哪一条、什么情况、几处字段不一样」，点开看三方逐字段对照。原则：**冲突中心是一面镜子，不是一只手** —— 只让它「看得见、看得懂」，不提供「保留本地／采用远端」的解决动作（那需要一条独立的持久化覆盖写路径，理由见下面的已知限制）。

| 约定 | 原因 |
|---|---|
| **分类从 decision 的** ***结构*** **算，绝不读 `note`**：`ConflictDigest.kindOf` 把 `null` ⇒ `UNREADABLE`、`Conflict` ⇒ `BOTH_EDITED`、`DeleteVsEdit(Local)` ⇒ `DELETED_THERE_EDITED_HERE`（反之亦然） | `ConflictReview.note` 是写给日志的自由英文。靠它决定显示什么，等于让界面依赖一句日志的措辞 —— 改一次措辞就把每一条**悄悄重新归类** |
| **「读不出来」是独立的一种情况**，既不折进「两端都改了」，也**不显示「0 处差异」** | 远端文件存在但解不开时，两侧**根本没有可比较的内容**。「0 处差异」读起来就是「两边一致」—— 这是唯一绝不能从一份读不出的载荷里得出的结论 |
| **不可评审的 decision 也报「读不出来」**（`kindOf` 的 `else` 分支，而不是 `BOTH_EDITED`） | 规划器只扣下冲突 / 删改 / 不可读三类。若将来它扣下别的，界面应该说「这条我看不了」，而不是**编一个像样的错故事** |
| **字段差异单独分类**：`FieldDeltaKind` 八态（本机改过 / 云端改过 / 两端改得不一样 / 本机有云端没有 / 云端有本机没有 / 本机删了这个字段 / 云端删了这个字段 / **两端都没了这个字段**） | 只并排两列值，用户还得自己推是哪边动的。而「两边都没了这个字段」在删改冲突里**真的会发生**（幸存的那一侧自己也少了一个字段，见 `ThreeWayMerge.editDiff`）—— 把它说成「两端都改了」会把用户指向一个**已经不存在**的字段 |
| **字段名一律用原始列名（wire key）**，不做「友好名」 | 同步层用**数据库列名**作键，而 `name` / `description` 两列的**模型含义与列名相反**（§7.8）。在这里发明友好名会让屏幕与数据各说一套 —— 而这两列恰恰是最容易译错、译错又最容易让人改错东西的地方 |
| **`null` 显示成「（无）」，不显示成空** | 在 `FieldDiff` 里 `null` 的含义是「这一侧没有这个字段」，与「有，但是空串」是两件事（§7.2）。画成空白就等于把两者抹平 |
| **「值被截断」与「列表被截断」都要主动说出来**：`Decoded.capped` / `Decoded.truncated` 随载荷带回来，界面各出一行提示 | 载荷要跨 Binder 送进 dialog arguments（见下），所以必须设上限。**静默截断**会让用户以为看到的就是全部差异，甚至以为那段值本身就是那样 |
| **截断位在发生截断的那一刻记录，绝不事后从文本推断** | 一个真实的值可能合法地以 `…` 结尾、长度也恰好等于上限。按「结尾是不是省略号」推断，会给一个**完整**的值贴上「已截断」的标签 |
| **冲突经 `Bundle`（dialog arguments）传递，不放静态持有者** | 静态持有者在进程被杀后回来是**空的** ⇒ 界面显示「没有需要确认的记录」，而这与「确实没有」长得一模一样。放 arguments 里，对话框可被系统随时重建而内容不变 |
| **载荷是两层的 `RecordCodec`**（外层 `r0 = <内层>`；内层 `id` / `note` / `decision` / `d.<i>.<side>`） | 复用它已经验证过的转义（`%` / `=` / 换行），而不是再造一套转义和一套转义 bug。外层会把内层的换行转义掉 ⇒ 无论交易的 `notes` 里有什么，两层都不会被互相误读 |
| **缺省值写成「省略键」，绝不写成空值**：某一侧没有值 ⇒ 内层**不写这个键**；值本身是空串 ⇒ 写成 `key=` | 本格式最容易犯、也最贵的错：把 `null` 编成 `""`，解码后「对端没有这个字段」就变成「**对端把它清空了**」 |
| **构造逻辑与 `Fragment` 生命周期解耦**：`build(context, payload)` / `contentView(context, payload)` 不要求 attach，`onCreateDialog` 只负责把 `requireContext()` 递进去 | 这个对话框是在**用户已经被告知「有记录需要确认」之后**才打开的。只在显示时才会失败的构造，留给用户的是一句承诺和一块空白 —— 必须能在单测里真的构建出来 |
| **「有没有入口」另有源码守卫**（`ConflictCenterWiringTest`）：`showSyncOutcome` 里必须有 `showConflicts(result.reviews)`，且**排在 `deservesCounts` 的提前返回之前** | 接线整条就是 `if (…) show(…)`，**没有任何新字面量** —— 重构时被删掉不会让任何测试变红，用户则永远看不到这个界面。顺序也要盯：冲突列表不能被「本轮没什么可计数」那道闸一起挡掉 |

- 新增：`sync/ConflictDigest.kt`（`ConflictKind` / `FieldDeltaKind` / `ConflictRow` / `MergeDecision.fieldDiffs` / `ConflictDigest.of|kindOf|deltaKindOf`）、`ui/settings/ConflictCenterCodec.kt`、`ui/settings/ConflictCenterText.kt`、`ui/settings/ConflictCenterDialogFragment.kt`；改动：`ui/settings/BackupPreferenceFragment.kt`（`showSyncOutcome` 里增 `if (result.reviews.isNotEmpty()) showConflicts(result.reviews)`，并新增 `showConflicts`）与 `res/values/strings.xml`（冲突中心文案块 30 条）。
- 已知限制（诚实记录）：① ~~**只读**~~ —— **已在 v1.27（②-v3）补齐**「保留本机 / 采用云端」，见 §7.11；原先不做是因为解决一条冲突要求「下一轮把它强制推（或拉）」，也就是一条**能被下一轮规划器读到的持久化覆盖**，它有自己的失败面；本轮不做半成品；② **不进历史** —— 冲突随这一轮结果存在，关掉对话框就没了（它不是账本的属性，而是「这一次比较」的产物）；③ **未做「真机启动」测试** —— 本轮验到「对话框能构建 + 内容正确 + 接线在场」；`Robolectric` 下没有宿主 Activity 时 `Dialog.show()` 不可用（见环境文档 **K23**），这条覆盖缺口如实记录；④ 字段名是列名、未本地化（**刻意**，见上表）。

### 7.11 冲突解决动作（②-v3，第二十七轮已落地）

§7.10 让冲突「看得见」，但**按不动**。本轮补上唯一的写路径：用户在冲突中心为某条记录拍了板
（**保留本机** / **采用云端**），这个答案要能被**下一轮**的规划器读到并兑现。核心是下面这几条：

| 约定 | 原因 |
|---|---|
| **存的是「边」不是「动词」**：`ResolveSide.LOCAL` / `REMOTE`，不是 `PUSH` / `PULL` | 「保留本机」在本机**有**该记录时是 `PUSH`，在本机**已删**（我们删的、对端改了）时是 `DELETE_REMOTE`。把用户的话读成「推」，会**复活**一条他特意删掉的记录。兑现方向由**当时的本地/远端状态**决定，不由答案的字面决定 |
| **答案只在「本会被扣下」的地方被读**：`forcedBy(...)` 的调用点 = 合并段的 `Conflict` / `DeleteVsEdit` 与「存在但读不出」段 | 别的记录一律走规则合并，用户没吭声的东西**一个字都不改**。在别处也读答案，等于让「用户曾经答过一条冲突」变成「所有记录都受这条答案影响」 |
| **`forcedBy` 可能返回 `null`**（采用云端 × 对端存在但读不出） | 「采用云端」要的是对端的**内容**，而读不出时**没有内容可采**。此时**不兑现**：记录继续停驻、答案**继续留着**，等某一轮对端可读了再兑现。返回一个假的 `PULL`（空字段）会把记录清空 |
| **对端已确认删除 ⇒ 「采用云端」就是删本地，并补一条墓碑** | 对端的「版本」就是**它的缺席**。兑现成 `DELETE_LOCAL`；不补墓碑的话，下一轮本机所见 = 记录没了、基线还在 ⇒ 被读成「用户删了」而**反向删掉对端**，两端一起丢 |
| **强制推的前置条件不用 `pushPrecondition`**：对端无副本 ⇒ `If-None-Match: *`（创建一个**已有基线**的记录） | 用户「保留本机」对上的是对端的**删除**：对端那份已经没了，此时用 `If-Match` 打一个**对端已经丢掉的 etag**，会让这次写**每一轮都失败** —— 一个用户永远兑现不了的答案。它是**创建**，不是更新 |
| **答案的消费分两种，且时机不同**：`resolutionsApplied ∩ landed`（动作**真的落地**后才清）vs `resolutionsDropped`（该记录**已不再停驻** ⇒ 清除） | 前者：一次被 `412` 拒的写、或一次被 store 拒的拉取，**不能**吃掉用户的选择 —— 否则冲突会被重新问一遍（用户已经答过）。后者：留着过期答案会让一个**旧选择**在将来某一轮**凭空复活**并强制一次用户当时没要的写 |
| **只有本轮「看过」的身份才能被判过期**：`examined = 本地 ∪ 远端 ∪ 基线`，且必须**落在本轮真的产出了动作的 `decided` 集里** | 本轮只同步账号时，一条**交易**的答案必须**原样保留**（否则「同步账号」会静默丢掉交易的答案）；而一次**首见缺席**只被**观察**（不产出动作）⇒ 也不能判过期 —— 二轮晋升后它**正好**会被停驻，用户的答案那时才是对的 |
| **写路径只有一个**：`ConflictResolutionStore.remember`，且**如实返回布尔** | 写账本库会抛异常，而抛在 dialog 点击回调里的异常会被框架**吞掉** —— 对话框关掉、答案**根本不存在**。`putResolution` 自己也会「不抛但返回 `-1`」⇒ 只看「没抛异常」等于按钮撒谎。失败必须能被告知（`Timber.e` + 返回 `false` + Toast） |
| **读不出的行不给「采用云端」按钮**（`ConflictCenterText.sidesFor(UNREADABLE)` 只给 `LOCAL`） | 一个**永远无法兑现**的按钮比没有按钮更坏：答案被存下、每轮被静默忽略、而用户以为自己已经处理完了 |

- 新增：`sync/ConflictResolution.kt`（`ResolveSide`（`id` 字面量 `local`/`remote` + `fromId`）+ `StoredResolution`）、
  `java/.../ui/settings/ConflictResolutionStore.kt`（写路径单点）。改动：`sync/SyncPlan.kt`
  （`resolutionsApplied` / `resolutionsDropped` 两个集合 + `PlanInput.resolutions`，**默认空** ⇒ 旧调用方一律不受影响）、
  `sync/SyncRoundPlanner.kt`（`forcedBy` + 过期判定）、`sync/SyncRound.kt`（`getResolutions()` 读答案 + `landed` 集合 +
  `commitResolutions`）、`db/DatabaseSchema.kt`（`SyncResolutionEntry`，**35→36**）、`db/DatabaseHelper.kt` /
  `db/MigrationHelper.kt#migrateTo36`、`db/adapter/SyncStateDbAdapter.kt`（`getResolutions` / `getResolution` /
  `putResolution`（返回 `Boolean`）/ `clearResolution` / `clearResolutions`（单事务）/ `resolutionCount`）、
  `java/.../ui/settings/ConflictCenterText.kt`（`sidesFor(kind)` + `of(side)`）、
  `java/.../ui/settings/ConflictCenterDialogFragment.kt`（两个按钮 → `remember` → Toast）、`res/values/strings.xml`（5 条）。
- 已知限制（诚实记录）：① **答案要等下一轮同步才生效**（本轮结束即写答案；兑现发生在下一次 `SyncRound`），
  提示语已**明说**「下次同步生效」；② **不为答案做「立刻推一次」**（避免在用户看屏幕时对端已移动 ⇒ 答案基于过期视图）；
  ③ 冲突中心**仍不进历史**（关掉对话框就没了，见 §7.10 ②）；④ 未做「真机启动」测试（同 §7.10 ③）。

---

## 8. 加密与口令方案（② 的配套）

| 项 | 设计 |
|---|---|
| 口令 | 用户设置并保存；**明文只存在于内存**，落盘只存"是否已设置"+ 校验用的派生值 |
| KDF | PBKDF2（`AndroidX Security` 或 JDK 自带）或 Argon2；迭代/内存参数写进密文头（便于将来升级） |
| 加密 | AES-GCM（认证加密，防篡改） |
| 作用范围 | **备份 / 导出 / 导入都可选加密**（决策 2）：不加密 = 明文；加密 = 密文 + 口令 |
| ⚠️ 兼容性红线 | **加密后的账本桌面 GnuCash 无法直接打开** ⇒ 必须在 App 内**解密后再导出**给桌面；UI 必须**明确提示** |
| ⚠️ 口令丢失 | = 数据报废。首设口令时**必须二次确认 + 不可逆警示**，并提供"口令提示语"（可选，也加密存） |
| 传输 | WebDAV 走 HTTPS；`net/TrustExt.kt` 已有 TLS 信任处理，复用而非新写 |

### 8.1 v1 加密备份容器（第十四轮已落地）

`sync/EncryptedBackup.kt` 把"整本书的备份"（本 app 已经写出的 gzip 过的 GnuCash XML）包成一个**自描述**容器：口令**只存在于内存**，落盘 / 上线的是密文；恢复只需要"容器 + 口令"。

```
-----BEGIN GNUCASH POCKET BACKUP-----
version: 1
kdf: PBKDF2WithHmacSHA256
iterations: 600000
cipher: AES/GCM/NoPadding
keybits: 256
salt: <base64, 16B>
iv: <base64, 12B>
-----END GNUCASH POCKET BACKUP HEADER-----
<raw ciphertext, tag included>
```

| 约定 | 理由 |
|---|---|
| **首行 magic 是文本**（`isEncrypted()` 一眼判定） | 恢复路径**先判再动**：结构上不可能把密文当 `.gnca` 喂给导入器（或反过来） |
| **头部为文本、且在正文之前** | 将来版本能**先读参数再解密**；二进制尾注做不到 |
| **头部字节作为 AAD**（GCM associated data） | 否则 `iterations` / `salt` 被改写**要等到产出乱码才发现**；现在任何头部改动与"口令错"走**同一条**失败路径 —— 只有一个失败面要推理 |
| **版本号是唯一前向兼容闸；算法名按版本钉死** | 头部里任何我们"不认识的算法" ⇒ 判定为**损坏**，而不是"更新的文件"（真正的更新会以新版本号到来） |
| **PBKDF2-HMAC-SHA256 / AES-256-GCM / salt 16B / IV 12B / tag 128bit**，迭代数写进头部且**可注入** | 生产 600k、测试 1000（否则一个测试类要跑成分钟级，而多覆盖不了任何东西） |
| **口令错 / 密文被改 / 头部被改 ⇒ 一律 `WrongPasswordException`** | GCM 只认证不区分；消息里**同时**写明"口令错**或**文件已损坏"，避免把用户引向错误的问题 |
| **空口令不允许加密**（`require`）；对上层把 "null 或空" 统一读作**不加密** | 两个入口行为一致，不会一个报错、另一个静默走明文 |

**远端备份（v1 单向，`sync/RemoteBackup.kt`）**：

| 约定 | 理由 |
|---|---|
| 路径**只有一条**：`/backup/<bookUid>.gnca`（加不加密都在这里） | 若按模式分两个文件名，一次**切换模式的上传**就会留下"陈旧兄弟文件"，之后恢复得**猜**哪个是当前的 —— 猜错就恢复出一本已被淘汰的书；扩展名还是"人"和 GnuCash 认这个文件**身份**的地方 |
| 扩展名**不因加密而改变**，加不加密由**内容 magic** 声明 | 同上。容器首行本身就是给人看的（明文提示"桌面 GnuCash 打不开它" —— §8 红线） |
| **硬守卫**：远端是密文而**没有口令** ⇒ `PassphraseRequired`，**一个字节都不返回** | 把密文当书交回 = "恢复说成功、账本变乱码"的**持久静默**故障（本功能存在的唯一理由就是防它） |
| 远端是明文而**有口令** ⇒ 照常返回明文 | 远端可能早于口令存在；在此拒绝会让"刚设了口令"反而**恢复不了自己的旧备份** |
| 上传 `PUT` **不带条件**（`PutPrecondition.None`） | §7.6 的"写远端一律带条件"是为了保护**合并**（防对端同刻写同一条 ⇒ 丢更新）；v1 是**单向备份**、槽位属于本机，"覆盖已有的"正是**被要求**的行为 |
| 返回五态：`Missing` / `Unreadable` / `WrongPassphrase` / `PassphraseRequired` / `Failed` | 五者**用户下一步动作完全不同**（没备份 / 备份坏了 / 口令错 / 要输口令 / 网络问题），压成一个"失败"就没人知道该改什么 |
| 与 v2 每记录布局**不冲突** | `SyncLayout.parse("/backup/x.gnca")` 必然返回 `null`（`backup` 不是 `SyncRecordType.id`）⇒ v2 的 `materialize` 对它 `?: continue`，那一轮看不见它 |

### 8.2 WebDAV 连接约定（第十五轮已落地）

`sync/webdav/WebDavConnection.kt` 是“用户在同步设置页里填的那一屏”的纯核心：填完先 `check()`，把每一条**只在运行时才暴露、且不报错、只管错**的填法变成**类型化的 `Problem`**。

| 约定 | 为什么必须拦 |
|---|---|
| **地址必须带 `https://` / `http://` 前缀**（`MISSING_SCHEME`） | 最常见的粘贴形态 `cloud.example.com/remote.php/dav` 会被朴素解析读成**主机名**的一部分 |
| **地址里不许内嵌凭据**（`CREDENTIALS_IN_URL`：authority 里出现 `@`） | `https://alice:secret@host/dav` 语法合法却**认证不了任何东西**（OkHttp 不会把 URL user-info 变成 `Authorization` 头）⇒ 永远 401，而用户坚信密码没错；留着它还会把口令写进**每一条请求 URL 与日志** |
| **默认拒绝明文 `http://`**（`PLAINTEXT`；`allowPlaintext` 需显式打开） | 明文 HTTP 会把用户名与口令直接放上线。与 `WebDavOkHttpTransport` 的 `trustAllCertificates` 默认关闭同一立场：**不安全默认必须由用户显式打开** |
| **地址里不许有字面空格 / 控制字符**（`UNESCAPED_CHARACTER`） | 最安静的一种：OkHttp 会把它**悄悄编码**成 `%20` 发出去，而客户端拿服务器回的 href 与**未编码**的根比对 ⇒ 永远不匹配、同步**什么都不做**。要求写成 `%20` |
| **不许 `.` / `..` 段**（`RELATIVE_SEGMENT`） | 同步根是**所有记录路径的坐标系**；根偏一级 ⇒ 每条远端记录都像“不存在” ⇒ 合并层读成“对端把东西全删了” |
| **不许 `%2F` 段**（`ENCODED_SEPARATOR`） | `WebDavPath` 已记录的同族陷阱：线路上是字面 `%2F`、一次无害的解码之后就成了 `/` ⇒ 请求的根与比对的根**不是同一个** |
| **不许查询串 / 片段**（`QUERY_OR_FRAGMENT`） | WebDAV 的根不该有；静默丢掉等于用户的意图**蒸发** |
| **归一化**：scheme 与 authority 转小写、丢尾斜杠、折叠空段，而**路径大小写与百分号编码原样保留**（不解码） | 路径是服务器上那个文件夹的**身份**；改大小写或解码都会让客户端去要**另一个**文件夹 |
| **用户名去首尾空白，口令永远不动** | 粘贴来的 `" alice "` 是笔误；而口令首尾的空格**可能就是口令的一部分**，静默改写它就是“同一个口令在我手机上能用”那类事故 |
| **填了口令必须填用户名**（`PASSWORD_WITHOUT_USER`） | transport 只在**有用户名**时才构造 `Authorization` ⇒ 口令被静默丢弃、请求变成匿名 |
| **接缝是 `Ready.connect(WebDavTransportFactory)`** | 工厂拿到的是**刚校验过的**凭据 ⇒ 结构上不可能出现“校验过的连接 + 用别的东西搭出来的 transport” |
| `check()` **从不抛异常** | 设置页两条分支（可用 / 不可用）都要渲染；用例把 19 种畸形输入跑一遍只是把这句话钉住 |
| **归一化后的地址照原样上线** | `connect` 的用例断言写请求落到 `MKCOL <base>/backup` 与 `PUT <base>/backup/<uid>.gnca` —— 中间任何一层再编码 / 再改大小写都会被它抓到 |

### 8.3 恢复载荷守卫（第十五轮已落地）

`util/BookPayload.kt` 回答一个只有**真实网络**才会问的问题：**这次的 `200` 里到底是不是一本书**。

| 约定 | 为什么 |
|---|---|
| **守卫站在“写回本地账本之前”** | 恢复的次序是“下载 → 写回本地 → 交给导入器”。等导入器报错，账本**已经被覆盖**了 —— 错误是响的，但**已经晚了**。`RemoteBackup.open()` 因此在**明文与解密后**统一过一遍守卫 |
| **拒绝三类：空体 / 网页 / 认不出的容器** | 空体（某些服务器对集合回 `200` + 0 字节）；网页（captive portal、反代后的 SSO 登录页、指向 web 根的错误地址 —— 全都是**合法 200**）；以及没有任何 gzip / zip / sqlite / XML 签名的东西 |
| **网页判定看前 1 KiB 里有没有 `<html`，而不是看前缀** | 真实的登录页常以注释、`<!DOCTYPE …>` 变体或 `<meta>` 开关 ⇒ 锚在前缀会正好**放过**它们 |
| **二进制容器先判** | 一本 gzip 过的书在**压缩流中**碰巧出现 `<html`，不该被判成网页 ⇒ 网页测试只在**没有任何容器命中**时才跑 |
| **报 `NotABook`，而不是 `Failed` / `Unreadable`** | 三者用户的下一步动作不同：网络被拒 ⇒ 查网络；文件是我们自己的但坏了 ⇒ 换备份；**这是一个网页** ⇒ 查**地址**（多半指向了登录页或错的文件夹） |
| **魔数只有一份，且 `ImporterFactory` 委派给它** | 导入器认识哪些容器、恢复就该接受哪些；两份拷贝必然漂移 —— 本轮正是靠集中化把 zip 的**死分支**挖了出来（§5② 第十五轮块） |
| **gzip 只认 2 字节魔数**（与 `ImporterFactory` 用的 `GZIPInputStream.GZIP_MAGIC` 同阈值） | 守卫比导入器**更严**会让“导入器能打开的文件被恢复拒绝”；截断由别处兜住（加密时靠 GCM tag、明文时靠解压器） |

---

## 9. OCR / ASR 选型（已核实数据）

### 9.1 场景决定权重

- **OCR 的输入是"UI 截图 / 分享图片"，不是拍照扫描件**：支付成功页、账单列表、聊天记录 —— 清晰印刷体、**中文+数字混排**、小字号无衬线。这直接让 Tesseract 吃亏、让 PaddleOCR 占优。
- **ASR 的关键不是"转写"而是"ITN"**：「房租三千五」必须变成 `3500`。没有 ITN，下游 NLU 得自己写中文数字消歧，而「三千五」= 3500 / 3.5 / 35000 正是记账最不能错的地方。

### 9.2 OCR 对照（体量为实测/核实值）

| 方案 | 体量 | 许可 | 中文+UI截图 | 预处理 | 集成难度 | 离线 | ABI |
|---|---|---|---|---|---|---|---|
| **PaddleOCR PP-OCRv5_mobile + ONNX Runtime**（**选定**） | det 4.7 + rec 16 + cls 0.96 ≈ **21.7MB** + ORT ≈12MB | Apache-2.0 + MIT | **强**（中文+数字原生） | 基本免（自带 det+cls 方向矫正） | 中（官方 `deploy/ppocr-android` 就是 Kotlin+Compose+minSdk26，可直接抄） | ✅ | arm64-v8a / v7a（**需自补 x86_64**，见 E7） |
| PaddleOCR + ncnn | 同模型 + 运行库 ≈200KB | Apache-2.0 + BSD-3 | 强 | 基本免 | 中高（多一步 onnx2ncnn + JNI） | ✅ | **含 x86** ✅ |
| RapidOCR(ONNX) | ≈10–20MB | Apache-2.0 | 强（同源） | 基本免 | 低（自带 android 例程） | ✅ | arm64 等 |
| Tesseract4Android 4.9.0 | chi_sim fast 2.35MB / best 12.5MB / 标准 42.3MB | Apache-2.0 | **弱**（UI 小字丢字） | **强依赖**（二值化+放大+调 psm） | 低（JitPack 一行） | ✅ | 每 ABI 各 .so |
| ML Kit `text-recognition-chinese` **bundled** | ≈+4MB/架构 | **闭源 Google SDK** | 中上 | 免 | 最低 | ✅（bundled 离线；**unbundled 需 GMS+联网，中国区不可用**） | 各 ABI |
| 云 OCR（百度/腾讯/阿里/讯飞） | 0（联网） | 商业 | 最高 | 免 | 低 | ❌ | 无 |

### 9.3 ASR 对照

| 方案 | 模型体积 | 许可 | 流式 | **自带 ITN+标点** | 中文短句 | 集成 | 离线 | ABI |
|---|---|---|---|---|---|---|---|---|
| **sherpa-onnx + SenseVoice-Small int8**（**选定**） | **228MB**（fp32 894MB） | Apache-2.0 | 非流式（配 VAD） | **✅ 两者都自带** | **高（CER≈8%）** | 中（官方 AAR） | ✅ | arm64/v7a/**x86_64**/x86 ✅ |
| sherpa-onnx + Paraformer-zh | 227MB | Apache-2.0 | 流式/非流式 | ❌ 需外挂标点（int8 **+72MB**） | 高（CER≈10%） | 中 | ✅ | 同上 |
| sherpa-onnx + Zipformer 流式 | 20 / 73 / 160 / 736MB | Apache-2.0 | ✅ | ❌ 需外挂标点 | 中-高 | 中 | ✅ | 同上 |
| whisper.cpp | tiny 31–75 / base 57–142 / small 181–466MB | **MIT** | 非流式(30s 窗) | ❌ | **低（base CER≈31%）** | 中（**无官方 AAR**） | ✅ | arm64/v7a |
| Vosk | small-cn **42MB** / 大模型 1.3GB | Apache-2.0 | ✅ 原生流式 | ❌ | 中-低（**数字易错**） | **最低**（官方 Maven） | ✅ | arm64/v7a/x86/x86_64 |
| 系统 SpeechRecognizer | 0 | 系统 | 视引擎 | ❌ | 视设备 | 低但**不可靠**（无 GMS 机 `isRecognitionAvailable()` 返回 false） | ⚠️ 不保证 | 无 |

### 9.4 选定与理由

- **OCR = PaddleOCR PP-OCRv5_mobile（det+cls+rec）+ ONNX Runtime**
  ①全链路 Apache-2.0(+MIT) 与主项目一致；②UI 截图+中文数字混排精度最高且自带矫正；③纯离线零 GMS；④官方 Demo 技术栈（Kotlin/Compose/minSdk26）与本仓库**完全对齐**，落地最快。
  **落败方案的适用条件**：极致小体积/愿多转一层 → ncnn（且**自带 x86，利于模拟器门禁**）；只求 Apache 且接受精度下降 → Tesseract fast。
- **ASR = sherpa-onnx + SenseVoice-Small int8 + silero VAD(629KB)**
  唯一自带 **ITN + 标点**，直接命中"金额/数字口语 → 结构化"；Apache-2.0；官方 AAR 含 **x86_64**（可上模拟器）。
  **落败方案的适用条件**：体积优先 → sherpa-onnx 流式 Zipformer 小模型（20–73MB）或 Vosk（42MB），代价是 ITN 自补、标点外挂、数字精度下降。

### 9.5 ★ 跨模块集成风险（必须最先验证）

**OCR 用 onnxruntime、ASR 用 sherpa-onnx，两者会撞同一个 `libonnxruntime.so`。**
`sherpa-onnx` 的 JNI 依赖 ORT 的版本化符号 `OrtGetApiBase@VERS_x.y.z`；若 App 中存在两份不同版本的 `libonnxruntime.so`，运行期会崩 `dlopen failed: cannot locate symbol "OrtGetApiBase"`。

→ **缓解**：全程只保留**一份** `libonnxruntime.so`，两处用**同一 ORT 版本**（或按 sherpa-onnx 文档用自有 ORT 重编）。**必须逐个 release 核对 AAR 实际捆绑的 ORT 版本**。
→ **这是本项目最靠前的门禁（P0.5），不通过则上架即崩。**

### 9.6 模型分发（决策 D5）

**分级**：OCR 模型（≈22MB）**随包内置**（保证"截图记账"开箱可用）；ASR 模型（228MB）**首次使用时按需下载**（APK 保持小巧，一次下载后完全离线）。

---

## 10. L3 脚本层设计

| 项 | 设计 |
|---|---|
| 可脚本化的四类扩展点 | ①账单导入解析（自定义列映射/正则后处理）②标签规则（关键词→标签/分类）③模板（脚本化生成）④报表（自定义聚合视图） |
| 引擎选型 | **LuaJ**（纯 Java、体积小、**零 ABI 负担**）优先；若需更高性能再评估 QuickJS（原生，需 x86_64 + arm64 两套） |
| 沙箱 | 白名单 API（只暴露"读入参/写结果/记日志"）；**无文件、无网络、无反射** |
| 隔离 | 每条脚本设**超时**与**内存上限**；异常必须被捕获并给出**脚本行号** |
| 分发 | 脚本以文本存本地（便于用户复制粘贴分享）；可选从 URL 拉取（默认关闭，需显式确认） |
| 与 SPI 的关系 | 脚本是 L0 SPI 的**一种实现**，注册进同一注册表；因此"宿主禁用脚本"时功能仍可用 |

---

## 11. 分期路线图

| 期 | 内容 | 出口判据（可验收） |
|---|---|---|
| **P0 地基** | E1/E2 环境已验证 ✅ → **无 Firebase 构建开关**（决策 9）→ Hilt 接入（决策 8）→ L0 SPI 骨架 → 单测基线 | `./gradlew :app:assembleDevelopmentDebug` **在缺 `google-services.json` 时可成功**；`./gradlew :app:testDevelopmentDebugUnitTest` **全绿**（既有 42 个测试文件不回归） |
| **P0.5 集成冒烟** | ★ **ORT 单 `.so` 共存冒烟**（OCR + sherpa-onnx 同进程连续调用） | 真机/模拟器上 OCR 与 ASR **连续调用不崩**；`libonnxruntime.so` 在 APK 中**只有一份** |
| **P1 低风险高收益** | ⑥记账模板 · ⑨动态快捷方式 · **①a 增强搜索（全字段 + 多关键词与/或 + 搜索模板）** · ⑤分享图片记账（复用 OCR 管线） | 各自单测/仪器测试 + 模拟器手测；①a 的多关键词 / `None` / 括号 / 模板 round-trip 断言全绿；搜索结果不被 `#tag` 污染 |
| **P2 数据接入** | ③三平台账单导入（走 Importer SPI）· ⑧标签+回写 `#tag`+按标签统计（schema 31→32）· **①b 多标签检索（依赖 ⑧）** | 导入去重/编码/中性收支剔除的断言；**标签 XML 往返对拍**；①b 的标签 ALL/ANY/NONE 三条 SQL 断言；迁移自检通过 |
| **P2.5 L3 脚本层** | 脚本引擎接入 + 四类扩展点 API | 脚本**超时/异常隔离**用例；禁用脚本后宿主功能不受影响 |
| **P3 同步** | ② WebDAV（先 v1 单向加密备份 → 再 v2 GUID 三路合并 + 冲突中心，schema 32→34）；v2 的**决策内核 + 传输层 + 动作层 + 编排层 + 真实本地 store（账号/交易 + 分录）已落地**；**v1 的加密容器核心 + 远端备份/恢复编排 + WebDAV 连接校验（`WebDavConnection`）+ 恢复载荷守卫（`BookPayload`）已落地**（设置页 UI 与 Android 侧凭据/transport 装配未做）；**②-v3 冲突解决动作（保留本机／采用云端：答案持久化 + 下轮强制兑现）已落地**（第二十七轮） | 双设备**并发改同一交易**的合并用例；**墓碑不丢**；列举失败**不误判删除**；快照与水位同事务；写远端**一律带条件**（`If-Match`/`If-None-Match`）且 `412` 走**重新列举重合并**而非重试；**建目录与上传按序且不对根做 MKCOL**；`DELETE 404` / `MKCOL 405` 判**幂等成功**；**列举不完整⇒整轮不动**；**首见缺席只记 pending、二轮才晋升墓碑**；**地址内嵌凭据 / 明文 http / 字面空格一律被拒**（否则“永远 401”或“同步什么都不做”）；**`200` 不等于一本书**（网页 / 空体 / 认不出的容器 ⇒ `NotABook`，**绝不写回本地**）；**魔数只有一份**（`ImporterFactory` 委派 `BookPayload`） |
| **P4 采集能力** | ④悬浮球+截屏+OCR · ⑦语音记账+NLU | 权限与合规检查；**模拟器 x86_64 可跑**；ITN 单测覆盖「三千五 → 3500」 |

> 依赖关系：**P0 不做完，后面全部会返工**（Hilt 与 SPI 是所有功能的接入面）。P0.5 必须在写 OCR/ASR 业务代码**之前**通过。**①b（多标签检索）依赖 ⑧ 的标签表** —— ⑧ 不落地，①b 只能做 UI 壳，SQL 无从生成。

---

## 12. 验收与门禁

| 层 | 判据 |
|---|---|
| 单元测试 | `JAVA_HOME=D:\jdk-21\jdk-21.0.12.1+1 ./gradlew :app:testDevelopmentDebugUnitTest`（**必须 JDK 21**，见 E10） |
| 仪器测试 | `Start-AndroidEmulator.ps1 -AvdName mx_atd33` → `./gradlew :app:connectedDevelopmentDebugAndroidTest`（Espresso + UiAutomator） |
| **跨实现对拍**（本仓库最高价值） | Kotlin `Exporter`/`Importer` ↔ 桌面 GnuCash XML ↔ `gnc/` 模块，逐类喂相同输入比结论：**缺字段 / 非法枚举 / 未知标识符 / 多余字段 / 往返后集合字段是否被静默清空** |
| 咬合测试 | 每个 bug 的修复断言，都要**先回退修法确认它会变红**（否则可能是空壳断言） |
| 回归 | 每次 schema 迁移后跑"迁移自检"（表/列/索引/行数） |
| 门禁环境 | 全部在**沙箱外**跑（沙箱拦写盘会把"编得过"变"任务失败"，见 E8）；⚠️ 沙箱放行标志**仅前台发起时生效**，后台发起仍会被拦 —— 门禁脚本要前台跑或显式放行；native 依赖必须出 **x86_64** 才能进模拟器门禁（arm64 只能真机验） |

---

## 13. 风险登记表与待验证清单

### 13.1 风险登记（按影响排序）

| # | 风险 | 影响 | 对策 |
|---|---|---|---|
| R-1 | 双向合并写坏数据（"日志全绿、交易少了"） | **不可逆丢数据** | 快照+水位同事务；删除靠"已确认的缺席"；base 幂等；每步有对拍 |
| R-2 | ORT 双 `.so` 共存 → 运行期崩 | 上架即崩 | P0.5 冒烟门禁，版本对齐，APK 内只留一份 |
| R-3 | 加密口令丢失 | 数据报废 | 二次确认 + 不可逆警示 + 可选提示语（也加密存） |
| R-4 | 加密账本桌面打不开，用户困惑 | 支持成本 | UI 明示 + "导出给桌面"走自动解密 |
| R-5 | 账单格式漂移 | 导入失败 | 解析器声明期望列 + 真实文件 golden + 可脚本化修正 |
| R-6 | 悬浮球/截屏权限合规 | 上架被拒 | 隐私说明；权限未授予时优雅降级；不默认启用无障碍路线 |
| R-7 | Hilt 接入波及全部现有页面 | 大范围回归 | P0 先做"最小接入 + 既有测试全绿"，再逐步迁移 |
| R-8 | native 库缺 x86_64 | 模拟器门禁跑不了 | 选型阶段就要求 x86_64（PaddleOCR 需自补；ncnn/sherpa 自带） |
| R-9 | `#tag` 剥离不彻底 | 搜索结果变脏 | **已重估（第二轮）**：标签落在 `notes`，列表不显示 notes，故列表文本不受污染；剩下的"脏命中"只有"搜的词恰好是标签名"这一种，而那**命中正是用户期望**（搜"餐饮"就该找到 `#餐饮`）。真正的脏命中需要剥离列或 FTS 才能做得干净 ⇒ 与 R-12（FTS）合并处理，本轮不引入 SQL 侧字符串剥离（`TagCodec.stripTags()` 已就位，供展示层使用） |
| R-10 | 模型体积（ASR 228MB） | 包体/首启 | 分级分发（OCR 内置 / ASR 按需） |
| R-11 | 搜索"与/或"**静默走错语义**（`None` 未实现、布尔漏括号） | 结果错但不报错 | QueryBuilder 单点收口 + 括号在生成时钉死 + `None` 断言（咬合：回退后须变红） |
| R-12 | 多关键词 `LIKE '%x%'` 无索引 → 大数据量卡顿 | 体验 | 先 A（LIKE）后 B（FTS），万级数据实测再定；不提前引同步触发器 |

### 13.2 待验证清单（诚实清单）

| # | 待验 | 方式 | 现状 |
|---|---|---|---|
| V1 | 缺 `google-services.json` 时能否 `assemble` / `test`（不只是配置） | 真跑 `assembleDevelopmentDebug` | ✅ **已闭环（2026-10-11）**：`assembleDevelopmentDebug` BUILD SUCCESSFUL，见 E12；test 亦已闭环 |
| V2 | build-tools 37.x 是否必需（本机最高 36.0.0） | 编译报错观测 | ✅ **已闭环**：compileSdk 37 用 build-tools **36.0.0** 出包成功 ⇒ **不强制要求 37.x**（AGP 9.4.1 自行选中，无报错） |
| V3 | 本机缓存的 Gradle 9.3.1 深路径失败的确切机制 | 见 Gradle9 文档 §4.5 | 经验规律已定，机制未钉死 |
| V4 | PaddleOCR mobile **INT8 实际体积** | 自己量化后实测 | **未验** |
| V5 | sherpa-onnx 所选 AAR 实际捆绑的 **ORT 版本**（决定能否单 `.so` 共存） | 解包 AAR 看 `.so` 符号 | **未验**（P0.5 门禁） |
| V6 | 三平台账单**真实导出文件**的当前列结构（含微信加密 zip 的现状） | 各导一份真实文件做夹具 | **未验** |
| V7a | **App 内** XML 往返：`#tag` 经导出→导入后文本与标签镜像是否都在 | `TagXmlRoundTripTest`（真跑） | ✅ **已闭环（2026-10-11 第三轮）**：文本不丢（notes slot + `<split:memo>`）、镜像由双写钩子重建、导入后按标签搜索仍命中。**并已咬合验证**（注释掉钩子 → 4 例变红） |
| V7b | **桌面 GnuCash** 对 `<tag>`/`#tag` 的实际读取行为（决定回写策略细节） | 桌面端导入对拍 | ⚠️ **仍未验**（本机无桌面 GnuCash，无法对拍；V7a 已把 App 侧闭环） |
| V8 | `ScheduledActionService` 未在 Manifest 声明，实际如何运行（WorkManager？） | 读代码 + 运行验证 | **未验** |
| V9 | Kotlin 增量编译（`kotlin.incremental`）在 Z 盘的真实表现 | 真实 Android 构建观测 | **未验**（探针未覆盖该路径） |
| V10 | 单测能否在本机真跑 | 真跑 `testDevelopmentDebugUnitTest` | ✅ **已闭环**：必须 JDK 21（E10）；已跑通 |
| V11 | 沙箱是否真拦构建 | 沙箱内/外对照 | ✅ **已确认**：拦写盘（E8）；放行标志**仅前台生效** |
| V12 | 缺 `google-services.json` 对 `assemble` 的影响 | 真跑 `assembleDevelopmentDebug` | ✅ **已验（2026-10-11）**：BUILD SUCCESSFUL（沙箱外，JDK 21，EXIT=0），产物 `app-development-debug.apk`（39.3 MiB）。**新发现的未决项**：release 包需要签名配置（`Keystore file not set for signing config release`）→ 需要你指定 keystore 才能出 release 包 |

---

## 14. 附录

### 14.1 关键文件路径索引

| 用途 | 路径 |
|---|---|
| 仓库工作区 | `Z:\wdDaily\gnucash-az` |
| 应用构建脚本 | `app/build.gradle.kts`（flavor / google-services 开关） |
| 版本目录 | `gradle/libs.versions.toml` |
| DB schema | `app/src/main/java/org/gnucash/android/db/DatabaseSchema.kt` |
| DB 建表/迁移 | `.../db/DatabaseHelper.kt` |
| DAO | `.../db/adapter/*DbAdapter.kt`（13 个，含 `TagsDbAdapter`、`SyncStateDbAdapter`） |
| 标签编解码（**已完成**，单点收口） | `.../util/TagCodec.kt` |
| 标签模型 + 持久化（**已完成**） | `.../model/Tag.kt`、`.../db/adapter/TagsDbAdapter.kt` + `TagEntry`/`TransactionTagEntry`（schema **32**，迁移 `31→32`） |
| 标签单测 | `app/src/test/java/.../util/TagCodecTest.kt`、`.../test/unit/db/{TagsDbAdapterTest,TagMigrationTest,TagSearchIntegrationTest,TagChipFilterTest}.kt`、`.../test/unit/export/TagXmlRoundTripTest.kt` |
| 标签 chip 展示层（**已完成**） | `res/layout/cardview_transaction.xml`（`tag_chips_scroll`/`tag_chips`）、`res/layout/item_tag_chip.xml`、`res/drawable/bg_tag_chip.xml`、`.../ui/search/SearchResultViewHolder.kt#renderTags` |
| 搜索条件（sealed class，含多关键词 / `AnyField` / `Tag`） | `.../ui/search/SearchCriteria.kt` |
| 搜索 SQL 生成（**已收口**） | `.../ui/search/SearchQueryBuilder.kt`（`SearchForm.buildSQL()` 委托之） |
| 搜索模板编解码（**已完成**） | `.../ui/search/SearchTemplate.kt` |
| 搜索模板持久化（**已完成**） | `.../db/adapter/SearchTemplateDbAdapter.kt` + `SearchTemplateEntry`（schema **31**，迁移 `30→31`） |
| 搜索 UI（表单式 = 左侧导航栏「高级搜索」） | `.../ui/search/SearchFormFragment.kt`、`.../ui/search/SearchFormViewModel.kt`、`res/menu/search_form_actions.xml` |
| **全文搜索**（首页右上角「全文」入口；**已完成**，解析器零 Android 依赖） | `.../ui/search/FullTextQuery.kt`、`.../ui/search/FullTextSearchFragment.kt`、`res/layout/fragment_full_text_search.xml`；测试 `app/src/test/java/.../ui/search/FullTextQueryTest.kt` |
| **常用搜索**（①c 第四轮，**已完成**；两个搜索页共用同一套对话框，按 `kind` 分流） | `.../ui/search/SearchFavoritesUi.kt`（保存 / 列表套用 / 管理：多选·改名·**批量删**，删除二次确认）、`.../ui/search/SearchTemplate.kt`（`SearchTemplateKind` + `query` 信封，Form 载荷逐字节兼容）、`.../db/adapter/SearchTemplateDbAdapter.kt`（`getByKind` / `delete(List<Long>)` 单条 SQL `IN`） |
| 搜索单测（迁移用例的类 `SearchTemplateMigrationTest` 与 `TagMigrationTest` 分别同文件放在各自 adapter 测试里） | `app/src/test/java/.../ui/search/{SearchQueryBuilderTest,SearchTemplateTest,SearchFormTest,FullTextQueryTest}.kt` + `.../test/unit/db/{SearchTemplateDbAdapterTest,TagsDbAdapterTest}.kt` |
| 导出 | `.../export/{Exporter,ExportFormat,ExportTarget}.kt` + `csv|ofx|qif|sql|xml/` |
| 导入 | `.../importer/{ImporterFactory,ImportAsyncTask}.kt` + `sql|xml/` |
| 备份设置（导出目标/备份/恢复/**导入**） | `.../ui/settings/BackupPreferenceFragment.kt`、`res/xml/fragment_backup_preferences.xml` |
| 备份文件名过滤（**新增**，导入用；纯函数、无 Android 依赖） | `.../util/BackupFileFormat.kt` + 单测 `app/src/test/java/.../test/unit/util/BackupFileFormatTest.kt`；图标 `res/drawable/ic_import_backup.xml` |
| **三路合并纯函数核心**（②-v2，**已完成**，零 Android 依赖） | `.../sync/ThreeWayMerge.kt`（`decide` / `diffFields` / `editDiff` / `planDeletions`）、`.../sync/SyncRecordType.kt` |
| **同步基线/墓碑模型 + 持久化**（②-v2，**已完成**） | `.../model/SyncBase.kt`（含 `etag`/`pendingSince`）、`.../model/Tombstone.kt`、`.../db/adapter/SyncStateDbAdapter.kt` + `SyncStateEntry`/`TombstoneEntry`（schema **34**，迁移 `32→33`、`33→34`） |
| **同步单测** | `app/src/test/java/.../test/unit/sync/ThreeWayMergeTest.kt`、`.../test/unit/db/SyncStateDbAdapterTest.kt`（含同文件的 `SyncStateMigrationTest`） |
| **WebDAV 协议核心 / 动作层**（②-v2 网络层，**已完成**，零 Android 依赖） | `.../sync/webdav/WebDavPath.kt`、`WebDavXml.kt`、`WebDavListing.kt`、`WebDavClient.kt`、`WebDavTransport.kt`、`WebDavActions.kt` |
| **WebDAV transport**（平台接缝，唯一触网） | `.../sync/webdav/WebDavOkHttpTransport.kt`（Basic auth；`PROPFIND`/写动词/下载；`trustAllCertificates` 复用 `net/TrustExt.kt`） |
| **WebDAV 单测** | `app/src/test/java/.../test/unit/sync/webdav/{WebDavPathTest,WebDavXmlTest,WebDavClientTest,WebDavActionsTest,WebDavClientActionsTest}.kt` |
| **同步编排层**（②-v2，**已完成**，纯核心零 Android 依赖） | `.../sync/SyncLayout.kt`（`SyncIdentity` + 路径映射）、`.../sync/RecordCodec.kt`、`.../sync/SyncPlan.kt`、`.../sync/SyncRoundPlanner.kt`、`.../sync/LocalRecordStore.kt`（接缝）、`.../sync/SyncRound.kt`（驱动） |
| **同步编排单测** | `app/src/test/java/.../test/unit/sync/{SyncLayoutTest,RecordCodecTest,SyncRoundPlannerTest,SyncRoundTest}.kt` |
| **同步落盘层**（②-v2，**已完成**；`AccountFields`/`TransactionFields` 纯核心 + `DbLocalRecordStore` 平台侧） | `.../sync/AccountFields.kt`（账号字段映射，键 = `accounts` 列名）、`.../sync/TransactionFields.kt`（交易 + 内联分录字段映射，分录键 `split.<index>.<列名>`）、`java/.../sync/DbLocalRecordStore.kt`（`LocalRecordStore` 的 `accounts` + `transactions` 实现） |
| **同步落盘层单测** | `app/src/test/java/.../test/unit/sync/{AccountFieldsTest,TransactionFieldsTest,DbLocalRecordStoreTest}.kt` |
| **加密备份（②-v1）**（**已完成**；纯容器核心 + 纯远端编排，零 Android 依赖） | `.../sync/EncryptedBackup.kt`（自描述 AES-256-GCM 容器：PBKDF2-HMAC-SHA256 + **头部字节作 AAD** + 版本闸 + 类型化拒收）、`.../sync/RemoteBackup.kt`（单向远端备份/恢复：**单一固定路径** `/backup/<bookUid>.gnca` + 「远端是密文而无口令 ⇒ 拒收」硬守卫） |
| **加密备份单测** | `app/src/test/java/.../test/unit/sync/{EncryptedBackupTest,RemoteBackupTest}.kt`（各 24 / 21 例，纯 JVM） |
| **WebDAV 连接校验 + 恢复载荷守卫**（②-v1 接线第一半，**已完成**；纯核心，零 Android 依赖） | `.../sync/webdav/WebDavConnection.kt`（连接配置的 **11 类类型化校验 + 地址归一化**；`Ready.connect(WebDavTransportFactory)` 是纯核心与 OkHttp 之间唯一的接缝）、`.../util/BookPayload.kt`（**魔数单点** + `classify`/`inspect` 恢复守卫；`ImporterFactory` 委派之，顺带修正 zip 端序死分支） |
| **连接 / 载荷守卫单测** | `app/src/test/java/.../test/unit/sync/webdav/WebDavConnectionTest.kt`（27 例）、`.../test/unit/util/BookPayloadTest.kt`（18 例），均纯 JVM |
| **WebDAV 设置页接线**（②-v1 接线第二半，**已完成**；纯编排 + UI + 凭据存储） | `.../sync/WebDavSyncController.kt`（**新**；`checkedFirst(connection)` 是**唯一漏斗** —— 校验在拨号之前，`Invalid` ⇒ `NotUsable` 且**不发任何请求**；**空导出拒上传**；下载只有成功才带字节）、`.../sync/RemotePassphrasePolicy.kt`（**新**；4 条口令规则、**顺序即优先级**；`Use` 持 `CharArray`、`toString()` 脱敏）、`.../sync/BackupNotice.kt`（**新**；13 态 `BackupNotice` + 5 态 `ConnectionNotice`；`STATUS_NO_RESPONSE`（没连上）与 4xx/5xx（被拒）**分成两条**）、`java/.../sync/AndroidBookTransfer.kt`（**新**；导出/落地暂存，书读不出即拒上传）、`java/.../ui/settings/WebDavPreferences.kt`（**新**；读**默认** SharedPreferences，**口令不落盘**）、`java/.../ui/settings/WebDavNoticeText.kt`（**新**；值→文案单点，`preferenceKeyOf` 把出错项点到具体那一行）、`java/.../ui/settings/BackupPreferenceFragment.kt`（改；+8 行 UI + 口令对话框 + 恢复二次确认）、`res/xml/fragment_backup_preferences.xml`（改）、`res/layout/dialog_backup_passphrase.xml`（**新**） |
| **②-v2 接线第一半**（手动「一轮同步」+ 冲突评审回传，**第二十二轮已完成**；纯结果描述 + 薄适配层 + 设置页两行） | `.../sync/SyncNotice.kt`（**新**；8 态 `SyncNotice` + 2 态 `SyncAbandon` + `SyncNotices.of` **按严重度排序**取胜出分支；`deservesCounts` 仅 `NOTHING_TO_DO` 为 false）、`.../sync/SyncRunner.kt`（**新**；`connection.check()` **唯一漏斗** —— `Invalid` ⇒ `NotUsable` **零请求**；未捕获异常兜 `Failed`）、`java/.../sync/AndroidSyncDeviceId.kt`（**新**；设备 id read-or-create、**永不 regenerate**）、`java/.../ui/settings/SyncNoticeText.kt`（**新**；值→文案单点）、`.../sync/SyncRound.kt`（改；`abandonReason` + `reviews` **回传**，`conflicts` / `movedAnything` 降为**派生**）、`java/.../ui/settings/WebDavPreferences.kt`（改；`twoWaySync` **显式 opt-in**、默认 false）、`java/.../ui/settings/BackupPreferenceFragment.kt`（改；`syncNow` / `setupTwoWaySync` / `showSyncOutcome` / `syncRunner`）、`res/xml/fragment_backup_preferences.xml` + `res/values/{strings,donottranslate}.xml`（改） |
| **②-v2 接线第一半单测** | `app/src/test/java/.../test/unit/sync/{SyncNoticeTest,SyncRunnerTest}.kt`（13 / 11 例）、`.../ui/settings/SyncNoticeTextTest.kt`（10 例），另 `WebDavPreferencesTest` / `BackupPreferenceScreenTest` 补用例 |
| **②-v2 接线第二半**（冲突中心（只读）：一轮结束后把被扣下的记录逐条摊开看三方字段差异，**第二十三轮已完成**） | `.../sync/ConflictDigest.kt`（**新**；`ConflictKind` / `FieldDeltaKind` / `ConflictRow` / `MergeDecision.fieldDiffs` / `ConflictDigest.of\|kindOf\|deltaKindOf` —— 分类只看 decision 的**结构**、**不看 `note`**；`UNREADABLE` 独立成态、`kindOf` 的 `else` 也报「读不出来」而不是 `BOTH_EDITED`；`FieldDeltaKind` 八态含「**两端都没了这个字段**」）、`java/.../ui/settings/ConflictCenterCodec.kt`（**新**；两层 `RecordCodec`、**缺值 = 省略键**（`null` ≠ `""`）、`MAX_REVIEWS = 100` / `MAX_VALUE_CHARS = 512`、截断位**当场记录**、`dropped`/`capped`/`truncated` 回传、坏载荷不炸）、`java/.../ui/settings/ConflictCenterText.kt`（**新**；三组 `when` **穷举**）、`java/.../ui/settings/ConflictCenterDialogFragment.kt`（**新**；`build` / `contentView` **不要求 attach**；`newInstance` 把冲突编进 dialog arguments）、`java/.../ui/settings/BackupPreferenceFragment.kt`（改；`showSyncOutcome` 里 `showConflicts(result.reviews)`，**排在 `deservesCounts` 提前返回之前**）、`res/values/strings.xml`（改；冲突中心文案块 30 条） |
| **②-v2 接线第二半单测** | `app/src/test/java/.../test/unit/sync/ConflictDigestTest.kt`（15 例，纯 JVM）、`.../ui/settings/ConflictCenterCodecTest.kt`（19 例，纯 JVM）、`.../ui/settings/{ConflictCenterTextTest,ConflictCenterDialogTest,ConflictCenterWiringTest}.kt`（7 / 15 / 3 例） |
| **②-v1 接线第二半 单测** | `app/src/test/java/.../test/unit/sync/{RemotePassphrasePolicyTest,BackupNoticeTest,WebDavSyncControllerTest}.kt`（纯 JVM）、`.../ui/settings/{WebDavNoticeTextTest,WebDavPreferencesTest,BackupPreferenceScreenTest}.kt`（Robolectric；**首次给偏好页补测试**，坑见 **K21**） |
| **交易模板 · 数据层**（①d，**已完成**；新表 `transaction_templates`，schema **34 → 35**） | `db/DatabaseSchema.kt`（`TransactionTemplateEntry`：`_id/name/payload_json/sort_order/created_at/updated_at`）、`db/DatabaseHelper.kt`、`db/MigrationHelper.kt#migrateTo35`、`ui/template/TransactionTemplate.kt`（模型 + `TransactionTemplateCodec`：**空值 = 省略 key**，`null` 绝不退化成 `0`）、`db/adapter/TransactionTemplateDbAdapter.kt`（**新**；`saveByName` 同名覆盖、`getAll` 按 `sort_order`、`delete(id)`/`delete(ids)`） |
| **交易模板 · UI**（①d，**已完成**；抽屉入口 + 列表页增删改 + 编辑对话框） | `ui/template/TransactionTemplatesActivity.kt`（**新**，继承 `BaseDrawerActivity`）、`ui/template/TransactionTemplatesFragment.kt`（**新**，列表 + FAB + 溢出菜单「套用/编辑/删除」）、`ui/template/TransactionTemplateEditFragment.kt`（**新**，借贷两账户 + 可选金额）、`res/layout/{activity_transaction_templates,fragment_transaction_templates,item_transaction_template,dialog_transaction_template}.xml`（**新**）、`res/menu/transaction_template_item_actions.xml`（**新**）、`res/menu/nav_drawer_menu.xml` + `ui/common/BaseDrawerActivity.kt`（`nav_item_transaction_templates`） |
| **交易模板 → 快速新建**（①d，**已完成**） | `ui/common/UxArgument.kt`（`SELECTED_TEMPLATE_ID`）、`ui/transaction/TransactionFormFragment.kt`（`templatePrefill` + `applyTemplatePrefill`：摘要/备注/编号/金额预填，**空值不填**） |
| **交易表单 · 借/贷双账户**（①d，**已完成**） | `res/layout/fragment_transaction_form.xml`（`layout_debit_entry` + `input_debit_account_spinner`，原 `input_transaction_type` 隐藏）、`ui/transaction/TransactionFormFragment.kt`（`updateDebitAccountsList`/`getDebitAccount`/`setSelectedDebitAccount`；`extractSplitsFromView` 按位置定 `DEBIT`/`CREDIT`，不再依赖 type switch） |
| **搜索结果 · 排序 / 合计 / 分类统计 / 图表**（①d，**已完成**） | `ui/search/SearchSortOrder.kt`（**新**；四种排序的 `ORDER BY` 片段，金额 = **借方合计**相关子查询）、`db/adapter/TransactionsDbAdapter.kt#fetchSearch(where, orderBy)`（空 WHERE 走字面量 `1`，保持同一查询形状）、`ui/search/SearchSummary.kt`（**新**，纯函数聚合：按币种合计 / 按账户分区 / 按标签分桶）、`ui/search/SearchResultsViewModel.kt`（`sortOrder` + `summary`）、`ui/search/SearchResultsFragment.kt`（继承 `MenuFragment`；排序/合计/图表菜单）、`ui/search/SearchSummaryDialogFragment.kt`（**新**）、`ui/search/SearchChartDialogFragment.kt`（**新**，`PieChart`）、`res/layout/dialog_search_summary.xml`（**新**）、`res/menu/search_results_menu.xml`（**新**） |
| **①d 单测** | `app/src/test/java/.../ui/search/{SearchSortOrderTest,SearchSummaryTest}.kt`（**新**，纯 JVM）、`.../test/unit/db/SearchResultSortIntegrationTest.kt`（**新**，真库跑四种 `ORDER BY`）、`.../ui/template/TransactionTemplateCodecTest.kt`（**新**）、`.../test/unit/db/TransactionTemplateDbAdapterTest.kt`（**新**，含 `TransactionTemplateMigrationTest`：35 / 34→35 / 30→35） |
| **借贷账户模糊搜索**（①e，**已完成**） | `ui/account/AccountSearchFilter.kt`（**新**，纯函数：多词元 AND / 大小写不敏感 / 限定名+短名 / 前缀命中优先 / 空查询原样返回）、`ui/account/AccountSearchDialogFragment.kt`（**新**，输入即过滤 + `FragmentResult` 回传 uid）、`res/layout/dialog_account_search.xml`（**新**）、`ui/transaction/TransactionFormFragment.kt`（`debitAccountConditions()` / `creditAccountConditions()` 与 spinner **同源**；`btn_search_debit_account` / `btn_search_credit_account`；`position < 0` 兜底） |
| **搜索结果 · 多选**（①e，**已完成**） | `ui/search/SearchSelection.kt`（**新**，**UID 键控**选择集）、`ui/search/SearchResultsAdapter.kt`（`startSelection`/`toggle`/`selectAll`/`visibleUids`）、`ui/search/SearchResultViewHolder.kt`（长按进入 / 选择模式点击勾选 / 复选框与卡片淡出）、`res/layout/cardview_transaction.xml`（`GONE` 的 `selection_check`） |
| **搜索结果 · 批量删除 / 批量修改**（①e，**已完成**） | `res/menu/menu_search_bulk.xml`（**新**）、`ui/search/SearchResultsFragment.kt`（CAB + 删除二次确认 + 「批量选择」菜单项）、`ui/search/BulkEditTransactionsDialogFragment.kt`（**新**）+ `res/layout/dialog_bulk_edit.xml`（**新**）、`ui/search/SearchBulkWriter.kt`（**新**，同步可测；走模型 `addRecord`）、`ui/search/BulkEdit.kt`（**新**，纯函数：文本/日期/标签值语义，日期保留时分）、`ui/search/SearchResultsViewModel.kt`（`deleteTransactions` / `bulkUpdate` 只做委托 + 重查） |
| **①e 单测** | `app/src/test/java/.../ui/account/AccountSearchFilterTest.kt`（**新**，纯 JVM）、`.../ui/search/{SearchSelectionTest,BulkEditTest}.kt`（**新**，纯 JVM）、`.../test/unit/db/SearchBulkWriterTest.kt`（**新**，真库 17 例 ×2 SDK） |
| **扩展视图（①f，**已完成**）** | `ui/transaction/view/ExtendedViewField.kt` / `ExtendedView.kt` / `ExtendedViews.kt` / `ExtendedViewCodec.kt` / `ExtendedRowContent.kt` / `ExtendedViewStore.kt`（均**新**）、`ui/transaction/view/ExtendedViewsActivity.kt` / `ExtendedViewsFragment.kt` / `ExtendedViewEditFragment.kt`（均**新**）、`res/layout/{activity_extended_views,fragment_extended_views,item_extended_view,dialog_extended_view_edit}.xml`（**新**）、`res/menu/extended_view_item_actions.xml`（**新**）、`res/menu/transactions_list_actions.xml`（改：二级菜单 `menu_extended_view`）、`ui/transaction/TransactionsListFragment.kt`（改：`useExtendedView` / `extendedFields` / `renderExtended`）、`AndroidManifest.xml`（改：注册 `ExtendedViewsActivity`） |
| **借贷同号修复 + 批量异步（①e 后续，**已完成**）** | `ui/transaction/TransactionSides.kt`（**新**）、`ui/account/AccountConditions.kt`（**新**）、`ui/transaction/TransactionFormFragment.kt`（改：贷方取 `creditAccountUid`、两行同源、保存拒同号）、`ui/search/BulkActionRunner.kt`（**新**）、`ui/search/SearchResultsViewModel.kt` / `SearchResultsFragment.kt`（改：异步 + 回调，先关 CAB）、`ui/template/TransactionTemplateEditFragment.kt` / `res/layout/dialog_transaction_template.xml`（改：模板账户模糊搜索） |
| **①g 批量写库单事务 + 扩展视图单选化 + 一级菜单**（第二十五轮，**已完成**） | `ui/search/SearchBulkWriter.kt`（改；`delete` / `update` **整批一个事务**，逐行 `try/catch` 保留）、`ui/search/BulkEditTransactionsDialogFragment.kt`（改；`EXTRA_UIDS` + `uidsOf`）、`ui/search/SearchResultsFragment.kt`（改；`resultUids ?: selectedUids()`、`bulk_edit_running` 提示）、`res/menu/transactions_list_actions.xml`（改；删嵌套 `<menu>`，改一级 `menu_extended_view_settings`）、`res/menu/extended_view_item_actions.xml`（改；删「设为默认」）、`ui/transaction/view/ExtendedViewStore.kt`（改；`select(id?)` / `updateFields(id, fields)`）、`ui/transaction/view/ExtendedViewsFragment.kt`（改；**单选列表** + 字段勾选面板 + `syncingFields` 防回环）、`ui/transaction/view/ExtendedViewEditFragment.kt`（改；仅改名）、`ui/transaction/view/ExtendedRowContent.kt`（改；`hasExtraLines`）、`ui/transaction/TransactionsListFragment.kt`（改；`select(null)` / `saveCompactView` / `content?.hasExtraLines == true`）、`res/layout/{fragment_extended_views,item_extended_view,dialog_extended_view_edit}.xml`（改；RadioButton + CheckBox 面板） |
| **①g 日期「手工填写」**（第二十五轮，**已完成**；单选 + 范围两个控件入口都接） | `util/ManualDate.kt`（**新**，纯 JVM：`format` / `digitsOf` / `parse`（严格 `yyyyMMdd`）/ `startOfDay`）、`util/ManualDateRange.kt`（**新**，纯 JVM：`parse` 负责「结束不早于起始」）、`ui/util/dialog/ManualDateDialogFragment.kt`（**新**）、`ui/util/dialog/ManualDateRangeDialogFragment.kt`（**新**）、`ui/util/dialog/DatePickerDialogFragment.kt`（改；`BUTTON_NEUTRAL` + `showManualEntry` + `applyManualDate`）、`ui/util/dialog/DateRangePickerDialogFragment.kt`（改；范围日历同款入口）、`res/layout/{dialog_manual_date,dialog_manual_date_range}.xml`（**新**） |
| **①g 搜索结果接报表**（第二十五轮，**已完成**） | `ui/report/search/SearchReport.kt`（**新**；`SearchReportRow` / `SearchReport : ChartData<DataSet<Entry>>`）、`ui/report/search/SearchReportLoader.kt`（**新**；**复用 `fetchSearch(where)`**、金额 = 借方分录合计、合计按币种）、`ui/report/search/SearchResultReportFragment.kt`（**新**；`BaseReportFragment<SearchReport>`）、`ui/report/ReportType.kt`（改；`SEARCH_RESULT` **置于 `NONE` 之前**、`getReportNames(includeSearchResult)`）、`ui/report/ReportsActivity.kt`（改；`searchWhere` / `EXTRA_SEARCH_WHERE` / `showSearchResult` / `onNewIntent`）、`ui/search/SearchResultsFragment.kt`（改；溢出菜单入口）、`res/menu/search_results_menu.xml`（改）、`res/layout/{fragment_search_report,row_search_report}.xml`（**新**） |
| **①g 单测**（第二十五轮） | `app/src/test/java/.../util/{ManualDateTest,ManualDateRangeTest}.kt`（**新**，纯 JVM）、`.../ui/util/ManualDateWiringTest.kt`（**新**，源码守卫）、`.../ui/report/{ReportTypeTest,SearchReportLoaderTest,SearchReportWiringTest}.kt`（**新**；后者为源码守卫）、`.../ui/report/SearchReportLoaderTest.kt`（真库）、`.../ui/search/BulkEditResultTest.kt`（**新**）、`.../ui/search/SearchBulkWiringTest.kt` / `.../ui/transaction/view/{ExtendedViewStoreTest,ExtendedRowContentTest,ExtendedViewWiringTest}.kt`（补例） |
| **①h 删除搜索结果「图表」**（第二十六轮，**已完成**） | `ui/search/SearchChartDialogFragment.kt`（**删**）、`res/menu/search_results_menu.xml`（改；删 `menu_search_chart`）、`ui/search/SearchResultsFragment.kt`（改；删分支）、`res/values/{strings,values-zh-rCN/strings}.xml`（改；删 `search_chart_title` / `search_chart_by_account` / `search_chart_empty`） |
| **①h 批量写库分块提交（ANR 真根因）**（第二十六轮，**已完成**） | `ui/search/SearchBulkWriter.kt`（改；`uids.chunked(BATCH_SIZE = 50)` **每块一个事务** + `breathe()` 2 ms 让出写锁，逐行 `try/catch` 保留）、`ui/search/SearchResultsViewModel.kt`（改；`toSearchResultRow()` 移出 `Dispatchers.Main`） |
| **①h 报表图型展示**（第二十六轮，**已完成**） | `ui/report/search/SearchReportChart.kt`（**新**，纯 JVM；`SearchChartBucket` / `byAccount` / `byDate`）、`ui/report/search/SearchReport.kt`（改；`chartCurrencyCode` / `byAccount` / `byDate` / `otherCurrencyCodes` / `hasChart`）、`ui/report/search/SearchReportLoader.kt`（改；`charted()` 单币种 + `primaryCurrency()`）、`ui/report/search/SearchResultReportFragment.kt`（改；图型 `Spinner` + Pie/Bar/Line + 局部 `PALETTE`）、`res/layout/fragment_search_report.xml`（改；图型下拉 + 图表面板 + 表格滚动区）、`res/values/{strings,values-zh-rCN/strings}.xml`（改；`label_search_report_chart_type` + `search_report_chart_types` + `search_report_chart_by_account` / `_by_date` / `_empty` / `_note`） |
| **①h 单测**（第二十六轮） | `app/src/test/java/.../ui/report/SearchReportChartTest.kt`（**新**，纯 JVM 5 例）、`.../ui/report/SearchReportLoaderTest.kt`（补 4 例）、`.../ui/report/SearchReportWiringTest.kt`（补 3 例；源码守卫）、`.../ui/search/SearchBulkWiringTest.kt`（改；分块事务 + 主线程 DB 读守卫）、`.../test/unit/db/SearchBulkWriterTest.kt`（改；跨块边界写入） |
| **②-v3 冲突解决**（答案持久化 + 下轮强制，**第二十七轮已完成**；纯词汇 + 写路径单点 + 三处读答案） | `sync/ConflictResolution.kt`（**新**；`ResolveSide`（**边**，非动词）+ `StoredResolution`）、`.../sync/SyncPlan.kt`（改；`resolutionsApplied` / `resolutionsDropped` + `PlanInput.resolutions` 默认空）、`.../sync/SyncRoundPlanner.kt`（改；`forcedBy`（本机有⇒`PUSH`／本机已删⇒`DELETE_REMOTE`；对端有⇒`PULL`／对端已删⇒`DELETE_LOCAL`+墓碑；对端读不出⇒`null` 继续停驻）+ 过期判定（`examined ∩ decided`））、`.../sync/SyncRound.kt`（改；`getResolutions()` + `landed` + `commitResolutions`（`applied ∩ landed` 才清））、`java/.../ui/settings/ConflictResolutionStore.kt`（**新**；单点写路径，如实返回 `Boolean` + `Timber.e`）、`java/.../ui/settings/ConflictCenterText.kt`（改；`sidesFor`：`UNREADABLE` 只给 `LOCAL`；`of(side)`）、`java/.../ui/settings/ConflictCenterDialogFragment.kt`（改；两个按钮 ⇒ `remember` ⇒ Toast）、`res/values/strings.xml`（改；5 条） |
| **②-v3 持久化**（新表 `sync_resolutions`，schema **35 → 36**） | `db/DatabaseSchema.kt#SyncResolutionEntry`、`db/DatabaseHelper.kt`、`db/MigrationHelper.kt#migrateTo36`、`db/adapter/SyncStateDbAdapter.kt`（`getResolutions` / `getResolution` / `putResolution`→`Boolean` / `clearResolution` / `clearResolutions`（单事务）/ `resolutionCount`） |
| **②-v3 单测**（第二十七轮） | `app/src/test/java/.../test/unit/sync/ConflictResolutionTest.kt`（**新**，纯 JVM）、`.../test/unit/sync/SyncRoundPlannerTest.kt`（补 12 例）、`.../test/unit/sync/SyncRoundTest.kt`（补 5 例）、`.../test/unit/db/SyncStateDbAdapterTest.kt`（补 8 例，含迁移 35→36 + 唯一索引拒重 + 读不出的行被跳过）、`.../ui/settings/ConflictResolutionStoreTest.kt`（**新**）、`.../ui/settings/{ConflictCenterTextTest,ConflictCenterWiringTest}.kt`（补例） |
| **②-v3 咬合脚本** | `_r27_mut.py` / `_r27_mut.sh`（11 处变异，逐处逐字节还原）、`_r27_mut_results.txt` |
| 清单 | `app/src/main/AndroidManifest.xml` |
| 测试 | `app/src/test/`（114 个 `.kt`）、`app/src/androidTest/`（19 个 `.kt`） |
| 环境文档（**接手先读**） | `Z:\wdDaily\程序调试运行手册\Kotlin-AGP-Gradle9-Android-环境搭建技术文档.md`（本项目技术栈总环境文档，坑表 **K1–K27**） |
| Gradle 9 专项环境文档 | `Z:\wdDaily\程序调试运行手册\Gradle9-KotlinDSL-环境重建与验证.md` |
| 项目环境文档（含 Z 盘勘误） | `Z:\wdDaily\程序调试运行手册\mxSync-环境搭建技术文档.md` §3.3 / §9 |
| 模拟器脚本 | `Z:\wdDaily\程序调试运行手册\Start-AndroidEmulator.ps1` |

### 14.2 已锁定决策汇总

| # | 决策 |
|---|---|
| 1 | 同步语义 = **多设备双向合并**；含**冲突列表对比差异 + 批量选择生效项** |
| 2 | 加密 = **设置并保存口令**；备份/导出/导入**均可加密或明文** |
| 3 | OCR = **离线模型** → 选定 PaddleOCR PP-OCRv5_mobile + ONNX Runtime |
| 4 | ASR = **离线模型** → 选定 sherpa-onnx + SenseVoice-Small int8 |
| 5 | 标签 = **回写 `#tag` 到备注以兼容桌面**，移动端**独立显示** |
| 6 | 发行 = **面向中国区**（无 GMS 为前提） |
| 7 | 插件深度 = **L3 脚本**（+ L0 内部 SPI） |
| 8 | **引入 Hilt** |
| 9 | 先做 **"无 Firebase 也能构建"** 开关 |
| D4 | 同步粒度 = **记录级 GUID 三路合并** |
| D5 | 模型分发 = **分级**（OCR 内置 / ASR 按需下载） |
| D6 | 搜索模板存储 = **独立表 `search_templates` + `form_json`**（条件形态收敛进 JSON，表结构稳定） |
| D7 | 搜索布尔 = **字段内 / 字段间两层独立**；`None` 语义补齐为 `NOT (...)`；多关键词默认 AND、`\|` 表 OR |
| D8 | 交易模板的「空值金额」= **JSON 里省略 `amount` 键**（`null` **绝不**退化成 `0`，也绝不写成空串）；套用模板时留空由用户当次填写 |
| D9 | 搜索结果的「金额」口径 = **该交易借方分录合计（正数量级）**；排序 / 合计 / 分类统计 / 图表**共用这一个定义**（配平交易的带符号分录和为 0、行内展示余额又依赖账户正常余额方向 ⇒ 两者都不能当聚合口径） |
| D10 | 分类统计：**按账户**是严格分区（各桶之和 = 总额 ⇒ 可画饼图）；**按标签**允许重叠（一交易多标签会计入每个桶 ⇒ 只做列表、不做图，且 UI 明示"可能重叠"） |

### 14.3 开发缩写表（本团队惯例）

| 缩写 | 含义 |
|---|---|
| **KF** | 继续开发 |
| **CS** | 编写测试自行验证（全绿才算交付） |
| **BY** | 编译 Android + Windows 双端 |
| **BYA** / **BYW** | 只编 Android 端 / 只编 Windows 端 |
| **TX** | 推送提醒 |

可连写组合，例如 `KFCSBYTX` = 继续开发 + 写测试自验 + 双端出包 + 推送提醒。

---

<sub>本文档所有"实测"结论均来自 2026-10-11 在本机的真跑；未跑项集中在 §13.2，避免把推测写成结论。</sub>
