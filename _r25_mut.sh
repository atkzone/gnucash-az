#!/usr/bin/env bash
# R25 mutation-bite driver. Runs one mutated source against one test class at a
# time and reports whether the assertions actually went red.
#
# Judging a bite (per the project's K-series):
#   - a compile error is INCONCLUSIVE, not a bite
#   - a bite is "Task :app:testDevelopmentDebugUnitTest FAILED" in the log
#   - "BUILD SUCCESSFUL" means the assertions never saw the mutation
set -u

PY="C:/Users/Arthurs/.workbuddy/binaries/python/versions/3.13.12/python.exe"
export JAVA_HOME="D:/jdk-21/jdk-21.0.12.1+1"
RESULTS="_r25_mut_results.txt"
: > "$RESULTS"

case_filter () {
  case "$1" in
    m01*) echo "org.gnucash.android.ui.search.SearchBulkWiringTest" ;;
    m02*|m03*) echo "org.gnucash.android.ui.transaction.view.ExtendedViewStoreTest" ;;
    m04*) echo "org.gnucash.android.ui.transaction.view.ExtendedRowContentTest" ;;
    m05*) echo "org.gnucash.android.util.ManualDateTest" ;;
    m06*) echo "org.gnucash.android.ui.report.SearchReportWiringTest" ;;
    m07*) echo "org.gnucash.android.ui.report.ReportTypeTest" ;;
    m08*) echo "org.gnucash.android.ui.search.BulkEditResultTest" ;;
    m09*) echo "org.gnucash.android.ui.util.ManualDateWiringTest" ;;
    m10*|m11*) echo "org.gnucash.android.ui.report.SearchReportLoaderTest" ;;
    m12*) echo "org.gnucash.android.ui.transaction.view.ExtendedViewWiringTest" ;;
    m13*) echo "org.gnucash.android.util.ManualDateRangeTest" ;;
    m14*) echo "org.gnucash.android.ui.util.ManualDateWiringTest" ;;
    *) echo "" ;;
  esac
}

run_one () {
  local id="$1"
  local filter; filter="$(case_filter "$id")"
  local log="_mut_${id}.log"
  if ! "$PY" _r25_mut.py apply "$id" >> "$RESULTS" 2>&1; then
    echo "$id: APPLY-FAILED" >> "$RESULTS"; return
  fi
  ./gradlew :app:testDevelopmentDebugUnitTest --rerun-tasks --no-build-cache \
      --tests "$filter" > "$log" 2>&1
  local rc=$?
  "$PY" _r25_mut.py restore "$id" >/dev/null 2>&1
  local verdict
  if grep -qE "^e: " "$log"; then
    verdict="COMPILE-ERROR(inconclusive)"
  elif grep -q "Task :app:testDevelopmentDebugUnitTest FAILED" "$log"; then
    verdict="RED(bitten)"
  elif grep -q "BUILD SUCCESSFUL" "$log"; then
    verdict="GREEN(NOT-bitten)"
  else
    verdict="UNKNOWN(rc=$rc)"
  fi
  local red
  red="$(grep -m1 -E "FAILED$" "$log" | sed 's/^ *//')"
  printf '%-34s %-26s %s\n' "$id" "$verdict" "$red" >> "$RESULTS"
  printf '%-34s %-26s %s\n' "$id" "$verdict" "$red"
}

IDS="$( "$PY" _r25_mut.py list | tr -d '\r' )"
for id in $IDS; do
  run_one "$id"
done

echo "==== restore verification ====" | tee -a "$RESULTS"
for id in $IDS; do
  "$PY" _r25_mut.py verify "$id" | tee -a "$RESULTS"
done
