#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R27 environment-doc backfill.

Edits  Z:\\wdDaily\\程序调试运行手册\\Kotlin-AGP-Gradle9-Android-环境搭建技术文档.md
  - bumps the K-range note (K1-K26 -> K1-K27)
  - inserts the K27 section before "## 4. 复现命令速查（照抄）"
  - appends the 2026-10-16 round-log row to §6 变更记录

Usage: python _r27_envdoc.py <classes> <tests>
"""
import io
import sys

DOC = r"Z:\wdDaily\程序调试运行手册\Kotlin-AGP-Gradle9-Android-环境搭建技术文档.md"


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    classes, tests = sys.argv[1], sys.argv[2]
    with io.open(DOC, "r", encoding="utf-8", newline="") as fh:
        t = fh.read()
    eol = "\r\n" if "\r\n" in t else "\n"
    t = t.replace("\r\n", "\n")

    def sub_once(find, repl, tag):
        nonlocal t
        n = t.count(find)
        if n != 1:
            raise SystemExit("ANCHOR %s matched %d times" % (tag, n))
        t = t.replace(find, repl, 1)

    sub_once("编号 K1–K26，按", "编号 K1–K27，按", "K-range")

    k27 = """### K27 ★ 第二十七轮（R27）的三处坑：**「用户拍板」存的是边不是动词**、SQLite 写失败**不抛只返 `-1`**、前台 `--tests` 批次超时**自动转后台**本轮**未**丢沙箱放行

**坑 1 · 同步冲突的「保留本机／采用云端」不能存成动词（`PUSH`/`PULL`）—— 同一条答案在不同状态下对应不同动作**

现象：把答案存成 `PUSH`，则「保留本机」在**本机已删该记录**（我们删的、对端改了）时会**复活**一条用户特意删掉的记录；存成 `PULL`，则「采用云端」在**对端已确认删除**（对端的"版本"就是**它的缺席**）时会去拉一个不存在的载荷。

根因：用户选的是**哪一边赢**，而"赢"对应的动作由当时的**本地/远端状态**决定：本机有 ⇒ `PUSH`、本机已删 ⇒ `DELETE_REMOTE`；对端有 ⇒ `PULL`、对端已删 ⇒ `DELETE_LOCAL`。存动词 = 把一次决策的**结论**当成下一次的**命令**。

处置：`ResolveSide`（`local`/`remote`）只存**边**；兑现方向在规划器里按状态算（`forcedBy`）。⚠️ 连带两条：① 对端已删而选「采用云端」⇒ 兑现 `DELETE_LOCAL` 时**必须补墓碑**，否则下一轮「记录没了 + 基线还在」被读成本机删除 ⇒ **反向删掉对端**，两端一起丢；② 强制推的前置条件**不能**沿用普通 `pushPrecondition` —— 对端无副本时要发 `If-None-Match: *`（创建），用 `If-Match` 打一个对端**已经丢掉的** etag 会让这次写**每轮都失败**，用户永远兑现不了。

**坑 2 · SQLite 的写失败**不抛异常**，`insertWithOnConflict` 返回 `-1` —— 只看「没抛异常」等于按钮撒谎**

现象：冲突中心点了「保留本机」，对话框关掉、提示已记下，但**什么都没写**。

根因：`db.insertWithOnConflict(...)` 失败时返回 `-1`（**不抛**）。把「没抛异常」当成功，就得到「按钮说谎」。而异常若抛在 dialog 点击回调里，会被框架**吞掉**（对话框照样关）。

处置：适配层 `putResolution` 返回 `Boolean`（`row != -1L`）；写路径单点 `ConflictResolutionStore.remember` **如实返回** 并 `Timber.e`；界面据此提示成功/失败。⚠️ 单测要能**咬住**这条 —— 变异「`return row != -1L` → `return true`」**不会红**（用例只断言成功路径 `isTrue()`）；真正能咬的是「清空路径」那条（`putResolution(…, null)` 断言 `isTrue()`），把它变异成 `return false` 即红（本轮的 `m09`）。

**坑 3 · 前台 `--tests` 批次跑久了会**自动转后台**，而后台 Gradle 本轮**没有**丢沙箱放行**

现象：`./gradlew :app:testDevelopmentDebugUnitTest --tests "….*"` 前台起跑，越过超时窗口后**自动转后台**（"Auto-backgrounded after hitting foreground timeout… no SIGTERM was sent"），进程**继续跑完**（本轮 db 包 22 类 12m16s），`denials=0`、XML 正常落盘。

处置：R26 记的「后台跑 Gradle 会丢沙箱放行、2 分钟内撞 `.gradle` 文件锁」在本轮**未复现**（后台起的 db 包、ui.settings 包都跑完了）。⇒ 大包可直接后台起跑再轮询；**但仍以后台日志无 `拒绝访问` 为准**，一旦出现立即回前台重跑。⚠️ 纪律不变：**变异咬合运行期间绝不动源码**（K24 坑 2 / K25 坑 6），否则整轮结论作废且可能留下变异态。

"""
    sub_once(
        "## 4. 复现命令速查（照抄）",
        k27 + "## 4. 复现命令速查（照抄）",
        "K27",
    )

    row = (
        "| 2026-10-16 | **第二十七轮补充：基线 " + classes + " 类 / " + tests + " 例 / 0 失败 0 错误 0 跳过**"
        "（`--rerun-tasks --no-build-cache`；沙箱下由分批前台 `--tests` 拼装 + 逐批归档 XML："
        "sync 23 类 / 498 例、db 22 类、ui.settings 9 类 + 其余包）；对应「②-v3 冲突解决动作"
        "（保留本机／采用云端）」：新表 `sync_resolutions`（schema **35→36**，唯一索引 "
        "`(record_type, record_key)` REPLACE 语义）+ 纯词汇 `sync/ConflictResolution.kt`"
        "（`ResolveSide` = **边**）+ `SyncRoundPlanner.forcedBy`（本机有⇒`PUSH`／本机已删⇒`DELETE_REMOTE`；"
        "对端有⇒`PULL`／对端已删⇒`DELETE_LOCAL`+墓碑；对端读不出⇒`null` 继续停驻）+ "
        "`SyncRound.commitResolutions`（`resolutionsApplied ∩ landed` 才清）+ 写路径单点 "
        "`ConflictResolutionStore.remember`（返回 `Boolean`）+ 冲突中心两个按钮；**新增 K27** 三坑"
        "（**存边不存动词** · `insertWithOnConflict` 失败**不抛**只返 `-1` ⇒ 只看「没抛异常」等于按钮撒谎 · "
        "前台批次超时自动转后台本轮**未**丢沙箱放行）；**11 处变异逐处咬合 11 条红**，逐处字节还原"
        "（11 文件 `byte-identical`）；**本轮不含 BY**（KFCSTX ⇒ 不出包） |\n"
    )
    marker = "**本轮含 BY**：APK 44852969 B、sha256 `34b031b3e3eee4047932b689b37851fa0a79a4841e1d5f5c139b154cb7f24605` |\n"
    sub_once(marker, marker + row, "roundlog")

    with io.open(DOC, "w", encoding="utf-8", newline="") as fh:
        fh.write(t.replace("\n", eol))
    print("ENV DOC PATCHED: K27 + round-log (classes=%s tests=%s)" % (classes, tests))


if __name__ == "__main__":
    main()
