# SC-2150 — rollout rehearsal against the live 8090 datasource, 2026-11-01

W13's remaining item: rehearse deploy and rollback on a disposable clone instead of assuming the
existing database accepts `main`. Two clone databases were used, both built from a snapshot, and
the live `chromascape` database was never written to.

## Inputs

| Item | Value |
|---|---|
| Live database | `chromascape` on `chromascape-postgres` (postgres:16-alpine, 127.0.0.1:5433) |
| Live schema | Flyway at **V36**; 102 tables; only `logs` (9,371), `task_recording_preferences` (12) and `debug_sessions` (2) hold rows |
| Snapshot | `build/db-snapshots/live-at-v36-20261101T152804Z.dump` (pg_dump custom format, 455 KB) |
| Rehearsal databases | `chromascape_rehearsal` (snapshot restored), `chromascape_fresh_rehearsal` (empty) |
| Runtime | clean worktree at `main` (`3aa43ecc`), port 18190, JDK 17 |

## Result 1 — `main` cannot migrate the existing database

`main` starts, runs Flyway validation, and fails before the context is usable:

```
Migration checksum mismatch for migration version 10, 21, 24, 31, 32, 33, 34, 35
Detected applied migration not resolved locally: 36
```

Cause, verified rather than inferred: the live schema was migrated by the checkout their launcher
points at, `wip/chromascape-checkpoint-20261007` (`480c0140`). For every failing version the two
branches hold different files, and `V36` exists only on that branch:

| Version | wip blob | main blob |
|---|---|---|
| V10 | `41536e62` | `54f3e9f8` |
| V21 | `5691ae33` | `4c458088` |
| V24 | `085c5f16` | `ab4c8ea5` |
| V31 | `fcdd27fc` | `d00db6f1` |
| V32 | `2cf3912d` | `ee539242` |
| V33 | `6dee9c6b` | `85eac606` |
| V34 | `32fe08d4` | `ee0cb1e2` |
| V35 | `3ebd3906` | `e224ae7f` |
| V36 | `8f8c2405` | absent |

So deploying `main` over that database is **not** a forward-only migration of SC-2150's seven
migrations; it is a schema takeover of a diverged branch's history. `flyway repair` would rewrite
the recorded checksums to `main`'s files without any evidence that the applied schema matches their
content, so it is not a safe substitute.

## Result 2 — `main` on an empty database is clean

Against `chromascape_fresh_rehearsal`:

- Flyway applied all **81** migrations; schema at **V86**.
- 14 `mouse_video%` objects exist (SC-2150's tables and their indexes).
- Application started in 29.2 s; `GET /debugger` → 200.
- `GET /api/debug/mouse-videos/capabilities` → 200 with the readiness ladder reporting honestly
  for this host: `media-import` configured/available/runnable/**isolated**, `cursor-tracking`,
  `youtube-acquisition` and `review-protocol` all `configured: false` (no template path, no
  downloader, no corpus manifest are configured here).
- `GET /api/debug/mouse-videos/observability` → 401 without a principal, as designed.

## Result 3 — a clean checkout needs two things that are not in the repository

1. `scripts/mcp-bridge/` — `McpBridgeLifecycleManager` requires it and fails startup with
   `Bridge directory not found`. The directory is **gitignored** (`.gitignore:83 /scripts/*/`) and
   tracked by nothing, so no clone or worktree can start the application without copying it in
   (39 MB). This is pre-existing and not SC-2150, but it blocks any clean-checkout deploy.
2. `CHROMASCAPE_SOURCE_*` settings — `SourceConfigurationValidator` refuses to start without
   `chromascape.source.public-key-base64`. On this host they come from `.env.local`; a runtime
   checkout needs the same values.

## Result 4 — defect found and fixed: the SC-2150 page route was never committed

`GET /mouse/video` returned **404** on a clean `main` build, while `templates/mouse-video.html`,
`static/js/mouse-video.js` and the whole controller surface were committed and the owner's
labelling guide links to `…/mouse/video?source=…`. The `@GetMapping("/mouse/video")` existed only
as an uncommitted edit in the shared checkout (`git diff -- src/main/java/com/chromascape/web/ServePages.java`),
next to three other mouse pages whose templates are not tracked either. `replay.html` on the old
branch links to the same route.

Fixed in #94: the mapping is committed with `ServePagesMouseVideoTest` (route resolves to the view,
template exists). Verified on the rehearsal instance afterwards: `GET /mouse/video` → **200** (12.5 KB,
review UI present: blind-mode and review-mode controls, `mouse-video.js`, `mouse-video-review-core.js`)
and `GET /mouse/video?source=df1833ad-…` (a held-out section from the owner guide) → **200**.

## Rollback — all three paths tested

1. **Snapshot restore works.** `pg_restore` of the snapshot into `chromascape_rehearsal`
   reproduced the live schema exactly (39 history rows, 102 tables).
2. **Reverting the code alone fails.** The `wip/chromascape-checkpoint-20261007` build started
   against the `main`-migrated database aborts with
   `Validate failed … Detected applied migration not resolved locally: 34.1, 39.1, 41, 42, 43 …`.
3. **`*:missing` does not help here.** `SPRING_FLYWAY_IGNORE_MIGRATION_PATTERNS='*:missing'` and
   `--spring.flyway.ignore-migration-patterns=*:missing` both still abort with the same validation
   error on Spring Boot 3.5.3 / Flyway 11.7.2, so the pattern cannot be relied on as an escape
   hatch.
4. **`--spring.flyway.validate-on-migrate=false` does start it.** With validation skipped the old
   build came up (Tomcat on 18192, application started in 27.8 s). That proves the startup barrier
   can be removed, not that the old code is correct against the newer schema — that would need its
   own functional pass.

Practical consequence: a rollback is a **snapshot restore plus the previous build**, not a code
revert over the migrated database.

## Consequences for the 8090 deploy

1. The existing `chromascape` database cannot carry `main`. It must be recreated (schema from
   `main`'s migrations) and the three populated tables decided on: `logs` (9,371 rows),
   `task_recording_preferences` (12 rows), `debug_sessions` (2 rows). Only the two small tables are
   worth carrying over; `logs` is history.
2. The launcher's `CHROMASCAPE_FEDORA_RUNTIME_PROJECT_DIR` in `.env.local` points 8090 at the
   `wip/chromascape-checkpoint-20261007` checkout; a deploy of `main` also means repointing it.
3. Feature flags must be set explicitly: `chromascape.mouse-video.enabled` defaults to false and
   nothing in `application*.yml` overrides it, so the SC-2150 surface stays dark until
   `CHROMASCAPE_MOUSE_VIDEO_ENABLED=true` is set (as `scripts/sc2150/start_verification.py` does
   for the 18191 instance).
