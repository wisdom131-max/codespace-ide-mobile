#!/usr/bin/env bash
# Backend health probe — the warmth check half of the keep-alive workflow.
#
# WHY THIS FILE EXISTS (2026-11-16): the workflow used to run one bare `curl --max-time 60`.
# Render's free tier spins the service down, and a cold boot takes longer than 60s, so the scheduled
# run died with `curl: (28) Operation timed out` while the backend itself was fine. A probe that
# cannot tell "asleep" from "broken" is not a probe.
#
# Three rules, each of which the old one-liner broke:
#  1. A cold start gets a real chance: up to PROBE_ATTEMPTS attempts, each with PROBE_TIMEOUT seconds.
#  2. A non-2xx response is a FAILURE (`--fail`). The old command wrote `-o /dev/null` without
#     `--fail`, so a 503 logged itself as a successful warmth check.
#  3. Retrying never turns a broken backend into a green run: if every attempt fails, the exit code is
#     non-zero and the last HTTP code is printed.
set -uo pipefail

url="${PROBE_URL:-https://codespace-ide-backend.onrender.com/api/v1/health}"
attempts="${PROBE_ATTEMPTS:-3}"
timeout_s="${PROBE_TIMEOUT:-120}"
sleep_s="${PROBE_SLEEP:-5}"

if ! [[ "$attempts" =~ ^[1-9][0-9]*$ ]]; then echo "invalid PROBE_ATTEMPTS: $attempts" >&2; exit 2; fi
if ! [[ "$timeout_s" =~ ^[1-9][0-9]*$ ]]; then echo "invalid PROBE_TIMEOUT: $timeout_s" >&2; exit 2; fi

code="000"
for i in $(seq 1 "$attempts"); do
  echo "[$(date -u)] probe $i/$attempts: $url (timeout ${timeout_s}s)"
  out=$(curl -sS --fail --max-time "$timeout_s" -o /dev/null -w "%{http_code} %{time_total}" "$url" 2>&1)
  rc=$?
  code="${out%% *}"
  [[ "$code" =~ ^[0-9]{3}$ ]] || code="000"
  if [ "$rc" -eq 0 ] && [ "${code:0:1}" = "2" ]; then
    echo "[$(date -u)] healthy: HTTP $code in ${out#* }s"
    exit 0
  fi
  echo "[$(date -u)] attempt $i failed (curl rc=$rc, HTTP $code): $out"
  if [ "$i" -lt "$attempts" ]; then sleep "$sleep_s"; fi
done

echo "[$(date -u)] UNHEALTHY after $attempts attempt(s): last HTTP $code" >&2
exit 1
