#!/usr/bin/env bash
# R27 mutation-bite driver (feature ② v3 — conflict resolution). Applies one
# mutated source against one test class at a time and reports whether the
# assertions actually went red.
#
# Judging a bite (per the project's K-series):
#   - a bite is "Task :app:testDevelopmentDebugUnitTest FAILED" in the log
#   - "BUILD SUCCESSFUL" means the assertions never saw the mutation
#   - a *real* compile error is INCONCLUSIVE, not a bite
#   - ⚠️ the buildSrc Kotlin daemon prints "e: Daemon compilation failed" and then
#     RETRIES IN-PROCESS — so `e: ` alone does NOT mean the tree failed to compile.
#     Judge RED / GREEN **first**; only call it a compile error when the build died
#     before the test task ever ran.
#   - ⚠️ a background run can LOSE the sandbox grant (K18): the log then shows
#     '拒绝访问' / '命令被沙箱拦截'. That is NOT a verdict — rerun in the FOREGROUND.
#
# Usage:  bash _r27_mut.sh              # all cases
#         bash _r27_mut.sh m01_ m02_    # only cases whose id starts with a prefix
#
# ⚠️ Do NOT edit any source while this runs: the next case would compile against a
# different tree and every earlier verdict stops meaning anything (K25 坑 6)。
set -u

PY="C:/Users/Arthurs/.workbuddy/binaries/python/versions/3.13.12/python.exe"
export JAVA_HOME="D:/jdk-21/jdk-21.0.12.1+1"
RESULTS="_r27_mut_results.txt"

case_filter () {
  case "$1" in
    m01*|m02*|m03*|m04*|m05*) echo "org.gnucash.android.test.unit.sync.SyncRoundPlannerTest" ;;
    m06*|m07*) echo "org.gnucash.android.test.unit.sync.SyncRoundTest" ;;
    m08*|m09*) echo "org.gnucash.android.test.unit.db.SyncStateDbAdapterTest" ;;
    m10*) echo "org.gnucash.android.ui.settings.ConflictCenterTextTest" ;;
    m11*) echo "org.gnucash.android.ui.settings.ConflictResolutionStoreTest" ;;
    *) echo "" ;;
  esac
}

run_one () {
  local id="$1"
  local filter; filter="$(case_filter "$id")"
  local log="_r27mut_${id}.log"
  if ! "$PY" _r27_mut.py apply "$id" >> "$RESULTS" 2>&1; then
    echo "$id: APPLY-FAILED" >> "$RESULTS"; return
  fi
  ./gradlew :app:testDevelopmentDebugUnitTest --rerun-tasks --no-build-cache \
      --tests "$filter" > "$log" 2>&1
  local rc=$?
  "$PY" _r27_mut.py restore "$id" >/dev/null 2>&1
  local verdict
  if grep -q "命令被沙箱拦截\|拒绝访问\|Access is denied" "$log"; then
    verdict="SANDBOX-BLOCKED(inconclusive)"
  elif grep -q "Task :app:testDevelopmentDebugUnitTest FAILED" "$log"; then
    verdict="RED(bitten)"
  elif grep -q "BUILD SUCCESSFUL" "$log"; then
    verdict="GREEN(NOT-bitten)"
  elif grep -qE "^e: " "$log" && ! grep -q "> Task :app:testDevelopmentDebugUnitTest" "$log"; then
    verdict="COMPILE-ERROR(inconclusive)"
  else
    verdict="UNKNOWN(rc=$rc)"
  fi
  local red
  red="$(grep -m1 -E "FAILED$" "$log" | sed 's/^ *//')"
  printf '%-58s %-26s %s\n' "$id" "$verdict" "$red" >> "$RESULTS"
  printf '%-58s %-26s %s\n' "$id" "$verdict" "$red"
}

ALL="$( "$PY" _r27_mut.py list | tr -d '\r' )"
if [ "$#" -gt 0 ]; then
  IDS=""
  for prefix in "$@"; do
    for id in $ALL; do
      case "$id" in "$prefix"*) IDS="$IDS $id" ;; esac
    done
  done
  # de-duplicate, keep the canonical order
  IDS="$( for id in $ALL; do for want in $IDS; do [ "$id" = "$want" ] && echo "$id"; done; done )"
else
  IDS="$ALL"
fi

[ "$#" -gt 0 ] || : > "$RESULTS"
echo "==== cases: $IDS ===="
for id in $IDS; do
  run_one "$id"
done

echo "==== restore verification ====" | tee -a "$RESULTS"
for id in $ALL; do
  "$PY" _r27_mut.py verify "$id" | tee -a "$RESULTS"
done
