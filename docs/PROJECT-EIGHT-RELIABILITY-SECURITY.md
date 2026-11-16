---
title: Project Eight — Integration Reliability and Security
summary: Output bounding for tool exchanges, the credential audit of everything Stage 5.4 and 6.1 touched, and the list of security work still outstanding.
---

# Project Eight — Integration Reliability and Security

## Shipped in this pass

**Tool output bounding.** A single tool output can be megabytes (a whole file, a build log). Sending it
back verbatim on every later request is how one call quietly consumes a context window, and on a phone
it is also a memory cost. `ToolExchangeLog` now cuts an exchange's output at 4,000 characters and STATES
the cut in the text the model receives (`[truncated N chars]`). A silent omission would be a lie by
silence; this is not one. Two cases in `Stage54bProducerTest` cover the cut and the untouched path.

**Credential audit of everything this work touched.** Four separate checks were run over the Stage 5.4
and 6.1 diffs rather than asserted from memory:

1. The three Stage 5.4 commits and the 6.1 commit touch only Kotlin sources, tests and Markdown; a
   scanner for `ghp_`, `github_pat_`, `sk-live` and `AIza` patterns over the diff finds nothing.
2. The exchange log stores tool output in memory for the session only; it is never written to disk, so
   no credential can be persisted by it.
3. `origin` is a credential-free HTTPS URL and the `github.com`-scoped environment-reading helper is
   still the only auth path; no token fragment was printed at any point in this work.
4. The pre-existing Firebase client configuration was left undisturbed. It carries a client API key by
   design and a generic scanner flags it; changing it would break the app to satisfy a scan.

## Not started, listed so it is not implied

Full secret scanning in CI, dependency and supply-chain review, permission-model regression suite,
network-hardening review (cleartext and host binding), redaction coverage across every log sink,
side-effect verification on fresh state, and structured security sign-off. Project Eight is substantially
open.

## CI

Run `43892624026` (job `build`) succeeded on `52d264b`, which carries the output bounding and the audit.
