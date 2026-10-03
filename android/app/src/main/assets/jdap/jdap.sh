#!/bin/sh
# F6-a (2026-10-02): CodeSpace JVM DAP driver launcher (proot container side).
# Speaks Debug Adapter Protocol (DAP) over stdio. Requires a JVM in the
# container:  apt update && apt install -y openjdk-21-jdk-headless
JH=/opt/jdap
JAVA_BIN="$(command -v java 2>/dev/null || true)"
if [ -z "$JAVA_BIN" ]; then
    for j in /usr/lib/jvm/*/bin/java; do
        [ -x "$j" ] && JAVA_BIN="$j" && break
    done
fi
if [ -z "$JAVA_BIN" ]; then
    echo "jdap: no JVM in container. Run: apt update && apt install -y openjdk-21-jdk-headless" >&2
    exit 127
fi
# Serial GC + 256m cap: the driver JVM shares the container with the debuggee
# JVM (F6-d tracks both JVMs' actual memory on the 3GB device).
exec "$JAVA_BIN" -XX:+UseSerialGC -Xmx256m -cp "$JH/DapDriver.jar:$JH/jars/*" DapDriver "$@"
