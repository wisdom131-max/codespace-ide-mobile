# P8 - Custom request headers, on a leash

`web_fetch` copied whatever header JSON it was handed straight onto `HttpURLConnection`. Three silent
failures lived in that one line.

| Failure | What actually happened |
| --- | --- |
| Transport-owned headers | `Host`, `Content-Length`, `Connection`, `Transfer-Encoding`, `Upgrade`, `Expect` are controlled by the platform; setting them either throws or corrupts the request |
| Credential collision | a caller `authorization` fought the token store's header, and which won depended on map iteration order |
| Leak | any error or notice that echoed the headers printed the credential in clear |

## The policy

`agent/net/HeaderPolicy.kt`, applied inside `fetchOnce` before a single property is set:

- **blacklist** (non-negotiable): `authorization`, `proxy-authorization`, `cookie`, `set-cookie`,
  `host`, `content-length`, `connection`, `keep-alive`, `transfer-encoding`, `upgrade`, `te`, `trailer`,
  `expect`, plus the `proxy-` and `sec-` prefixes;
- **collision rule**: a header supplied twice with different casing sends **neither** copy, because
  choosing one makes the outcome depend on map order;
- **redaction**: `redact` replaces the value of a credential-bearing name whole, and `describe` (the
  only way these headers become text) goes through it;
- **visibility**: rejected names are named in the tool result, so "my header had no effect" is a
  stated fact rather than a mystery. The notice never contains a value.

## Tests

8 tests in `HeaderPolicyTest`: the transport set, the credential set, ordinary headers surviving with
trimmed values, the casing collision, the rejection notice carrying names but not values, redaction,
`describe` never printing a secret, and a blank name being ignored rather than throwing.

Full suite 2514 tests, one known `BackupTarTest` root-sandbox failure (green in CI).

## Honest limitations

- The policy governs `web_fetch`'s caller-supplied headers. Connector and token-store requests build
  their own headers through `SecureTokenStore` and are out of this path by design.
- An ignored header is reported in the fetch result only when text is produced; a hard HTTP failure
  returns its status line first, and the note is cleared on the next fetch rather than stacked.
