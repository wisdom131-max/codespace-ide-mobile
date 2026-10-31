# Connectors: what exists, what it asks for, and the plan to finish the 22

Source-verified 2026-10-31 against `backend/src/connectors/` and
`android/app/src/main/java/com/codespace/ide/agent/AgentConnectorManager.kt`.
Nothing here is a device test.

## 1. The answer to "which ones still exist"

The connector system is **backend-owned**. `backend/src/connectors/connector-registry.ts` holds
the provider table (auth URLs, token URLs, default scopes, `apiBase`, the env var names that hold
the client id/secret), `connectors.service.ts` does the code-for-token exchange, the refresh, and
one generic proxy, and the app never sees a client secret. The app only knows a service **id**,
calls `/connectors/status`, opens the consent URL in a real browser tab, and reaches the provider
through `use_connector` -> backend `proxyCall`.

Twenty providers are registered. Of the 22 on the audit list, **11 exist and 11 do not**:

| # | Connector | Exists | Auth | Shipped scope | Admin needed? |
|---|---|---|---|---|---|
| 1 | Gmail | yes | oauth | `gmail.modify` + `userinfo.email` | No admin role; **restricted scope** (Google verification if public) |
| 2 | Google Calendar | yes (the 22nd row) | oauth | `calendar` | No |
| 3 | Google Drive | yes | oauth | `drive` (full user drive) | No admin role; full data access |
| 4 | Slack | yes | oauth | `chat:write,channels:read,users:read` | Workspace admin only if the workspace restricts app installs |
| 5 | GitHub | yes, **twice** | oauth | backend `repo read:user codespace`; app Device Flow for git | No. Two paths must be merged (see §4) |
| 6 | GitLab | yes | oauth | `api` (full API as the user) | No admin role; admin API reachable only if the user is an admin |
| 7 | Notion | yes | oauth | empty default (scopes set on the integration) | No. **Broken by the proxy** (see §3) |
| 8 | Linear | yes | oauth | `issues:create issues:read issues:update issues:delete` | No |
| 9 | Jira | yes | oauth (3LO) | `read:jira-user read:jira-work write:jira-work manage:jira-project offline_access` | `manage:jira-project` needs project admin; **broken by the proxy** (see §3) |
| 10 | Discord | yes | oauth | `identify email guilds guilds.members.read` | No |
| 11 | Sentry | yes | PAT | user-pasted token | No, unless the token is an org token |
| 12 | YouTube | **no** | would be oauth | — | Google verification for `youtube.upload`; Workspace admin can block |
| 13 | Dropbox | **no** | — | — | Team scopes need a Business **admin install** |
| 14 | LinkedIn | **no** | — | — | Marketing/org posting needs **page admin** + partner approval |
| 15 | TikTok | **no** | — | — | App **audit** for direct post; no admin role |
| 16 | Twitch | **no** | — | — | No; moderation scopes need broadcaster/mod role |
| 17 | Supabase | **no** connector (only per-project DB creds in `SecureTokenStore`) | would be PAT | — | Management API token is account-level |
| 18 | OneDrive | **no** | would be Graph | — | **Tenant admin consent** |
| 19 | PowerPoint (Graph) | **no** | would be Graph | — | **Tenant admin consent** |
| 20 | WhatsApp Cloud | **no** | — | — | **Meta Business admin**, phone number, permanent token |
| 21 | Telegram | **no** | would be PAT (bot token) | — | No. Easiest of the eleven |
| 22 | VidIQ | **no** | — | — | No public API. Needs a product decision, not credentials |

Also registered but not on the list: Figma, Canva, Hugging Face, Vercel, Cloudflare, PostHog,
Stripe, Railway, Render. They already follow the same pattern; the plan below must not break them.

## 2. Admin access: the honest answer

**No shipped connector requests a provider admin scope.** The pattern is user consent, one
person, one grant. What several of them request is *full access to that user's data*, which is
admin-equivalent in effect but needs nobody's approval to grant:

- `gdrive` = `drive`, the whole drive, not a folder scope.
- `gmail` = `gmail.modify`, read + modify + delete mail.
- `gitlab` = `api`, every API the user can reach.
- `jira` = `write:jira-work` + `manage:jira-project`.
- `huggingface` = `manage-repos write-discussions`.

So you do not need to bring an admin **for the eleven that exist**. You will need one, or an
admin action, in exactly four places:

1. **Microsoft (OneDrive + PowerPoint)**: one Azure app, and delegated Graph permissions need
   **tenant admin consent** unless your tenant allows user consent.
