# Voice and Media Intelligence — investigation and proposed architecture

**Date:** 2026-11-15
**Scope:** OCR engine choice, speech transcription choice, Brahma voice experience, and the native voice architecture proposed for VN Code.
**Status:** research only. Nothing downloaded, nothing installed, no APK built, no cloud service called. No existing stage touched (`media_info`, `image_convert`, `video_frames` stay as they are).

**Evidence labels used throughout**

| Label | Meaning |
|---|---|
| **CONFIRMED** | Stated on the vendor's own documentation or read out of a file I inspected. |
| **OBSERVED** | Witnessed on a device by the owner, reported to me. Not independently reproduced. |
| **INFERRED** | Follows from confirmed structure plus how Android works. Reasonable, not verified. |
| **UNKNOWN** | Cannot be established from what is accessible now. |

---

## 1. OCR engine comparison

### 1.1 Google ML Kit Text Recognition v2

All figures below are from Google's own Android guide for Text Recognition v2 (**CONFIRMED**, developers.google.com/ml-kit/vision/text-recognition/v2/android).

| Property | Bundled | Unbundled |
|---|---|---|
| Dependency | `com.google.mlkit:text-recognition*` | `com.google.android.gms:play-services-mlkit-text-recognition*` |
| App size | **about 4 MB per script, per architecture** | **about 260 KB per script, per architecture** |
| Model availability | Statically linked at build time | **Downloaded via Google Play Services** |
| Initialization | Immediate | May wait for a download; requests before it completes return nothing |
| Min API for the API | API 23 | API 23 |

- Scripts offered: **Latin, Chinese, Devanagari, Japanese, Korean** (**CONFIRMED**). A script is a separate dependency, so Latin alone is one model.
- Offline: the bundled model is **offline by construction** (**CONFIRMED**). The unbundled model is offline *after* its one download (**CONFIRMED**).
- Performance statement: "Real-time on most devices for Latin script library, slower for others" (**CONFIRMED**).
- Image size limits: the guide ties accuracy to input resolution and focus and advises controlling processing frequency, but states no hard pixel ceiling (**CONFIRMED** that no limit is published; **INFERRED** that downscaling to roughly 1,500–2,000 px on the long edge is the practical sweet spot, since beyond that the extra pixels cost memory and add nothing).
- Word boxes and confidence: ML Kit's `Text` result exposes lines, elements and language, and the block/element level carries a `confidence` value (**INFERRED** from the API surface; the numeric range is `0..1` and Google does not guarantee it is populated for every element).
- Google Play Services dependency for the unbundled path means a device without Play Services gets nothing (**INFERRED**, and it matters: it makes the unbundled path a *runtime dependency*, not just a download).

### 1.2 Alternatives considered and why they lose here

| Option | Size | Verdict |
|---|---|---|
| **Tesseract4Android** | ~8–15 MB of tessdata for English plus native libs | Apache-2.0 and fully offline (**CONFIRMED** licensing is permissive), but materially weaker on phone photos and screenshots of UI text, and no confidence-per-word by default. Kept as a fallback only. |
| **Cloud OCR** (Google Vision, Azure Read) | 0 MB in app | Rejected as a default: per-request cost, network dependency, and it ships the user's screenshots to a server. Contradicts the owner's data rules. |
| **Bundled self-hosted model** (PaddleOCR/ONNX) | 20–60 MB | Rejected: storage and RAM cost on a 3 GB device for no accuracy gain over ML Kit at this task. |

### 1.3 Recommendation for OCR

**Bundle ML Kit Latin only** (`com.google.mlkit:text-recognition:16.0.1`).

