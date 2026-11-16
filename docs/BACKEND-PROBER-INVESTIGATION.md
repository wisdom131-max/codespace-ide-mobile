# Backend prober — investigation (2026-11-16)

Separate workstream from Project Five/Six, by the owner's instruction, so a green project build cannot
hide it.

## What the "backend prober" actually is

`.github/workflows/backend-keepalive.yml` — job `keepalive`, two probes and (since today) one
self-check:

| Step | What it proves |
|---|---|
| `Ping Supabase Postgres with a real query` | `psql "$DATABASE_URL" -c 'SELECT 1'`. The REAL pause-prevention: an HTTP ping never reaches Postgres, so only a query resets Supabase's inactivity clock. |
| `Ping Render backend health` | warmth log against `https://codespace-ide-backend.onrender.com/api/v1/health` |
| `Probe behaviour regression check` | the probe's own behaviour, see below |

It is **not** any of: AI provider capability detection (`ProviderCapabilities`), provider health /
`ToolHealth`, cross-provider selection (`ProviderSelector`), or Project Five's error taxonomy. Those
inspect AI providers from inside the app. This is a scheduled CI job that touches two external services
nobody in the app controls. The two share no code.

## Last green, first red

| Run | When | Verdict |
|---|---|---|
| 43720854929 | 2026-11-15 13:47 UTC | success (last green) |
| **43896880333** | **2026-11-16 15:29 UTC** | **failure (first red)** |

Both on `main`/schedule, so no Stage 5.x commit is in the picture: the schedule runs against `main`,
which has not moved from `9754f666` all day.

## The failure, exactly

Step 3 `Ping Render backend health`, exit code 28:

```
[Mon Nov 16 15:29:26 UTC 2026] Curling Render health endpoint...
curl: (28) Operation timed out after 60002 milliseconds with 0 bytes received
Render health HTTP:000 time:60.002625s
##[error]Process completed with exit code 28.
```

Step 2 (Postgres) passed in the same run, so the Supabase clock was reset. Only the Render warmth probe
failed.

## Root cause

**Render free-tier cold start versus a 60-second timeout.** The service had been idle long enough to be
spun down; a cold boot takes longer than 60s; the probe had exactly one attempt and no retry, so a cold
start and an outage were indistinguishable. Confirmed by probing the same URL live minutes later:
`HTTP 200 in 0.16s`. The backend was healthy the whole time.

It is **not** a backend regression, not a credential change (Postgres auth passed in the same job), and
not caused by recent Copilot work (the job runs on `main`, and the workflow touches no app code).

## Two further defects found while fixing it

1. **A 503 would have passed.** `curl -o /dev/null -w "HTTP:%{http_code}"` without `--fail` exits 0 on a
   503, so a broken backend would have logged itself as a successful warmth check. A probe that cannot
   fail is not a probe.
2. **The first scripted version could not find itself.** After moving the logic into
   `scripts/backend-health-probe.sh` the job had no `actions/checkout` step, so it failed with
   `No such file or directory` — a failure with nothing to do with the backend, which is precisely the
   confusion this job exists to prevent (`43898680978`).

## Fix

- `scripts/backend-health-probe.sh`: bounded retries (`PROBE_ATTEMPTS`, default 3) with a cold-start
  timeout (`PROBE_TIMEOUT`, default 120s), `--fail` so any non-2xx is a failure, and a non-zero exit when
  every attempt fails. Retrying survives a cold start; it never launders a broken backend into green.
- `scripts/test-backend-health-probe.sh`: regression coverage against throwaway local servers —
  healthy first time, cold start recovered by retry, backend that never recovers still fails, a 503 never
  read as healthy, a slow first response inside the timeout, an unreachable host failing.
- `backend-keepalive.yml`: checkout added; step 3 calls the script; step 4 runs the behaviour check.
- `android-build.yml`: the behaviour check also runs on every build, so the probe cannot quietly regress.

## Verified behaviour, not just a green tick

Run `43903082582` (job `keepalive`, 5 steps, all success) on `7ee6868`:

```
probe 1/3 ... healthy: HTTP 200 in 42.366004s      <- a real cold start, inside the new timeout
ok   - a healthy backend passes first time (exit 0)
ok   - a cold start is survived by retrying (exit 0)
ok   - a backend that never recovers still fails (exit 1)
ok   - a 503 is never reported as healthy (exit 1)
ok   - an unreachable backend fails
```

The 42-second response is the point: the same backend state that produced a red run under a 60s
single-attempt probe is now correctly reported as healthy, and the negative cases still fail.

## Limitations

- The daily schedule cannot keep Render warm (free tier sleeps after ~15 minutes of inactivity). This job
  is a **correctness** check and a Supabase clock reset, not a warmth guarantee, and it no longer claims
  otherwise.
- GitHub's hosted-runner queue delayed the first retry of this workflow by ~20 minutes with zero jobs
  listed; the re-dispatched run started promptly. Queue behaviour is outside this repository.
- The Supabase step still reports success or failure from `psql`'s exit code with no assertion of its own
  beyond the query; `SELECT 1` proves reachability, not schema health.
