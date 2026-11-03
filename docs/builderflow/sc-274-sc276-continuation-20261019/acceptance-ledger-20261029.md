
## 2026-11-03 — provenance identity code increment (no row change)

No acceptance row changes state in this pass: it is code and tests only, and the capture that the
retained rows need has not been taken.

| Row | Status this pass | Note |
|---|---|---|
| 274-1, 274-2, 274-6, 274-7 | unchanged (PARTIAL, fixture-level) | Moved to the follow-up story under D1 = B; the v2 compiler still needs the causal capability before it emits any manifest |
| 274-3, 274-5 boundary, travel negative, historical | unchanged (CLOSED) | — |
| 274-4, 274-align, travel positive | unchanged (BLOCKED) | SC-2201 / SC-1498 |
| capture quality, output completeness | still pending | No capture since 31 October; Docker Desktop is stopped, so the SC-274 containers are down |
| `IMMUTABLE_PROVENANCE` cause | still unproven | The reuse guard now compares canonical capture identity instead of raw JSON trees, and both refusal sites log `differingFields`; the live cause is named by the next start, not by inspection |

Code: `331297cf` (tests + code) and `7d257581` (docs) on `codex/sc274-completion-20261015`, local,
not pushed. Focused provenance lane 31/31; full `replayTest` 871 tests with the 16 recorded
environmental failures plus 10 Docker-unavailable failures. Detail:
`implementation-evidence-20261103.md`, `verification-evidence-20261103.md`.
