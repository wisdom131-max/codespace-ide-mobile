
## Items 35-37 (2026-11-12)

- **Item 35** (`364392a`): `ContextDigest` + `SessionHandoff` evidence. Extraction with provenance, no
  generated prose; `retrieve()` ranks evidence > prose, user > model, newer > older, bounded.
- **Item 36** (`364392a`, `71b4ea4`, `6db186f`): `ProviderRetry` turn-level policy; `ChatFailureAdvice`
  folds its verdict into the existing failure card (no second row, no automatic retry ladder).
- **Item 37** (`485fecc`): `ServiceApi` follows exactly one credential-free, https, GET-only redirect to a
  host the service names; `run_logs` in the GitHub catalogue. L20 resolved, stub-tested.
- Full detail: `docs/VN-CODE-COPILOT-FINAL-IMPLEMENTATION-2026-11-12.md`.
