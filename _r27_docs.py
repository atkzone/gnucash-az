#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R27 planning-doc backfill (feature ② v3 — conflict resolution).

Edits docs/gnucash-az-开发规划书.md in place:
  E1  header version  v1.24 -> v1.27
  E2  append the v1.27 entry to the revision line
  E3  §6.1 schema table: add 33->34 / 34->35 / 35->36 rows
  E4  §7.10 known-limitation ① marked as lifted
  E5  new §7.11 "冲突解决动作（②-v3）"
  E6  §11 P3 roadmap row mentions ②-v3
  E7  §14.1 index rows for the new files + K27 + test-file count

Usage: python _r27_docs.py <classes> <tests>
"""
import io
import sys

DOC = "docs/gnucash-az-开发规划书.md"


def load():
    with io.open(DOC, "r", encoding="utf-8", newline="") as fh:
        return fh.read()


def save(text):
    with io.open(DOC, "w", encoding="utf-8", newline="") as fh:
        fh.write(text)


def sub_once(text, find, repl, tag):
    n = text.count(find)
    if n != 1:
        raise SystemExit("ANCHOR %s matched %d times" % (tag, n))
    return text.replace(find, repl, 1)


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    classes, tests = sys.argv[1], sys.argv[2]
    t = load()
    eol = "\r\n" if "\r\n" in t else "\n"
    # normalise to LF for anchors, restore at the end
    t = t.replace("\r\n", "\n")

    # ---- E1 header -------------------------------------------------------
    t = sub_once(
        t,
        "> 文档状态：**v1.24** —— v1.0 纳入",
        "> 文档状态：**v1.27** —— v1.0 纳入",
        "E1",
    )

    # ---- E2 append the v1.27 revision entry ------------------------------
    entry = (
        "；**v1.27 回填 ②-v3「冲突解决动作」**（第二十七轮）：把冲突中心从「只读镜子」升级为**能拍板** —— "
        "用户点「保留本机／采用云端」后，答案经**单点写路径** `ConflictResolutionStore.remember` 落进新表 "
        "`sync_resolutions`（schema **35→36**，唯一索引 `(record_type, record_key)` **REPLACE 语义**、"
        "`putResolution` 返回 `Boolean` 把「没写进去」如实报回），下一轮 `SyncRoundPlanner` **只在该记录本会被扣下时**"
        "读它并强制一个动作（`ResolveSide` 存的是**边**不是动词：本机有 ⇒ `PUSH`（对端无副本 ⇒ `If-None-Match: *`）、"
        "本机已删 ⇒ `DELETE_REMOTE`；采用云端：对端有 ⇒ `PULL`、对端已确认删 ⇒ `DELETE_LOCAL` + 墓碑；"
        "对端**存在但读不出** ⇒ 不兑现、继续停驻）；**消费时机**：`resolutionsApplied ∩ landed` 才清除"
        "（`412`／传输失败／被 store 拒 ⇒ 保留答案下轮重试），「该记录已不再停驻且本轮真的出了动作」⇒ "
        "`resolutionsDropped` 清除，**本轮没看过的记录类型的答案原样保留**（否合同步某类记录会静默丢另一类的答案）；"
        "**11 处变异逐处咬合 11 条红**（m01 保留本机被抹成不动 · m02 强制推丢 `MustNotExist` · "
        "m03 采用云端面对对端删除时原地停驻 · m04 过期答案永不清 · m05 被观察记录的答案被当过期丢弃 · "
        "m06 不看落地就消费 · m07 永不消费 · m08 读不出的答案被猜成本机 · m09 清空路径谎报失败 · "
        "m10 读不出的行也给「采用云端」· m11 写失败吞成成功），逐处**逐字节还原**（11 文件 sha256 全部 `byte-identical`）；"
        "全量单测 **" + classes + " 类 / " + tests + " 例 / 0 失败 0 错误 0 跳过**（见 E13）；**本轮不含 BY**（KFCSTX）"
    )
    idx = t.find("\n")
    line5 = t[:idx]
    t = line5 + entry + t[idx:]

    # ---- E3 §6.1 schema rows ---------------------------------------------
    anchor = (
        "| 附 | 检索索引：`transactions(description/memo/note/number)`、`splits(memo)` |"
    )
    rows = (
        "| 33 → 34 | `sync_state` 补 `base_etag`、`pending_since`（②-v2 编排层） | **✅ 已实现**：纯加法；"
        "`base_etag` 存对端 etag（未变则不下载、省一次 GET）、`pending_since` 存「首见缺席」水位（二轮才晋升墓碑）；"
        "建表/索引 `IF NOT EXISTS` ⇒ 迁移可重复执行 |\n"
        "| 34 → 35 | 新增 `transaction_templates`（①d 记账模板） | **✅ 已实现**：纯加法；`payload_json` 存模板载荷"
        "（**空值 = 省略 key**）；建表/索引 `IF NOT EXISTS` ⇒ 迁移可重复执行 |\n"
        "| 35 → 36 | 新增 `sync_resolutions`（②-v3 冲突解决覆盖） | **✅ 已实现**（第二十七轮）：纯加法；"
        "唯一索引 `(record_type, record_key)` 保证「同一记录只有一条答案」（`REPLACE` 语义，后答覆盖先答）；"
        "`side` 存 `local`/`remote` 字面量；**读不出的 type/side 一律跳过而非猜默认**；"
        "建表 `IF NOT EXISTS` ⇒ 迁移可重复执行 |\n"
    )
    t = sub_once(t, anchor, rows + anchor, "E3")

    # ---- E4 §7.10 limitation ① lifted ------------------------------------
    t = sub_once(
        t,
        "① **只读** —— 没有「保留本地 / 采用远端」的选择：解决一条冲突要求",
        "① ~~**只读**~~ —— **已在 v1.27（②-v3）补齐**「保留本机 / 采用云端」，见 §7.11；"
        "原先不做是因为解决一条冲突要求",
        "E4",
    )

    # ---- E5 new §7.11 ----------------------------------------------------
    sec711 = """
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
"""
    t = sub_once(
        t,
        "\n---\n\n## 8. 加密与口令方案（② 的配套）",
        sec711 + "\n## 8. 加密与口令方案（② 的配套）",
        "E5",
    )

    # ---- E6 §11 P3 row ---------------------------------------------------
    t = sub_once(
        t,
        "（设置页 UI 与 Android 侧凭据/transport 装配未做） |",
        "（设置页 UI 与 Android 侧凭据/transport 装配未做）；"
        "**②-v3 冲突解决动作（保留本机／采用云端：答案持久化 + 下轮强制兑现）已落地**（第二十七轮） |",
        "E6",
    )

    # ---- E7 §14.1 index --------------------------------------------------
    anchor = "| 清单 | `app/src/main/AndroidManifest.xml` |"
    rows = (
        "| **②-v3 冲突解决**（答案持久化 + 下轮强制，**第二十七轮已完成**；纯词汇 + 写路径单点 + 三处读答案） | "
        "`sync/ConflictResolution.kt`（**新**；`ResolveSide`（**边**，非动词）+ `StoredResolution`）、"
        "`.../sync/SyncPlan.kt`（改；`resolutionsApplied` / `resolutionsDropped` + `PlanInput.resolutions` 默认空）、"
        "`.../sync/SyncRoundPlanner.kt`（改；`forcedBy`（本机有⇒`PUSH`／本机已删⇒`DELETE_REMOTE`；"
        "对端有⇒`PULL`／对端已删⇒`DELETE_LOCAL`+墓碑；对端读不出⇒`null` 继续停驻）+ 过期判定（`examined ∩ decided`））、"
        "`.../sync/SyncRound.kt`（改；`getResolutions()` + `landed` + `commitResolutions`（`applied ∩ landed` 才清））、"
        "`java/.../ui/settings/ConflictResolutionStore.kt`（**新**；单点写路径，如实返回 `Boolean` + `Timber.e`）、"
        "`java/.../ui/settings/ConflictCenterText.kt`（改；`sidesFor`：`UNREADABLE` 只给 `LOCAL`；`of(side)`）、"
        "`java/.../ui/settings/ConflictCenterDialogFragment.kt`（改；两个按钮 ⇒ `remember` ⇒ Toast）、"
        "`res/values/strings.xml`（改；5 条） |\n"
        "| **②-v3 持久化**（新表 `sync_resolutions`，schema **35 → 36**） | `db/DatabaseSchema.kt#SyncResolutionEntry`、"
        "`db/DatabaseHelper.kt`、`db/MigrationHelper.kt#migrateTo36`、`db/adapter/SyncStateDbAdapter.kt`"
        "（`getResolutions` / `getResolution` / `putResolution`→`Boolean` / `clearResolution` / "
        "`clearResolutions`（单事务）/ `resolutionCount`） |\n"
        "| **②-v3 单测**（第二十七轮） | `app/src/test/java/.../test/unit/sync/ConflictResolutionTest.kt`（**新**，纯 JVM）、"
        "`.../test/unit/sync/SyncRoundPlannerTest.kt`（补 12 例）、`.../test/unit/sync/SyncRoundTest.kt`（补 5 例）、"
        "`.../test/unit/db/SyncStateDbAdapterTest.kt`（补 8 例，含迁移 35→36 + 唯一索引拒重 + 读不出的行被跳过）、"
        "`.../ui/settings/ConflictResolutionStoreTest.kt`（**新**）、"
        "`.../ui/settings/{ConflictCenterTextTest,ConflictCenterWiringTest}.kt`（补例） |\n"
        "| **②-v3 咬合脚本** | `_r27_mut.py` / `_r27_mut.sh`（11 处变异，逐处逐字节还原）、`_r27_mut_results.txt` |\n"
    )
    t = sub_once(t, anchor, rows + anchor, "E7-rows")

    t = sub_once(
        t,
        "| 测试 | `app/src/test/`（112 个 `.kt`）、`app/src/androidTest/`（19 个 `.kt`） |",
        "| 测试 | `app/src/test/`（114 个 `.kt`）、`app/src/androidTest/`（19 个 `.kt`） |",
        "E7-count",
    )
    t = sub_once(t, "坑表 **K1–K26**）", "坑表 **K1–K27**）", "E7-k27")

    # ---- E8 E13 baseline row --------------------------------------------
    t = sub_once(
        t,
        "JDK 21 + 沙箱外：**112 个测试类 / 1721 个用例 / 0 失败 0 错误 0 跳过**（首轮 505",
        "JDK 21 + 沙箱外：**" + classes + " 个测试类 / " + tests + " 个用例 / 0 失败 0 错误 0 跳过**"
        "（**第二十七轮** R27「②-v3 冲突解决动作（保留本机／采用云端）」—— 新表 `sync_resolutions`"
        "（schema **35→36**、唯一索引 `(record_type, record_key)` REPLACE 语义）+ 纯词汇 "
        "`sync/ConflictResolution.kt`（`ResolveSide` = **边**）+ `SyncRoundPlanner.forcedBy` + "
        "`SyncRound.commitResolutions`（`resolutionsApplied ∩ landed` 才清）+ 写路径单点 "
        "`ConflictResolutionStore.remember`（返回 `Boolean`）+ 冲突中心两个按钮；⚠️ 沙箱下由**分批前台 "
        "`--tests` 拼装**并逐批归档 XML（sync 23 类 / 498 例、db 22 类、ui.settings 9 类 + 其余包，"
        "归档 `_r27_full_xml/`）；**" + classes + " 个测试类 / " + tests + " 个用例 / 0 失败 0 错误 0 跳过**；"
        "11 处变异逐处咬合 11 条红、逐处字节还原；见环境文档 **K27**）（首轮 505",
        "E8",
    )

    save(t.replace("\n", eol))
    print("DOC PATCHED: v1.27 (classes=%s tests=%s)" % (classes, tests))


if __name__ == "__main__":
    main()
