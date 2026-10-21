# U4-P PLAN — isolated-process PDF extraction (PLAN ONLY, no code; advisor 2026-10-21)

> Context: the in-process containment (PdfExtractionGate, shipped) confines a
> stuck pdfbox parse to ONE daemon thread, refuses new PDF work while it
> burns, and heals when the parse ends. What it CANNOT do is kill a truly
> stuck parse (pdfbox's page loop never checks Thread.interrupted()). This
> plan evaluates the next step: run the untrusted-file parse in a SEPARATE
> PROCESS the app CAN kill. Per the advisor: not built; code freeze active.

## 1. Source-first evidence (READ, cited)

- **AOSP `frameworks/base/core/java/android/os/Process.java`** — isolated
  processes get their own uid range: `FIRST_ISOLATED_UID = 99000`,
  `LAST_ISOLATED_UID = 99999` (lines 346/354); `UserHandle.isIsolated(uid)`
  is exactly that range check (line 951-952). Isolation is a KERNEL-LEVEL
  identity, not a convention.
- **AOSP `system/sepolicy/private/isolated_app.te`** — the isolated_app
  domain: "New socket creation is not permitted" (only sockets RECEIVED over
  IPC are usable — no network); and, the load-bearing rule for us
  (b/32896414): "Allow accessing sdcard file descriptors passed to
  isolated_apps by other processes. Open should never be allowed" — our
  project PDFs live on /sdcard FUSE storage, and the SELinux policy
  explicitly blesses passing those files as FDs into an isolated process
  while denying the process the ability to OPEN paths itself. The file also
  documents the canonical real-world deployment: WebView renderers forked
  from webview_zygote run as isolated_app — Chromium's renderer sandbox is
  the "how real apps isolate untrusted parsing" precedent, and it uses the
  same fd-over-IPC pattern.
- **Android docs (DOC)** — `<service android:isolatedProcess="true">`: runs
  under an isolated uid, no permissions, no network, communication only via
  the Service API (binding). `android:process` starts any component in a
  named private process. Binder transactions are limited to the 1 MB
  binder-mapped buffer (asynchronous transfers are limited to ~half the
  buffer is a common engineering constraint; treat 512 KB as the safe
  per-message budget and chunk).
- **AMS `ActivityManagerService.java`** — `startIsolatedProcess(...)` path
  (lines ~2882-2925): the parent's uid and the isolated uid are carried
  separately in the ProcessRecord; the process is spawned by AMS, so its
  lifecycle (and death) is observable by the app through
  onServiceDisconnected. DOC-level expectation to verify ON DEVICE before
  any build: isolated processes carry no foreground oom-protection and are
  reclaimed by LMK first under memory pressure (plausible for a low-RAM
  phone; measured, not assumed, before shipping).

## 2. Architecture (proposed)

A `PdfExtractWorkerService` in the manifest:

```xml
<service android:name=".chat.pdfworker.PdfExtractWorkerService"
         android:isolatedProcess="true"
         android:process=":pdfextract" />
```

- The app process does NOT open the PDF for the worker — it passes a
  `ParcelFileDescriptor` (pfd opened in the app, handed over binder). This
  is the exact pattern the sepolicy rule above blesses for sdcard files.
- The worker runs pdfbox text extraction (same `MemoryUsageSetting`
  main-memory-only, same page cap, same internal deadline) and returns
  per-page text CHUNKED (<=256 KB per message) via the reply Messenger.
- TRUE CANCELLATION, finally: a watchdog thread in the worker calls
  `Process.killProcess(Process.myPid())` when the deadline passes — the
  parse thread dies WITH the process, mid-page included. The app side sees
  `onServiceDisconnected`, converts it to the honest "PDF extraction did
  not finish" refusal, and the next extraction binds a fresh process. A
  crash, OOM, or StackOverflowError in the worker CANNOT take down the app:
  it is a different process; the app process only observes the disconnect.
- The in-process `PdfExtractionGate` STAYS as the caller-side guard
  (Busy refusal while a bind is in flight) — the two layers compose; the
  gate is not replaced.

## 3. Costs (honest, with what is estimate vs measured)

- **Startup**: a cold spawn of an isolated process is a zygote fork + init
  (~100-500 ms on a mid-range device; ESTIMATE — must be measured on the
  TECNO KL4 before shipping; first-PDF-per-session latency is the cost).
  Rebinds reuse a warm worker if we keep the service bound briefly.
- **Memory**: a second full ART process: ~15-40 MB baseline (ESTIMATE;
  pdfbox classes duplicated in the worker). On a low-RAM phone this is the
  biggest real cost; mitigations: kill the worker after N seconds idle
  (stopService), never keep it alive in the background.
- **APK delta**: ZERO bytes of APK size (same APK, manifest attribute only).
- **Battery/thermals**: negligible unless the stuck-parse case fires, where
  the process kill ENDS the burn — a net improvement over today.

## 4. What moves into the worker

- **v1 (recommended): pdfbox TEXT extraction only.** It is the single
  untrusted-parse risk surface (compressed streams, font programs, nested
  page trees — the SOE/OOM/stuck history lives here).
- **NOT moved initially:** the U2/U3 Office extractors (pure-JVM zip+xml
  with bounded reads — lower risk class) and Android's built-in
  `PdfRenderer` (framework code, already memory-bounded by the
  RENDER_TARGET_WIDTH downscale; optional phase 2 if the owner wants page
  images sandboxed too). Moving everything on day one doubles the binder
  churn for little added safety.

## 5. Failure modes (each maps to a user-visible honest outcome)

1. Worker OOMs/crashes mid-parse → app survives, onServiceDisconnected →
   "PDF extraction failed — showing file info only", retry allowed.
2. Deadline exceeded → worker's watchdog kills its own process → app sees
   disconnect → honest timeout wording (and NO lingering CPU burn — the
   thing no thread-based fix can promise).
3. Binder >256 KB results → chunked messages; reassembly tested.
4. fd leak → pfd closed in the app's finally + in the worker's finally.
5. LMK reclaims the isolated process mid-parse (low RAM) → same as (1);
   the app NEVER depends on the worker surviving.
6. Bind races (two PDFs at once) → the existing gate's single-lane Busy
   rule extends to the binder handoff; one worker, one job at a time.
7. Isolated-process spawn fails (rare OEM quirks) → fall back to TODAY's
   in-process extraction path (the gate code stays) — the feature degrades,
   it never breaks attachment flow.

## 6. Tests

- JVM: chunk/reassembly protocol round-trip; deadline marshalling;
  refusal-wording mapping for every failure mode above.
- Robolectric/JVM: the gate+worker state machine (bind in flight, busy,
  disconnected → healed) with a fake service connection.
- ON DEVICE (requires a round item — so this waits for the owner's device
  pass and the code-freeze lift): cold-spawn latency, worker-kill latency,
  memory delta with/without worker, one hostile fixture (deep-nesting PDF)
  proving the app process survives a worker crash.

## 7. Recommendation

RECOMMENDED as a post-device-round phase (U4-P1, pdfbox-only), gated on:
(a) the owner lifts the code freeze after the device round, (b) on-device
measurement of spawn cost + memory on the TECNO KL4 confirms the estimate,
(c) CI green with the fallback path proven. The honest win is TRUE
cancellation of an unkillable parse — the one property the thread-based
containment cannot have — at the cost of one extra process during PDF
extraction only. NOT recommended before the device round, and NOT
recommended for the Office extractors in v1.

## 8. Advisor check triad (2026-10-21, READ from source / AOSP; DOC where docs-only)

**(a) pdfbox-android resource loading in an isolatedProcess service.**
READ, pdfbox-android 2.0.27.0 source (the pinned dependency),
`library/src/main/java/com/tom_roush/pdfbox/android/PDFBoxResourceLoader.java`:
`init(Context)` does exactly ONE thing — caches
`context.getApplicationContext().getAssets()` (an `AssetManager`); every
resource load (the Noto font fallback set) goes through that AssetManager
as an `InputStream`. An AssetManager reads the app's OWN APK assets; the
isolated process runs the app's code under the app_domain rules — the same
mechanism the WebView renderer (isolated_app) uses to load its resources.
VERDICT: works, with ONE discipline: `PDFBoxResourceLoader.init(...)` is
per-process static state — the main process's init does NOT carry over,
so the worker service MUST call `init(this)` in `onCreate()` with its OWN
context (a Context is not parcelable across processes anyway). Confidence:
source-based HIGH for the mechanism; the one device-verify row of U4-P1
confirms the first PDF's font fallback in the worker.

**(b) Random access / seekability of a passed /sdcard FUSE descriptor.**
READ, pdfbox-android source: today's `PdfBoxTextEngine` calls
`PDDocument.load(File, mainMemoryOnly)`, which routes through
`RandomAccessBufferedFileInputStream(File)` (PDDocument.java:1076) — that
constructor OPENS THE FILE BY PATH (`new RandomAccessRandomAccessFile(file,
"r")`). An isolated process CANNOT open paths — the AOSP
`system/sepolicy/private/isolated_app.te` rule is explicitly
fd-passing-only ("Open should never be allowed"), so the File overload is
UNUSABLE in the worker regardless of seekability. The load-bearing
subtlety: the sepolicy fuse rule for passed descriptors allows
`{ read write append getattr lock map }` — **seek is NOT in that list**, and
whether a later AOSP branch added a `file:seek` allow for isolated_app is
not something this plan assumes. ENGINEERING ANSWER: don't seek the
descriptor at all. The worker reads the passed ParcelFileDescriptor
SEQUENTIALLY into a bounded byte array (the extraction path already caps
sizes; add an explicit worker-side buffer cap), then calls the pdfbox
`PDDocument.load(byte[])` overload, which builds an in-memory
`RandomAccessBuffer` (PDDocument.java:1298) — no file open, no seek, no
temp file. Sequential read of a passed fuse fd is exactly what the
sepolicy rule blesses (b/32896414). The byte[] route also AVOIDS the
`RandomAccessBufferedFileInputStream(InputStream)` constructor's
`File.createTempFile` path (source: `createTmpFile` in that class) — an
isolated process cannot write app-data temp files either, so the byte[]
overload is the ONLY safe pdfbox entry point in the worker.
VERDICT: feasible, but ONLY via the byte[] overload; this is now a design
requirement of section 2, not an open question.

**(c) Binder reply limits + Android 14 start/bind restrictions.**
READ, AOSP `frameworks/native/libs/binder/ProcessState.cpp`:
`#define BINDER_VM_SIZE ((1 * 1024 * 1024) - sysconf(_SC_PAGE_SIZE) * 2)`
(line 47) — each process maps ~1 MB of shared binder buffer, and ONE-WAY
(asynchronous, Messenger-style) transactions occupy that buffer on the
RECEIVING side until consumed. Plan's 256 KB chunks: at most a few pending
chunks against a ~1 MB buffer — safe headroom; a full extracted text at our
60k-char snapshot cap fits in ONE chunk anyway, the chunking exists for the
worst case, not the common one. ANDROID 14 RESTRICTIONS (DOC +
code-of-record): binding has NO caller-foreground requirement —
`bindService` is always legal from a running app process; what Android 14
restricts is STARTING services from the background and launching foreground
services from background states, neither of which the design uses (the app
binds while the chat UI is up; the isolated worker is bound, never
startService'd, and holds no foreground type). On-demand spawn is handled
by AMS's isolated-process path (ActivityManagerService.startIsolatedProcess,
cited in section 1). VERDICT: no Android-14 blocker for a bound isolated
service driven from a foreground-visible app.

All three checks are now CLOSED in the plan; U4-P remains PLAN ONLY and is
NOT built before the owner's device round (advisor ruling 2026-10-21).
