#!/usr/bin/env bash
# R27 全量单测：分批拼装驱动（每批 12 类，逐批归档 XML）。
# 背景：本轮"单次全量"跑到 1h32m 时 JVM 静默消失（无 java、无 XML、日志停在测试任务起跑）⇒
#       改用 R26 已验证的分批前台/短跑法；每批都归档，进程被杀也只丢这一批。
#
# 判据：每批日志含 "> Task :app:testDevelopmentDebugUnitTest"；汇总 114 个 *Test 类产 XML
#      （BookHelperTest 是无 @Test 的基类 ⇒ 不产 XML）。
set -u
export JAVA_HOME="D:/jdk-21/jdk-21.0.12.1+1"
PY="C:/Users/Arthurs/.workbuddy/binaries/python/versions/3.13.12/python.exe"

rm -rf _r27_full_xml && mkdir -p _r27_full_xml
START=$(date +%s)

bash _r27_batch.sh _r27_batch_*.txt

END=$(date +%s)
echo "==== total secs=$((END-START)) ===="
"$PY" - <<'EOF'
import glob, xml.etree.ElementTree as ET
t=f=e=s=0; n=0
for p in sorted(glob.glob('_r27_full_xml/TEST-*.xml')):
    r=ET.parse(p).getroot()
    T=int(r.get('tests')); F=int(r.get('failures')); E=int(r.get('errors')); S=int(r.get('skipped'))
    t+=T; f+=F; e+=E; s+=S; n+=1
    if F+E: print("PROBLEM", r.get('name'), "tests=%d fail=%d err=%d" % (T,F,E))
print("FULL TOTAL classes=%d tests=%d fail=%d err=%d skip=%d" % (n,t,f,e,s))
EOF
