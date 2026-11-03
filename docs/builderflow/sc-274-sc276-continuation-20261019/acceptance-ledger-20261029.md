
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

### Correction, 21:4xZ — runtime and delivery state after the 22:00Z run

- Docker is running; the SC-274 capture PostgreSQL (15474) and mule registry (8182) are healthy. The
  catalog-bridge container exited and is recreated against the live executor when needed.
- 8090 runs the **clean candidate jar** (`6622a23d`, PID 233944); the class overlay is retired.
- Lease `9a60481e…` / executor `f24979d6…` is live until `2026-11-03T23:28:09Z`, awaiting manual login.
- SC-274 text narrowed; follow-up **SC-2273** created and linked.
- **PR #108** open against `main`; the branch is pushed and unmerged.
- The last pending SC-274 outbox record is replayed (note `note_1793741389129_fc38f89c`) and moved to
  `synced/sc-274`; none remain pending.
- Rows unchanged: the Capture and Output rows still need the genuine recording.
