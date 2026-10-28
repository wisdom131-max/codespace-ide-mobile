# VN Code — Master Roadmap
Updated: 2026-10-28. Status legend: DONE / IN PROGRESS / PENDING / PARKED.
Each DONE row carries commit + CI run. PASTE CHANGES in later reports.

## Chat rebuild (A / WP1-WP9)
| Package | Status | Commit / Run |
|---|---|---|
| A(a) token layer (single source + Compose + CSS) | DONE | 228350e/8df9ed9 GREEN 3377 |
| A(a) component library + ChatListRenderer interface | DONE | 4e535b7 GREEN 3378 |
| A(a) design gallery (dev-only, DEBUG-gated) | DONE | f69dc73 GREEN 3379 |
| A(b) ProviderAdapter seam + OpenAIAdapter (behavior-identical) | DONE | f1ba20c GREEN 3381 |
| A(b) GeminiAdapter (bounded thinking, thought-part filter) | DONE | 2bd5b92 + fix a7d4c33 GREEN 3383 |
| WP1 (c) read-only key/endpoint migration | DONE | pending push GREEN 3384 |
| WP2 (d-min) panel on engine, Gemini first | IN PROGRESS | this push |
| CHECKPOINT 1 (install target + batch sheet) | AFTER WP2 | - |
| WP3 providers (OpenAI, Anthropic, OpenRouter, DeepSeek/Groq/Mistral/xAI presets) | PENDING | - |
| WP4 custom endpoints (HF/Ollama/LM Studio as custom) | PENDING | custom headers PARKED |
| WP5 model catalog + capability data (feeds budget guard) | PENDING | - |
| WP6 multi-key, failover OFF by default, manual switch | PENDING | - |
| WP7 Settings screens + "Chat storage: N KB — Clear" | PENDING | - |
| CHECKPOINT 2 (install target + batch sheet WP1-WP7) | AFTER WP7 | - |
| WP8 (e) attachment pipeline (picker, 10 MB cap, staging, redaction) | PENDING | - |
| WP9 (g) feature-map items by tier after P0 | PENDING | - |

## Explorer/editor (advisor rulings 2026-10-28; P1+ starts AFTER CHECKPOINT 1)
| Phase | Status |
|---|---|
| P1 editor registry (behavior-identical) + Utf16 long-press residual fix (first commit) | PENDING |
| P2 Open With... + per-extension user association | PENDING |
| P3 binary read-only BANNER (no dialog) — "Binary content — read-only", Hex view / Enable editing | PENDING |
| P4 2 MB truncated read-only preview + "Load full file" (measured cap) | PENDING |
| P5 markdown preview | PENDING |
| P6 third-party viewer registration | PENDING |
| Source Control: Initialize-repository offer; Generate-commit-message returns with chat | PENDING |

## PARKED (never propose in a group batch; each needs its own go-decision)
- Link-tap work (Bug 2, D1-D3, redaction/rename checks)
- backup/restore credential-path hazard (restore over a working container loses .ssh/.gitconfig/.aws etc.)
- auto-backup design (docs/automatic-container-backup-design.md)
- U4 isolated-process PDF worker (PDF extraction being rebuilt in WP8)
- rea inert review before any extension
- mobile preview own build (lirobi notes)
- custom headers security review (WP4 dependency: near-credential values, redaction, Authorization collision)
- tool-registry source of truth
- S1-c warm-path re-enable (owner mobile-data)
- XG01-04 extension-architecture enablers (owner ruling 2026-09-26)
- docs: rebuild-plan.md v2 + ui-research.md (queued read-only)
