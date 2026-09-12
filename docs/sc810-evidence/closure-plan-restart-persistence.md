# SC-810 — restart-persistence proof (2026-09-12)

## What was being proved

That SC-810's durable state survives a full ChromaScape process restart: the schema is not
re-migrated or re-baselined, an in-flight run record is not lost or mutated, the client registry
recovers, and the authoritative source manifest served by the executor is still byte-identical to
the signed bundle afterwards.

The test is a **restart of the ChromaScape application process only**. The PostgreSQL container and
the live game client were deliberately left running, so the restart isolates ChromaScape's own
startup path (Flyway, registry rehydration, source-endpoint wiring) from any database or client
lifecycle event.

## Method

1. Captured baselines: Flyway history, database volume identity, the full run record for the
   in-flight Fishing run, and the live source manifest.
2. Stopped the ChromaScape JVM (PIDs `27808`, `84400`, `48412`); confirmed port 8195 free, the
   Postgres container untouched (`Up 8 hours`), and the game client (PID `81552`) still alive.
3. Restarted ChromaScape (new PID `143276`, log `chroma_sc810_restart2.log`).
4. Re-ran each baseline measurement and compared.

Restart timeline from the log (local time): Flyway validate `06:24:48.530`, Tomcat bound to 8195
`06:25:35.908`, application ready `06:25:35.991` (67.0 s startup). **0 `ERROR`-level lines**, 4
`WARN`.

## Result 1 — schema untouched, no re-migration

```
06:24:48.530 DbValidate - Successfully validated 48 migrations (execution time 00:00.288s)
06:24:48.650 DbMigrate  - Current version of schema "public": 45
06:24:48.660 DbMigrate  - Schema "public" is up to date. No migration necessary.
```

Full `flyway_schema_history` dump (`installed_rank, version, description, checksum, success`,
ordered by `installed_rank`) compared before and after:

| | Rows | SHA-256 of full history |
|---|---|---|
| before restart | 48 | `e726bffa2fdc28c19b952f57e2fb1e3838b5439543979943adda771ef6d5400e` |
| after restart | 48 | `e726bffa2fdc28c19b952f57e2fb1e3838b5439543979943adda771ef6d5400e` |

`cmp` reports no difference. No migration was re-applied, no checksum drifted, and no baseline row
was inserted. Database volume identity also unchanged:
`2e78236327f7395d97bdcda5616e8fbd262150b4c5e9298c7a6eb9a74d82dd2c -> /var/lib/postgresql/data`.

## Result 2 — the in-flight run record survived intact

`GET /api/debug/builder-goals/runs/buildergoal_3958a3af` → `HTTP 200` after restart. The response
was flattened to leaf fields and compared against the pre-restart capture:

**161 leaf fields before, 161 after. 159 identical. 2 changed.**

The two that changed are the liveness poll timestamps, which *must* advance for the record to be
live at all:

| Field | Before | After |
|---|---|---|
| `lastPollAt` | `2026-09-12T06:22:59.892795500` | `2026-09-12T06:27:12.609605300` |
| `lastSuccessfulPollAt` | `2026-09-12T06:22:59.892795500` | `2026-09-12T06:27:12.609605300` |

Every identity, provenance and settings field is unchanged:

| Field | Value (identical before and after) |
|---|---|
| `iterationId` | `buildergoal_3958a3af` |
| `status` | `RUNNING` |
| `taskKey` | `FISHING_TRAINING` |
| `runSessionId` | `debug_a3b56cb6f4b9479d82607762f43b6d29` |
| `runRecordingId` | `recording_2a98057f` |
| `taskStartedAt` | `2026-09-12T04:11:21.407Z` |
| `settingsSha256` | `sha256:ff833fbb4515dedc72be0279bbcdf29bc30002063cb469dc0a42511a7daf0a40` |
| `accountHash` | `649bd3950515bd2f` |
| `micro6RevisionId` | `null` (see *Caveats*) |

Canonical whole-document SHA-256 differs (`5db5dc0e…` → `8f6f3b35…`) **solely** because of those
two poll timestamps; that difference is expected and is recorded here rather than suppressed, so
the hash is not mistaken for evidence of immutability it does not provide. The immutability claim
rests on the field-level comparison above, not on the whole-document hash.

## Result 3 — source manifest still byte-identical to the signed bundle