Reasons: Latin is exactly what a coding screenshot, a photographed document and a scanned page need; it is offline from first launch with **no silent download** (the owner's explicit rule); the build-time cost is one dependency, and the runtime cost is about 4 MB of APK for the arm64 ABI, which is 2.7% of the current 79 MB APK (**CONFIRMED** against the CI APK size in run 43745842438). Devanagari can be added later as a second dependency if the owner wants it; it is not needed to read English, code, or numerals.

Text structure and reading order: ML Kit returns blocks → lines → elements in the order its detector found them, which is layout order, not guaranteed reading order (**CONFIRMED** as a limitation). Preserving structure therefore has to be *our* step: we reassemble lines by their bounding-box `top`, group into paragraphs on vertical gaps, and keep code-shaped lines verbatim (do not reflow a line that starts with indentation). That logic is pure Kotlin and fully testable without a device.

---

## 2. Speech transcription comparison

| Option | Model size | Runtime memory | Offline | Cost | Verdict for this device |
|---|---|---|---|---|---|
| **Android `SpeechRecognizer`, on-device** | 0 MB in our APK | low, owned by Google app | yes, when an offline language pack is installed (**CONFIRMED** that Android 13+ exposes on-device recognition; the pack is a system setting, not ours to download) | free | **Recommended stage 1** |
| **Vosk small-en-us 0.15** | **40 MB** model | **about 300 MB** for small models (**CONFIRMED**, alphacephei.com/vosk/models) | yes | free, Apache-2.0 (**CONFIRMED**) | **Recommended stage 2**, opt-in download |
| **Whisper via whisper.cpp, tiny** | **75 MiB** | **about 273 MB** (**CONFIRMED**, whisper.cpp README) | yes | free, MIT (**CONFIRMED**) | stage 3, file transcription only |
| **Whisper base / small** | 142 MiB / 466 MiB | about 388 MB / 852 MB (**CONFIRMED**) | yes | free | **Rejected**: 852 MB of RAM on a 3 GB phone alongside an IDE and a sandbox is not defensible |
| **Cloud** (Google STT ~$0.016/min, Azure ~$1/hour, OpenAI Whisper $0.006/min) | 0 MB | none | no | recurring (**INFERRED** from secondary sources; verify at the chosen vendor before use) | opt-in only, never default |

Accuracy on **Nigerian English**: no benchmark exists for Vosk's small US-English model or for whisper tiny on Nigerian-accented speech (**UNKNOWN**). This is the single biggest risk in the transcription work and it is *only* resolvable by testing on the owner's own recordings during the device round. Whisper is generally the more accent-robust family (**INFERRED** from its training scale; not verified here). Android's on-device recognizer is tuned by Google and is the best bet for accent tolerance at zero cost (**INFERRED**).

Battery and long recordings: a 40 MB Vosk model decoding 30 minutes of audio is a sustained CPU load; the honest design is to chunk audio into 20–30 second windows, release the recogniser between chunks, and expose a cancel that stops at the next window boundary (**INFERRED** design). Whisper tiny on a 3 GB phone runs slower than real time (**INFERRED** from its 273 MB working set; no measured rate on this hardware — **UNKNOWN**).

### 2.1 Recommendation for STT

Stage 1: **Android's own recognizer, on-device preferred, for voice instructions.** Zero APK cost, no model to download, no consent problem.
Stage 2: **Vosk small-en-us (40 MB), downloaded only when the owner presses a button**, for offline file transcription and for the case where the Google app's recogniser is unavailable.
Stage 3: optional **Whisper tiny** for archiving-quality transcription of long recordings, behind the same explicit-download gate.
Never bundled, never downloaded silently. Each stage is independently switchable, which is what "modular" means here.

---

## 3. Brahma voice investigation

### 3.1 What I could inspect, and what I could not

I did **not** download the APK, because this prompt forbids downloading anything until the investigation is documented. What follows therefore uses two evidence sources only: the read-only APK audit of 14 November (which did inspect the 55 MB `BrahmaAI-Mobile-v1.0.1-release.apk` and mined four dex files for class and string tables), and the owner's own observations.

Drive still holds **two** copies of `BrahmaAI-Mobile-v1.0.1-release.apk`, both exactly **55,630,071 bytes** (**CONFIRMED**, Drive API listing today). One was modified 13 November 2026, one 28 October 2026. Identical size, so the 14 November conclusion stands: treat them as the same build until byte-compared.

### 3.2 Findings

| # | Question | Finding | Label |
|---|---|---|---|
| 1 | Which TTS engine or provider? | No TTS vendor SDK was identified in the audit's class and string tables. The manifest declares `BIND_VOICE_INTERACTION` and the APK contains `BrahmaVoiceInteractionService`. | **CONFIRMED** (component exists) |
| 2 | Where does the male voice come from? | Almost certainly the device's own TTS engine reached through the Android speech API, not a bundled model and not a proprietary voice. A `VoiceInteractionService` is *required* by the Android voice-interaction contract to produce speech through the platform; there is no evidence of a bundled neural voice, and no vendor endpoint string was found in the audit. | **INFERRED** |
| 3 | A specific voice ID, language, accent or configuration? | Not readable without opening the dex to look for `setVoice`, `Voice`, locale and pitch strings. | **UNKNOWN** |
| 4 | Local or remote speech? | Local, through the system engine. If the installed engine is Google TTS, the *voice data* ships with the engine; synthesis itself runs on-device. | **INFERRED** |
| 5 | Are welcomes AI-generated or fixed? | The APK contains a proactive subsystem (`BrahmaProactiveService`) and two AI provider clients (Gemini over SSE, OpenRouter). Either a template pool with injected variables, or a short model call, would be consistent with what was found. | **UNKNOWN** (both plausible) |
| 6 | What triggers the occasional welcome? | The presence of a dedicated proactive service is the strongest available signal: a background/proactive component rather than the activity lifecycle. | **INFERRED** |
| 7 | Does the trigger depend on opening, returning after inactivity, or elapsed time? | The owner's report — "sometimes welcomes me, not every interaction" — is consistent with a *conditions met* check (last-seen gap, or a daily/first-open flag). It is inconsistent with an on-open greeting. | **OBSERVED** + **INFERRED** |
| 8 | Does it remember previous greetings or rate-limit? | Some persistence must exist, because the behaviour is occasional rather than constant. The APK contains `BrahmaMemoryStore` and `BrahmaChatHistoryStore`; a last-greeted timestamp could live in either. | **INFERRED** |
| 9 | Streamed or generated before playback? | Platform TTS synthesises speech per utterance and can stream it as it is produced; nothing in the evidence suggests pre-generated audio files. | **INFERRED** |
| 10 | Interruptions, backgrounding, navigation, closure? | Not determinable without runtime observation. A `VoiceInteractionService` normally stops speech on session end. | **UNKNOWN** |

### 3.3 What would turn the UNKNOWNs into evidence

One step, one file: download the 55 MB APK (the owner's own file, from his own Drive) into the sandbox and inspect it — `classes*.dex` for `TextToSpeech`, `setVoice`, `setPitch`, `setSpeechRate`, locale strings, ElevenLabs/Azure/Polly endpoints; `assets/` for greeting templates; the service's own strings for welcome text. That is static analysis of a file already on his Drive, costs no mobile data, and produces hard evidence for items 3, 5, 6, 8 and partly 9.

**This needs one word of approval**, because the prompt says not to download before this report exists. The report now exists, so the decision is the owner's.

---

## 4. Recommended VN Code architecture

One engine, three consumers. Voice, OCR and transcription are independent modules that share a single speech service.

```
                     ┌──────────────────────────────┐
  image  ──► ocr ────►  MediaTextResult (text,      │
  audio  ──► stt ────►  structure, boxes, conf)     ├──► Copilot (as a user turn or a tool result)
  mic    ──► stt ────►                              │
                     └──────────────────────────────┘
                                  │
  Copilot reply ──► tts ──► spoken output ──► same voice used by the welcome
```

**A. Speech output — `VoiceService` (thin wrapper over Android `TextToSpeech`)**
- Selects a **male English voice** by preference order: engine voice list filtered for a male-capable English voice, then any English voice, then the engine default. Every step down the list is announced in the UI, never silent (**CONFIRMED** that Android voice availability is engine- and device-dependent, so this fallback is mandatory, not defensive).
- Configurable: voice, pitch (default 0.95, a small downward nudge that reads as less synthetic), rate (default 0.95), volume.
- Streams per utterance; `QUEUE_FLUSH` on a new response, `QUEUE_ADD` only for the welcome.
- Never speaks over itself: if `isSpeaking`, a welcome is dropped, not queued.
- Releases the engine when the app backgrounds for more than a minute, because a held `TextToSpeech` keeps a service binding.

**B. Welcome — `GreetingPolicy` (pure, fully testable)**
Triggers considered, and the rules chosen:
- First ever open: greet once, ever. Stored flag.
- Return after **≥ 6 hours** since the app was last in the foreground: eligible.
- Fresh Copilot session after **≥ 90 minutes** idle: eligible, but only if nothing has been spoken in this process.
- **Rate limit:** at most one greeting per 4 hours, at most two per calendar day. Counter persisted.
- **Suppression:** never while a response is streaming, while TTS is speaking, while the app is not the resumed activity, or while the device is locked.
- **Privacy:** the greeting may name the *project*, never a file path, a branch name, a token, or any content. It is composed for the speaker, not the lock screen, and it is never posted as a notification.
- Message pool of ~8 templates plus project-aware variants ("Welcome back. Ready to continue with `<project>`?"), chosen deterministically by day so it does not repeat twice in a row. **No AI call** for the greeting: a model round trip to say "welcome back" is cost and latency for nothing.

**C. Controls**
Spoken responses on/off (master), welcome greetings on/off (independent), voice picker with a **preview button**, pitch and rate sliders, volume, local-only toggle (which disables any future cloud voice path), voice-input on/off, spoken-responses-per-turn cap. All persisted; all off by default except spoken responses on an explicit user request.

**D. Low-resource fit (Android 14, TECNO, arm64, ~3 GB)**
No bundled neural voice. No bundled STT model. ML Kit Latin only, ~4 MB. Peak extra RAM when idle: near zero, because the TTS engine is released when backgrounded. The heaviest new thing by far is ML Kit during a single recognition, and a scan is bounded by downscaling the bitmap before it is handed over.

---

## 5. Impact summary

| Item | APK | RAM (idle / peak) | Storage on device | Data | Cost |
|---|---|---|---|---|---|
| ML Kit Latin v2 (bundled) | **+4 MB** (arm64) | 0 / moderate during a scan | 0 extra | none | free |
| Android STT (on-device) | 0 | 0 / Google-owned | 0 (may already have a language pack) | none offline | free |
| Vosk 40 MB (opt-in) | 0 in APK | 0 / ~300 MB while decoding | **40 MB**, only if pressed | one 40 MB download, on request | free |
| Whisper tiny (opt-in) | 0 in APK | 0 / ~273 MB | 75 MB, only if pressed | on request | free |
| Android TTS | 0 | small binding while speaking | 0 | none | free |
| Cloud anything | 0 | 0 | 0 | full audio/images leave the device | per minute/request |

Current APK is 79 MB (arm64 prod debug, run 43745842438). ML Kit Latin moves it to about 83 MB. That is the entire OCR cost.

---

## 6. Implementation stages

Each stage is one commit, its own tests, its own CI run.

| Stage | Work | Download? | Testable on JVM |
|---|---|---|---|
| **V1** | OCR ingestion: ML Kit Latin wired to a new `image_ocr` tool, structure reassembly (line grouping, code-shaped line preservation), confidence and boxes returned | Gradle dependency only (build-time) | The reassembly logic is pure: yes |
| **V2** | OCR → Copilot: extracted text offered as a tool result and as an attachment-style turn | no | yes |
| **V3** | `VoiceService` + voice picker, pitch, rate, volume, preview, release-on-background | no | The selection order and settings are pure: yes |
| **V4** | `GreetingPolicy` + persistent state + suppression rules + tests for every frequency edge | no | yes, entirely |
| **V5** | Welcome templates, project-aware variant, wiring to `VoiceService` | no | yes |
| **V6** | Voice input via Android `SpeechRecognizer`, `EXTRA_PREFER_OFFLINE`, cancel | no | The state machine is pure |
| **V7** (opt-in, only if approved) | Vosk download manager with explicit consent, size shown before download, resumable, removable | **yes, one 40 MB download, user-pressed** | chunking logic is pure |
| **V8** (optional) | Whisper tiny for long-recording transcription | only if approved | no |

Device verification for all of it stays in the separate device-testing round, as agreed.

---

## 7. Tests and acceptance criteria

- **OCR reassembly:** a synthetic line/box list reproduces in reading order; a code block keeps its indentation; two columns do not interleave.
- **Confidence:** absent confidence is reported as absent, never as 0.0 or a fabricated number.
- **Greeting policy:** first launch greets once; a return after 5 h 59 m does not; after 6 h it does; a second greeting inside 4 h does not; the third attempt in one day does not; a greeting is dropped while speaking or streaming; a locked screen is never spoken to.
- **Voice selection:** given a list of voices, the male English one wins; with none, the engine default is used and the fallback is surfaced in the UI.
- **Acceptance:** all of the above pass on JVM; no new network permission; no model downloaded without a user press; APK growth under 6 MB for V1–V6 combined.
- Device round: greeting appears after a real overnight gap and not twice; OCR reads a screenshot of code with correct line structure; spoken reply uses the selected male voice; the app is not audibly interrupted.

---

## 8. Limitations of this report

- **Nothing was run.** No OCR, no transcription, no TTS on the device.
- **Brahma items 3, 5, 6, 8, 9 are inferences from class names**, not from reading code. Kotlin bodies are compiled and R8 strips local names, so even a downloaded APK will yield structure and strings, not algorithms.
- **No accent benchmark exists** for Nigerian English on Vosk small or Whisper tiny. Only the owner's recordings can settle it.
- **Cloud prices are secondary-source figures** and must be confirmed at the vendor before any spend.
- **Voice availability cannot be promised** for a specific device. The fallback path is part of the design precisely because Google's voice catalogue depends on the installed engine and on language packs.
- The 14 November note stands: **no runtime observation of Brahma was ever made**, so every claim about its *behaviour* (as opposed to its structure) is inference.
