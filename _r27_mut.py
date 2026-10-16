#!/usr/bin/env python3
"""R27 mutation-bite helper (feature ② v3 — conflict resolution).

Applies one named source mutation, restores it, or verifies the restore is
byte-identical. Line endings are handled adaptively (the repo mixes CRLF and LF),
which is the trap that makes a mutation silently not apply (K25 / _r26_mut.py).

Each case is a single unique `find` fragment replaced by `repl`, aimed at one
guarantee of the conflict-resolution feature:

  planner (SyncRoundPlanner)  — which side a stored answer forces, and when an
                                answer is spent vs. kept;
  round   (SyncRound)         — that an answer is only spent once its action landed;
  adapter (SyncStateDbAdapter)— that a stored answer is never guessed at;
  UI      (ConflictCenterText / ConflictResolutionStore) — which buttons are offered,
                                and whether a failed write is reported honestly.

Usage:
  python _r27_mut.py apply   <id>
  python _r27_mut.py restore <id>
  python _r27_mut.py verify  <id>
  python _r27_mut.py list
"""
import hashlib
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
BACKUP_DIR = os.path.join(ROOT, "_r27_mut_backup")

PLANNER = "app/src/main/kotlin/org/gnucash/android/sync/SyncRoundPlanner.kt"
ROUND = "app/src/main/kotlin/org/gnucash/android/sync/SyncRound.kt"
ADAPTER = "app/src/main/java/org/gnucash/android/db/adapter/SyncStateDbAdapter.kt"
CTEXT = "app/src/main/java/org/gnucash/android/ui/settings/ConflictCenterText.kt"
STORE = "app/src/main/java/org/gnucash/android/ui/settings/ConflictResolutionStore.kt"

# id -> (relative path, unique find fragment (LF), replacement fragment (LF))
CASES = {
    # --- 「保留本机」被抹成不动：用户拍了板，规划器却什么也不做（"拍板" 形同虚设）
    # ⚠️ 不能改成 `if (false)`：那样会破坏 `local` 的智能转换，`local.fields` 编译不过 ⇒
    #    只会得到 COMPILE-ERROR(inconclusive)，不是一条红。故改**动词**（锚点唯一）。
    "m01_keep_local_forces_nothing": (
        PLANNER,
        "                id, MergeDecision.KeepLocal, SyncVerb.PUSH,\n",
        "                id, MergeDecision.KeepLocal, SyncVerb.NONE,\n",
    ),
    # --- 强制推的前置条件丢掉「对端无副本 ⇒ 必须创建」
    "m02_forced_push_loses_must_not_exist": (
        PLANNER,
        "            !remotePresent -> PutPrecondition.MustNotExist\n",
        "            false -> PutPrecondition.MustNotExist\n",
    ),
    # --- 「采用云端」面对对端已确认的删除时不再删本地，而是原地停驻（永远解不开）
    "m03_take_remote_over_peer_deletion_stays_parked": (
        PLANNER,
        "            remoteAbsent -> SyncItem(id, MergeDecision.DeleteLocal, SyncVerb.DELETE_LOCAL)\n",
        "            false -> SyncItem(id, MergeDecision.DeleteLocal, SyncVerb.DELETE_LOCAL)\n",
    ),
    # --- 过期（不再停驻）的答案永不清除：老选择会在将来某轮凭空复活并强制一次写
    "m04_moot_answer_never_dropped": (
        PLANNER,
        "        val droppedResolutions = resolutions.keys\n"
        "            .filter { it in examined && it !in parked && it !in appliedResolutions && it in decided }\n"
        "            .toSet()\n",
        "        val droppedResolutions = emptySet<SyncIdentity>()\n",
    ),
    # --- 只是「被观察」的首次缺席也当成过期 ⇒ 用户的选择被丢掉，下一轮停驻时无答案可用
    "m05_watched_answer_dropped_as_moot": (
        PLANNER,
        "            .filter { it in examined && it !in parked && it !in appliedResolutions && it in decided }\n",
        "            .filter { it in examined && it !in parked && it !in appliedResolutions }\n",
    ),
    # --- 不看「是否真的落地」就消费答案 ⇒ 412 被拒的那次写把用户的选择吃了
    "m06_round_spends_answer_without_landing": (
        ROUND,
        "        val consumed = plan.resolutionsDropped + plan.resolutionsApplied.intersect(landed)\n",
        "        val consumed = plan.resolutionsDropped + plan.resolutionsApplied\n",
    ),
    # --- 本轮永远不消费答案 ⇒ 已生效的选择永不消失，重复强制同一写
    "m07_round_never_spends_answers": (
        ROUND,
        "        if (consumed.isEmpty()) return\n        state.clearResolutions(consumed)\n",
        "        if (consumed.isEmpty()) return\n",
    ),
    # --- 读不出的答案被猜成「保留本机」⇒ 静默强推用户从未选择的边
    "m08_unreadable_row_guessed_as_local": (
        ADAPTER,
        "                val side = ResolveSide.fromId(cursor.getString(INDEX_RESOLUTION_SIDE)) ?: continue\n",
        "                val side = ResolveSide.fromId(cursor.getString(INDEX_RESOLUTION_SIDE)) ?: ResolveSide.LOCAL\n",
    ),
    # --- 清空答案的那条路径谎报失败 ⇒ 界面会告诉用户「没记下」而其实记下了
    "m09_null_answer_reports_failure": (
        ADAPTER,
        "            clearResolution(recordType, recordKey)\n            return true\n",
        "            clearResolution(recordType, recordKey)\n            return false\n",
    ),
    # --- 「读不出」的行也给出「采用云端」按钮 ⇒ 按钮永远无法兑现
    "m10_unreadable_offers_adopt_remote": (
        CTEXT,
        "        ConflictKind.UNREADABLE -> listOf(ResolveSide.LOCAL)\n",
        "        ConflictKind.UNREADABLE -> ResolveSide.entries\n",
    ),
    # --- 写入失败被吞成成功 ⇒ 按钮说「已记下」，其实一个字都没写
    "m11_store_reports_failure_as_success": (
        STORE,
        "        write(SyncStateDbAdapter.instance, identity, side)\n",
        "        write(SyncStateDbAdapter.instance, identity, side)\n        true\n",
    ),
}


def _paths(mid):
    rel, find, repl = CASES[mid]
    src = os.path.join(ROOT, rel.replace("/", os.sep))
    flat = rel.replace("/", "__")
    bak = os.path.join(BACKUP_DIR, mid + "__" + flat)
    return src, find, repl, bak


def sha(path):
    with open(path, "rb") as fh:
        return hashlib.sha256(fh.read()).hexdigest()


def apply(mid):
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
    src, _find, _repl, bak = _paths(mid)
    if not os.path.isfile(bak):
        raise SystemExit("no backup for %s" % mid)
    shutil.copy2(bak, src)
    print("RESTORED %s" % mid)


def verify(mid):
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
        return
    mid = sys.argv[2]
    if mid not in CASES:
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
