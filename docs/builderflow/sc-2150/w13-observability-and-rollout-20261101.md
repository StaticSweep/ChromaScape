# SC-2150 — W13 observability completion and the 8090 rollout/rollback checklist, 2026-11-01

Code: `codex/sc-2150-w13-observability` (on `main` at `9bcf41a6`). Docs-only parts of this note
apply to any deployment.

## 1. What W13 already had

- `MouseVideoTelemetry` (`/observability` → `counters`): intake accepted/reused/refused, jobs
  claimed/succeeded/failed, cancellations requested/confirmed, reconciliations
  required/resolved, media refused, exports created, and refusals by error code.
- `MouseVideoReviewSessionService.counters()` (`/observability` → `reviewProtocol`): created,
  refused, conflicted, saved, retried, sealed, exposed, abandoned.
- `MouseVideoReadiness.report()` (`/capabilities` → `readiness`): configured → available →
  runnable → isolated → qualified for media import, cursor tracking, YouTube acquisition and the
  review protocol.
- Operator reconciliation (`POST /jobs/{id}/reconcile`) with a cleanup proof, and the in-JVM
  cancellation fix.

## 2. What this change adds

| Requirement | Where |
|---|---|
| Queue coverage (waiting work, including stalled queues) | `/observability` → `queue` |
| Provider/egress outcomes (acquisition failures never pass the request error handler, so they were invisible) | `/observability` → `counters.providerOutcomes` |

`queue` is computed from the job table with the **database** clock, so it is honest across
instances with skewed wall clocks:

```json
{"queued": 2, "running": 1, "cancelling": 0, "needsReconciliation": 0, "oldestQueuedSeconds": 13}
```

`providerOutcomes` uses a fixed vocabulary — `acquired`, `auth-required`, `rate-limited`,
`unavailable`, `limit`, `tool`, `interrupted`, `other` — and, like `refusalsByCode`, never carries
an actor, source, URL or provider message.

Coverage map for the W13 metric list:

| W13 metric | Source |
|---|---|
| session create / refuse / seal / abandon | `reviewProtocol.created/refused/sealed/abandoned` |
| exposure | `reviewProtocol.exposed` |
| retry / conflict | `reviewProtocol.retried`, `reviewProtocol.conflicted`, `counters.refusalsByCode.CONFLICT` |
| queue coverage | `queue` (this change) |
| export refusals | `counters.refusalsByCode` (export routes raise typed codes) |
| converter refusals | client-side `labels_from_review_export.py`; not a server metric |
| provider / egress outcomes | `counters.providerOutcomes` (this change) |
| containment reconciliation | `counters.reconciliationsRequired/Resolved`, `refusalsByCode` |

Nothing here is a new endpoint or a schema change; the shape of `/observability` only grows.

### Tests (clean worktree of `9bcf41a6` + this change)

| Check | Result |
|---|---|
| `test --tests '…tickdebugger.video.*'` | 338 tests, 1 failure (the known 90 s child timeout flake in `MouseVideoFrameIndexServiceTest`, passes on rerun), 48 database/opt-in skips |
| `MouseVideoTelemetryTest` | 3/3 |
| `MouseVideoJobServiceTest` | 34/34 |
| `MouseVideoImportServiceTest` | 11/11 |
| `MouseVideoControllerHttpTest` | 20/20 |
| `checkstyleMain`, `checkstyleTest` | pass |
| `spotlessCheck` on the touched files | pass (the task still fails on pre-existing files listed in `full-suite-dispositions-20261031.md`) |

## 3. Rollout and rollback for the live 8090 instance

Verified on `main` at `9bcf41a6`:

- The story ships exactly seven migrations, `V80`–`V86`.
  - `V80` only creates new `mouse_video_*` tables and their indexes.
  - `V81`–`V86` alter only those same `mouse_video_*` objects (constraints and columns added by
    this story). **No pre-existing table, column or row is touched.**
- `spring.flyway` on main: `baseline-on-migrate: true`, `baseline-version: 0`,
  `out-of-order: true`, default `validate-on-migrate: true`. The default validation set ignores
  only `*:future`.

Consequences:

1. Deploying is additive. If the feature is never used, the new tables stay empty.
2. Reverting the application **without** touching the database is expected to fail Flyway
   validation: the database will hold `V80`–`V86`, which the older code does not know
   (`*:missing` is not ignored by default). That must be confirmed against the real instance
   during the rollout, not assumed.
3. So rollback is one of:
   - **Preferred (lossless here):** restore the pre-deploy database snapshot, then run the older
     build. Nothing in SC-2150 is in use on 8090, so no story data is lost.
   - **Keep the new tables:** run the older build with
     `SPRING_FLYWAY_IGNORE_MIGRATION_PATTERNS='*:missing'` (or `*:future,version:80-86`), leaving
     the empty `mouse_video_*` tables in place.

Checklist:

1. Take a database snapshot before starting.
2. Deploy on the host that runs 8090, using `docs/CHROMASCAPE_RESTART_RUNBOOK.md`
   (Fedora: `scripts/start-fedora-dev.sh` from the repository root; Windows:
   `scripts\restart-chromascape.bat`). Do not redirect between hosts.
3. Confirm Flyway applied `V80`–`V86` and the application reached startup.
4. Verify `http://localhost:8090/debugger` loads, then
   `GET /api/debug/mouse-videos/capabilities` → `readiness` shows the real rungs for this host
   (`youtube-acquisition` should stay `configured: false`, and `media-import` only `configured`
   when the flag is on).
5. Verify `GET /api/debug/mouse-videos/observability` returns `queue` with zero counts and
   `counters.providerOutcomes` present.
6. If anything fails, restore the snapshot and start the previous build; record which rollback
   path was used and whether the `*:missing` override was needed.