`GET http://127.0.0.1:8098/api/v1/source-manifests/FISHING_TRAINING` re-fetched after the restart
with a complete audit context (`X-Source-Actor: sc810-acceptance`, `X-Source-Purpose: manifest-read`,
a fresh `X-Correlation-ID`, `X-Source-Environment: local-development`, metadata bearer token):

| Source | SHA-256 | Bytes |
|---|---|---|
| baseline (pre-restart, mid-run) | `10086419fce0d0c1903401874702ca2c56b2b0b2186d152836b7ac1ed9cb9820` | 833,754 |
| after restart | `10086419fce0d0c1903401874702ca2c56b2b0b2186d152836b7ac1ed9cb9820` | 833,754 |
| signed bundle on disk | `10086419fce0d0c1903401874702ca2c56b2b0b2186d152836b7ac1ed9cb9820` | 833,754 |

`cmp` against `bundles/srcbundle_7c5226066eb527205273c425/manifests/FISHING_TRAINING.json` reports
no difference.

## Result 4 — client registry recovered, and the run kept executing

`GET /api/debug/builder-goals/clients` after the restart:

```
clientId:             418a4f7d-be75-4d36-9a1d-f5a344e69958   (matches the run's targetClientId)
accountName:          TheSomaliMan
accountHash:          649bd3950515bd2f
currentWorld:         380        loginState: logged_in
scriptLifecycleState: RUNNING    dispatchReady: true    stale: false
launchAttemptId:      53b43f9d-6d6f-46d9-971e-e4d142448dc7
```

Executor `/health` shows the recovery happened through the heartbeat path fixed earlier this
session, not through any manual re-registration:

| Field | Value |
|---|---|
| `heartbeat_scheduled` / `heartbeat_enabled` | `true` / `true` |
| `heartbeat_last_attempt_at` | `2026-09-12T04:27:38.032457Z` |
| `heartbeat_last_success_at` | `2026-09-12T04:27:38.049398Z` |
| `heartbeat_last_status` | `success` |
| `heartbeat_consecutive_failures` | `0` |
| `chromascape_heartbeat_url` | `http://localhost:8195` |
| `current_task` | `Fish Training → ∞` |
| `build_id` / `artifact_digest` | `04718fe3bf…` / `sha256:9c5fe625…` |
| `source_snapshot_id` | `srcsnap_1c1527f1843cf6e36d490e0c` |

The last heartbeat success (`04:27:38Z` = `06:27:38` local) is **after** the restart completed
(`06:25:35` local), so the client genuinely re-registered with the newly started instance. The
client kept running the Fishing task throughout — `current_task` is unchanged and the run is still
`RUNNING`.

> **Later correction (same day).** The run record survived this restart unchanged — but subsequent
> investigation established that what survived **contains no source binding at all**: neither the
> snapshot id, nor the artifact digest, nor the build id is persisted anywhere in the database. See
> section 3 of `closure-plan-negative-matrix-and-binding-provenance.md`. The persistence result
> below stands exactly as measured; its *significance* is narrower than it first appears, because
> the record whose stability was proved never carried the binding in the first place. The
> content/symbol/duplicate-row checks that this document left open are also completed there.

## Caveats — what this does *not* prove

- **Client registration is re-established, not persisted.** The registry entry came back because
  the client heartbeated into the restarted instance. This proof does not show the registry
  surviving a restart with the client absent; that is a different property and was not tested.
  `heartbeat_consecutive_failures: 0` reflects the counter *after* a success reset it — it is not
  evidence that no heartbeat failed during the ~4 minutes ChromaScape was down.
- **`micro6RevisionId` is still `null`** on this run, before and after. Source-binding provenance
  therefore remains inferred from `source_snapshot_id` rather than asserted explicitly. The restart
  preserved the field faithfully, including its emptiness; it did not improve it.
- **Only the ChromaScape process was restarted.** Postgres and the game client were not, by design.
  A database-container restart and a client restart are separate, untested cases.
- The signing key remains the **development** Ed25519 key `sc810-dev-ed25519-611ab9dd95a518af`.

## Environment

| | |
|---|---|
| ChromaScape (isolated) | port `8195`, PID `143276` |
| Executor | `http://127.0.0.1:8098` |
| Database | container `chromascape-sc810-p9-postgres`, `Up 8 hours`, not restarted |
| Canonical ChromaScape | port `8090`, PID `94364` — **untouched throughout** |
