# P8 - the redirect leak, and the shared note field

Two weaknesses in the header work I shipped an hour earlier. Both were found by auditing it rather
than by a test, which is the point of this pass.

## 1. `HttpURLConnection` re-sends request properties on redirect

Following a redirect automatically re-applies the connection's request properties to the **new** URL.
A header supplied for `api.example.com` therefore arrives at whatever host a 302 names. Nothing in the
earlier policy touched this: it checked the names, not the destination.

**Fixed** in `fetchOnce`: when the caller supplied headers, redirects are followed manually, up to
`MAX_FETCH_REDIRECTS = 4`, and `HeaderPolicy.redirectKeepsCustomHeaders` decides whether they survive
the hop. They survive only on the **same host over https**. Otherwise the whole custom set is dropped,
the hop continues without them, and the result says so. Without custom headers the platform's own
redirect handling is kept, because there is nothing to leak and it is faster.

## 2. The note was a shared mutable field

`pendingHeaderNote` was a `@Volatile var` on the shared `AgentTools` object, consumed lazily by
`webFetch`. Three ways that goes wrong:

- a non-2xx result returned before the note was read, leaving it to attach to the **next** fetch;
- a nested fetch (Reddit's JSON attempt) could consume the outer call's note;
- parallel callers would overwrite each other.

**Fixed**: the note travels on the `Fetched` value (`note: String?`) and is prepended to the result
that produced it. There is no shared state left to go stale.

## Tests

12 tests in `HeaderPolicyTest` (8 from the earlier commit, 4 new): same-host https keeps them,
a cross-host hop drops them, a look-alike host such as `api.example.com.evil.example` drops them, an
https to http downgrade drops them, and an unparseable target keeps nothing.

Full suite 2518 tests, one known `BackupTarTest` root-sandbox failure (green in CI).

## Unavoidable limitation, stated

Custom headers are still sent on the **first** request the caller asked for, whatever that host is.
That is the feature, not a defect: the user chose the URL. What the code now guarantees is that the
headers cannot travel further than that host's own https hops.
