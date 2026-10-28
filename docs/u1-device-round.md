# U1 device round — single cumulative build

**Build:** `ad80257` (CI run **3416**, green). One round covers all five U1 defects.
Nothing is pending from U1 in code; these five checks are the ones only a device can answer.

Stop and report at the first failure, with the exact on-screen text.

## 1 — Keys are found and usable (a)
1. Chat → provider dialog. Every provider you saved a key for shows **(key stored)**; none shows **(no key stored)**.
2. Send one message on **Gemini**. It must not say "No key stored".
   *If it fails, copy the error bubble text verbatim — that names the layer (key lookup vs wire).*

## 2 — Provider/model stay in sync (d)
3. Switch to **Ollama** (or any keyless preset). The header must **not** show a gemini model id.
4. Force-stop the app, reopen chat. The header shows a provider you actually have a key for, and Send works
   without touching the dialog.

## 3 — Endpoint save survives (c)
5. Add a custom endpoint → Save. It appears in the list immediately and the header switches to it
   (showing **Pick model** is correct until you type a model).
6. Type a model id, Apply, send. Then force-stop, reopen: the endpoint is still there and still selected.
   *Vanishing here means the write never landed on this device — the one risk the JVM round-trip cannot cover.*

## 4 — Dialog scrolls (b)
7. Open the provider dialog and scroll to the bottom. **Model id** and **Context tokens override** must be
   reachable, with Cancel/Apply still on screen.

## 5 — Input is usable in a narrow panel (e)
8. Split-screen or narrow the panel. The message field is a real multi-line field on its own line, with
   **Attach** and **Send** beneath it. It must never collapse to a sliver.

## What CI cannot prove
The 588-test JVM suite exercises the store through a storage seam. Android's actual
SharedPreferences/EncryptedSharedPreferences **commit** on this device (filesystem, keystore, storage
pressure) is only provable here — that is exactly what check 6 tests.
