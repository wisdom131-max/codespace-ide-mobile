# Project 1 - Research Improvements

Closes stage 1 of the eight-project programme. The Autonomous Research System already searched,
fetched, extracted and cited; nothing here replaced it. Four new pure-Kotlin components sit around
it, and `web_search` uses them.

## What each stage does

| Stage | Component | Behaviour |
| --- | --- | --- |
| 1.1 | `ResearchPlanner` | Collapses equivalent URLs (via `UrlTools.normalize`) and mirrored titles, caps a host at two slots while other hosts remain, derives follow-up queries only from question terms with no support in what was read, bounds rounds with `MAX_ROUNDS` |
| 1.2 | `SourceQuality` | Classifies primary / official / secondary / community / weak by structure, flags staleness only when the question needs current information, states the reason. Search orders by source kind before engine rank |
| 1.3 | `ContradictionScan` | Compares only same-subject, same-unit values and polarity; keeps both sides with sources and quotes; separates corroborated from single-source |
| 1.4 | `ResearchSynthesis` | Claim-level citations, four buckets, both sides of every dispute, targeted follow-ups, honest stop reason |
| 1.5 | `ResearchSynthesis.RecoveryKind` | Eight distinct statuses (no results, unavailable, fetch failed, blocked, parse failed, timeout, rate limited, budget exhausted), evidence retained when a later step fails |

## Stop reasons

`SUFFICIENT` is the only reason that may be described as a complete answer. `MAX_ROUNDS`,
`BUDGET_EXHAUSTED`, `FAILURES` and `PARTIAL` all render with "the evidence above is partial: state
what is missing and do not describe the research as complete".

Precedence: sufficiency, then a continuing run with gaps left, then a non-retriable failure, then
the round limit, then the budget, then plain partial.

## Bounds

24 sources in the ledger, 400-character excerpts, 4,000 characters of evidence text per source in a
brief, 12 sentences and 320 characters per sentence in the scanner, 200 claims, `MAX_ROUNDS` of 3,
and the caller's search budget. Rendering caps disputes at 4, claims at 6, unresolved points at 5.

## Limitations, stated plainly

- Claim extraction is lexical: a version or quantity bound to a named subject, plus negation
  polarity. It does not understand implication, so a positive claim and a negative one about the
  same thing are not paired into a dispute. That direction is deliberate: a missed conflict is
  recoverable, an invented one is not.
- Authority is a structural heuristic. It says what kind of source a page is; it does not verify
  the page is correct.
- Sufficiency needs two hosts plus an undisputed corroborated claim, or the caller saying so. A
  genuinely sufficient single primary source will read as PARTIAL and invite one more search.
- No Compose harness: the brief is verified as text, not as rendered UI.

## Tests

92 research tests (12 planner, 12 quality, 12 contradiction, 20 synthesis and recovery, plus the
existing research suite). Full suite 2392 tests, 1 failure: `BackupTarTest.failureMessageCarriesRealTotals`,
root-sandbox only, green in CI.
