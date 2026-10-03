#!/bin/sh
# F6-b (2026-10-02): on-device (in-container) jdap eval engine test.
# Run INSIDE the proot container after installing openjdk-21-jdk-headless:
#   jdap-eval-test          (or)  sh /opt/jdap/jdap-eval-test.sh
# Compiles the sample debuggee with -g (evaluation needs local-variable info),
# then drives the full DAP exchange: line bp, conditional bp on both engine
# paths (javac + fast JDI), negative condition, and paused-session evaluation.
T=/tmp/jdap-test
JAVA_HOME_BIN=""
for j in /usr/lib/jvm/*/bin/java; do [ -x "$j" ] && JAVA_HOME_BIN="$(dirname "$(dirname "$j")")" && break; done
if [ -z "$JAVA_HOME_BIN" ]; then
    echo "jdap-eval-test: no JVM in container. Run: apt update && apt install -y openjdk-21-jdk-headless" >&2
    exit 127
fi
command -v python3 >/dev/null 2>&1 || { echo "jdap-eval-test: python3 required (apt install -y python3)" >&2; exit 127; }
mkdir -p "$T"
cp /opt/jdap/Hello.java "$T/" 2>/dev/null || true
"$JAVA_HOME_BIN/bin/javac" -g -d "$T" "$T/Hello.java" || { echo "javac -g failed" >&2; exit 1; }
JARS="$(ls /opt/jdap/jars/*.jar | tr '\n' ':')"
JDAP_JAVA_HOME="$JAVA_HOME_BIN" JDAP_BASE="$T" \
JDAP_DRIVER_CP="/opt/jdap/DapDriver.jar" JDAP_JARS_CP="$JARS" \
JDAP_EVALHOST="/opt/jdap/jdap-evalhost.jar" JDAP_PORT=5010 \
python3 /opt/jdap/jdap_eval_test.py
