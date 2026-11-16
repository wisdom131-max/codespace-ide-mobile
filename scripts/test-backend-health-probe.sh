#!/usr/bin/env bash
# Regression coverage for backend-health-probe.sh, runnable anywhere with bash + python3.
#
# The two failures that actually happened in CI are encoded here:
#  - a slow first response (cold start) must be survived by retrying, not reported as an outage;
#  - a non-2xx response must FAIL. The old one-liner's `-o /dev/null` without `--fail` reported a 503
#    as a successful warmth check, which is a lying probe.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
probe="$here/backend-health-probe.sh"
fails=0

pass() { echo "ok   - $1"; }
fail() { echo "FAIL - $1"; fails=$((fails+1)); }

# A throwaway server whose first `warmup` requests return 503, then 200.
fake() { # $1=port $2=warmup-failures $3=mode
  python3 - "$@" <<'PY'
import sys, http.server, socketserver
port, warm, mode = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3]
state = {"n": 0}
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        state["n"] += 1
        if mode == "slow" and state["n"] == 1:
            import time; time.sleep(3)
        code = 503 if state["n"] <= warm else 200
        self.send_response(code); self.send_header("Content-Length", "0"); self.end_headers()
    def log_message(self, *a): pass
socketserver.TCPServer.allow_reuse_address = True
with socketserver.TCPServer(("127.0.0.1", port), H) as s:
    s.serve_forever()
PY
}

run_case() { # $1 name, $2 port, $3 warm, $4 mode, $5 expected-exit, $6 attempts
  fake "$2" "$3" "$4" & srv=$!
  sleep 1
  PROBE_URL="http://127.0.0.1:$2/health" PROBE_ATTEMPTS="$6" PROBE_TIMEOUT=10 PROBE_SLEEP=0 \
    bash "$probe" >/tmp/probe.$2.log 2>&1
  rc=$?
  kill "$srv" 2>/dev/null; wait "$srv" 2>/dev/null
  if [ "$rc" -eq "$5" ]; then pass "$1 (exit $rc)"; else fail "$1 (exit $rc, expected $5)"; sed 's/^/       /' /tmp/probe.$2.log; fi
}

run_case "a healthy backend passes first time" 18081 0 ok 0 3
run_case "a cold start is survived by retrying" 18082 2 ok 0 3
run_case "a backend that never recovers still fails" 18083 99 ok 1 2
run_case "a 503 is never reported as healthy" 18084 99 ok 1 1
run_case "a slow first response inside the timeout passes" 18085 0 slow 0 2

# An unreachable host must fail, not silently pass.
PROBE_URL="http://127.0.0.1:18099/health" PROBE_ATTEMPTS=2 PROBE_TIMEOUT=3 PROBE_SLEEP=0 \
  bash "$probe" >/tmp/probe.dead.log 2>&1
[ $? -ne 0 ] && pass "an unreachable backend fails" || fail "an unreachable backend failed to fail"

[ "$fails" -eq 0 ] && { echo "all probe checks passed"; exit 0; }
echo "$fails probe check(s) failed"; exit 1