2. **Dropbox team features**: a team-scoped app is installed by a team admin.
3. **WhatsApp Cloud**: a Meta Business account admin creates the app, the phone number, and the
   permanent token.
4. **Slack**, only if the workspace blocks member app installs.

Google is the subtle one: `gmail.modify` and `drive` are **restricted scopes**. In "testing" mode
that is fine for the accounts you add as test users; publishing to other people triggers Google
verification (and for restricted scopes, a security assessment). YouTube upload is the same class.

## 3. What is actually broken today, independent of credentials

This is the part that matters most, because more credentials will not fix it.

`connectors.service.ts` `proxyCall` sends exactly one shape: `Authorization: Bearer <token>` plus
an optional JSON body, against `apiBase` + path. Three registered providers do not speak that
shape:

- **Notion**: requires a `Notion-Version` header on every call. Without it the API rejects the
  request, so Notion is connected-but-unusable.
- **Jira**: Atlassian 3LO tokens must be sent to `api.atlassian.com/ex/jira/{cloudId}/...`, and
  the cloudId comes from `/oauth/token/accessible-resources` after the callback. Today the
  registry points at `https://api.atlassian.com` with no cloudId, so every Jira call 404s.
- **Telegram** (when added): the bot token goes **in the URL path**, not in a header.

Beyond shape, two structural gaps:

- **No per-connector action surface.** `use_connector` takes a raw method + path, so the model has
  to guess endpoints from memory. That is why connector conversations feel unreliable rather than
  broken. VS Code-class behaviour is a small curated action table per provider (list messages,
  create issue, upload file), with the raw path as the escape hatch.
- **Two GitHub systems.** `AgentConnectorManager` explicitly excludes GitHub because the app's
  Device Flow already covers git. The backend registry also has a `github` entry with a different
  scope set. One of them should own the token.

## 4. Credential checklist (what to bring, and only when its wave starts)

Callback URL for every OAuth provider: the backend's `/connectors/callback` on its public base URL
(`PUBLIC_BASE_URL`), and that exact string must be whitelisted in the provider's app settings.

| Wave | Connector | What to bring | Env vars |
|---|---|---|---|
| 1 | Telegram | bot token from BotFather + a target chat id | PAT flow, no new env |
| 1 | Supabase | personal access token (management API) | PAT flow |
| 1 | Twitch | Twitch app client id + secret | `TWITCH_OAUTH_CLIENT_ID/SECRET` |
| 2 | YouTube | **reuse** the existing Google client; add the YouTube scopes; verification later | none new (`GOOGLE_OAUTH_*`) |
| 3 | OneDrive + PowerPoint | one Azure app: client id, secret, tenant id, admin consent | `MS_GRAPH_CLIENT_ID/SECRET/TENANT` |
| 4 | Dropbox | app key + secret (team app if team scopes) | `DROPBOX_OAUTH_CLIENT_ID/SECRET` |
| 4 | LinkedIn | app client id + secret, org page admin for org posting | `LINKEDIN_OAUTH_CLIENT_ID/SECRET` |
| 4 | TikTok | app key + secret, then app audit for posting | `TIKTOK_OAUTH_CLIENT_ID/SECRET` |
| 4 | WhatsApp Cloud | business account id, phone number id, permanent token, webhook verify token | `WHATSAPP_*` |
| — | VidIQ | nothing: no public API; decide to drop it | — |

## 5. Order of work

**Wave 0 — fix the plumbing before adding providers (no credentials needed).**
Per-provider request shaping in the registry (`headers`, `pathPrefix`, `tokenPlacement`), so Notion
and Jira start working and Telegram/WhatsApp become plug-in instead of special cases; a curated
action table per connector behind `use_connector`; one GitHub owner.

**Wave 1 — the two-token wave.** Telegram, Supabase, Twitch. No admin, no verification, small
apps, immediate proof that the plumbing changes work for a provider added after them.

**Wave 2 — Google family.** YouTube on the existing Google client. Ship to test users first;
verification is a separate calendar item, not a code item.

**Wave 3 — Microsoft.** OneDrive + PowerPoint on one Graph app. Both need tenant admin consent, so
they are one conversation with one admin, not two.

**Wave 4 — the approval-heavy four.** Dropbox, LinkedIn, TikTok, WhatsApp. Each can take days to
weeks of provider review, so they start last and run in parallel with everything else.

Rules that apply to every wave: a provider is "done" only when status, consent, refresh, disconnect
and at least three real actions work end to end; credentials land in backend env only, never in the
APK; and each provider's scopes are the minimum its action table needs, not the provider's "full"
scope because it was easier.
