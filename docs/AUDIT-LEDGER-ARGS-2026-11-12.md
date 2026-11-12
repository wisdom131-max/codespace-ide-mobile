# Audit find: the ledger traded memory for one comparison

The arguments fix (that terminal records keep `args`) is verified in `ToolDispatchGuard.kt:71` and
`ToolCallLedger.kt`, and `ToolReconciler` reads `prior.args["content"]` to prove a write landed. The
audit of that fix found the next problem in the same place.

## The problem

`args` were stored **verbatim**. A `write_file` argument is the entire file body. So:

- one entry could hold megabytes;
- the persisted ledger file grew with it, on every atomic write;
- 200 entries × a large body is real heap on a phone.

All of it for one comparison: *does the file already hold what was asked for?*

## The fix

`ArgFingerprint`:

- values up to `KEEP = 2,000` chars are kept whole, because paths, ids, commands and small bodies are
  what a human reads when a call is refused;
- longer values become `sha256:<hex>`;
- the map is capped at `MAX_KEYS = 12`, so a tool with many parameters cannot bloat the entry;
- `matches()` compares a fingerprinted value by hash and a short one exactly, and a missing stored
  value never matches, because absence is not evidence.

`ToolReconciler` compares through `ArgFingerprint.matches`, so fingerprinting cannot break the one
thing the arguments are for: proving an uncertain write already landed. Both sides use the same rule,
which is why this is a safe change rather than a behaviour change.

## Tests

`ArgFingerprintTest` (8): short values readable, a long body reduced to a fingerprint, that
fingerprint still proving the write landed, it rejecting different content, exact comparison for short
values, absence never matching, the map bound, and the value exactly at the boundary kept whole.

Full suite 2531 tests, one known `BackupTarTest` root-sandbox failure (green in CI).

## Trade-off, stated plainly

A refusal message lists a fingerprinted body as a hash, not the text. That is deliberate: the text is
in the file the reconciler just read, and copying it into a ledger entry on disk is the risk.
