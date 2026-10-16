#!/usr/bin/env bash
# R26: 前台跑一批单测（沙箱放行只在能走 escalation 的前台命令里稳），并把这一批的
# JUnit XML 归档到 _r26_xml/ —— `--tests` 过滤会**清掉**上一批的 XML，不归档就丢数据。
#
# 用法:  bash _r26_batch.sh _r26_batch_03.txt
#
# 判据（每个批都要满足，缺一即视为"这一批没真跑"）：
#   - 日志里出现 "> Task :app:testDevelopmentDebugUnitTest"
#   - 该批 12 个类里，除"无 @Test 的类"外，都产出了 TEST-*.xml
set -u

PY="C:/Users/Arthurs/.workbuddy/binaries/python/versions/3.13.12/python.exe"
export JAVA_HOME="D:/jdk-21/jdk-21.0.12.1+1"

RES="app/build/test-results/testDevelopmentDebugUnitTest"
mkdir -p _r26_xml

run_batch () {
LIST="$1"
ARGS=()
while IFS= read -r c; do
  [ -n "$c" ] && ARGS+=(--tests "$c")
done < "$LIST"

rm -f "$RES"/TEST-*.xml
START=$(date +%s)
./gradlew :app:testDevelopmentDebugUnitTest --no-build-cache "${ARGS[@]}" > "_r26_$(basename "$LIST" .txt).log" 2>&1
RC=$?
END=$(date +%s)

cp -f "$RES"/TEST-*.xml _r26_xml/ 2>/dev/null
GOT=$(ls "$RES"/TEST-*.xml 2>/dev/null | wc -l)
WANT=$(grep -c . "$LIST")
echo "batch=$(basename "$LIST") rc=$RC secs=$((END-START)) xml=$GOT/$WANT"
if grep -q "> Task :app:testDevelopmentDebugUnitTest" "_r26_$(basename "$LIST" .txt).log"; then
  echo "  task ran: yes"
else
  echo "  task ran: NO  <-- 这一批没真跑，需 --rerun-tasks 重跑"
fi
grep -E "BUILD (SUCCESSFUL|FAILED)" "_r26_$(basename "$LIST" .txt).log" | tail -1
if grep -q "拒绝访问\|命令被沙箱拦截" "_r26_$(basename "$LIST" .txt).log"; then
  echo "  SANDBOX DENIAL in this batch"
fi
}

for f in "$@"; do
  run_batch "$f"
done
