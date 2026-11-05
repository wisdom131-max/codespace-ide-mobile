# Secret handling — researched before any change

Date: 2026-11-05. Stage 2, "secrets research report" (device finding #2: where do the keys live,
what is enciphered, what is not). **Research only. No code changed by this document.**

## What was actually read

| Source | What it is | Licence |
|---|---|---|
| `vscode-src/src/vs/platform/secrets/common/secrets.ts` (local mirror of microsoft/vscode `main`) | `ISecretStorageService`, `BaseSecretStorageService` | MIT |
| `vscode-src/src/vs/platform/encryption/common/encryptionService.ts` | `IEncryptionService`: `encrypt`, `decrypt`, `isEncryptionAvailable` | MIT |
| `vscode-src/src/vs/platform/encryption/electron-main/encryptionMainService.ts` | the Electron implementation (OS keychain via safeStorage) | MIT |
| `android/app/src/main/java/com/codespace/ide/data/SecureTokenStore.kt` | VN Code's own store | — |
| `android/app/src/main/java/com/codespace/ide/chat/panel/ChatSettingsPrefs.kt`, `editor/…` call sites | where keys are read | — |

**Not read / not claimed:** VS Code's Electron `safeStorage` internals (Chromium), and any
statement about what GitHub's own servers do with a token after it is sent.

## What VS Code does (verified)

1. **One service, async, keyed by string.** `ISecretStorageService.get(key)` / `set(key, value)`
   return promises and a per-key `SequencerByKey` serialises mutations
   (`secrets.ts:101-140`). Callers never touch a file.
2. **Encryption is delegated, not implemented.** `IEncryptionService` exposes exactly
   `encrypt(value)`, `decrypt(value)`, `isEncryptionAvailable()` (`encryptionService.ts:21-25`).
   On desktop the implementation is OS-level (Electron `safeStorage`, i.e. DPAPI / Keychain /
   libsecret).
3. **It can degrade, and says so.** The service tracks `type: 'in-memory' | 'persisted' |
   'unknown'` and is constructed with a `_useInMemoryStorage` flag (`secrets.ts:106-121`). When
   encryption is unavailable the secrets still work, but only for the session — the degradation is
   explicit and inspectable rather than silent.
4. **Secrets live outside settings.** They are not written into `settings.json`; configuration holds
   ids, the secret service holds values.

## What VN Code does (verified)

1. **One store, same idea.** `data/SecureTokenStore.kt` wraps `EncryptedSharedPreferences` with a
   `MasterKey` (`KeyScheme.AES256_GCM`), key names encrypted with `AES256_SIV`, values with
   `AES256_GCM` (`SecureTokenStore.kt:44-56`). Keys are shaped `ai_<provider>`
   (`SecureTokenStore.kt:216`).
2. **Master key is in the Android Keystore.** `MasterKey.Builder` without a custom key means the
   key material is generated and held by the Keystore, not derivable from app data.
3. **It already survives the failure that matters.** `EncryptedSharedPreferences.create` used to run
   eagerly in `init` and an `AEADBadTagException` / `KeyStoreException` there killed the process;
   creation is now lazy and `quarantineUnreadableStore()` (`SecureTokenStore.kt:32-79`) moves an
   unreadable store aside instead of crashing. This is stronger than VS Code's desktop path, which
   simply reports encryption unavailable.
4. **The PIN is not a key.** `setPin` / `verifyPin` store a PBKDF2-derived verifier and a failure
   counter (`SecureTokenStore.kt:125-197`); the PIN gates the UI, it does not encrypt provider keys,
   and no provider key is derived from it.

## Comparison and gaps

| Area | VS Code | VN Code | Gap | Recommendation |
|---|---|---|---|---|
| Where values live | OS keychain via Electron | Android Keystore + EncryptedSharedPreferences | none material | keep |
| Degradation visibility | `type` field, in-memory fallback | lazy create + quarantine, but no surfaced state | **small**: the UI cannot say "your keys are not persisted, only this session" | show a one-line note in Settings → AI providers when the secure prefs could not be opened; no API change needed |
| Key naming | caller-chosen strings | `ai_<provider>` | none | keep |
| Secrets in logs | never | never (the app logs paths and reasons, not values) | none | keep; the `[chat-storage]` lines added in 3549 log a directory NAME only |
| Secrets in the transcript | n/a | attachment bytes never persisted (WP8), names only | none | keep |
| Rotation on exposure | n/a | policy: any key printed in chat or terminal is rotated | none | unchanged |
| Keys in backups | not applicable (keychain) | `BackupManager` copies `shared_prefs` EXCEPT the keystore-bound file (`isKeystoreBoundFile`, `codespace_secure.xml`) | none — copying it would be useless anyway, the Keystore key does not travel | keep, and this document is the evidence for that exclusion |

## Deliberate non-actions

- **No migration to a new store.** The current stack is the platform-recommended one and already
  handles the crash case that broke builds on this device.
- **No PIN-derived encryption.** Deriving a vault key from a 4-digit PIN would lower the real
  security below the Keystore's, and would lock every key behind a forgotten PIN.
- **No plaintext fallback.** If the Keystore path ever fails hard the honest answer is "keys cannot
  be read on this device", not "here they are in the clear".

## The one proposed change (not made here)

Settings → AI providers should show a short line when the secure store had to be quarantined or
created fresh, so a user whose keys vanished after an OS upgrade knows why. It needs a `state`
accessor on `SecureTokenStore` and one row in the settings panel; it is UI, so it belongs with the
Stage 3 rows rather than here, and it is not implemented by this document.
