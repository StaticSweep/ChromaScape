# SC-810 — account-record migration (2026-09-11)

## Authorization

Executed only after the user sent the exact required authorization phrase verbatim in chat:
"I explicitly authorize the SC-810 account-record migration from `3720a9a3b5c9f57b` to
`b01c7f006bace664`, following the documented backup, transaction, validation, and rollback
procedure." No earlier phrasing (including quoting the required text back in a status report)
was treated as authorization.

## What the two hashes actually mean

Traced both algorithms to source before touching anything:
- `generateLegacyAccountHash` (mule-service, MD5): unrelated to either value here.
- `database.GenerateCanonicalAccountHash(seed) = sha256(lower(trim(seed)))[:16]`.
- `sha256("udasuavuco")[:16] = 3720a9a3b5c9f57b` — the launcher's stored `display_name`
  ("udasuAvuco"), i.e. the *profile-name-derived* hash.
- `b01c7f006bace664` is the *in-game-RSN-derived* hash the live Micro6 executor actually
  resolves (`client.getLocalPlayer().getName()`), per
  `docs/evidence/final-gap-closure/shortcut-migration-note.md` and `operational-acceptance.md`
  from an earlier session. The real character name was not needed or recovered (SHA-256 is
  one-way); only the launcher's stored `account_hash` field needed relabeling to match what the
  executor already reports.
- `RunCanonicalAccountHashMigration` (the existing bulk migration routine) computes its target
  hash from `display_name`/email and is therefore a **no-op** for this row —
  `GenerateCanonicalAccountHash("udasuAvuco")` already equals the old hash. It cannot perform
  this specific relabel; a different, explicit mechanism was required.

## Infrastructure prerequisite (unplanned): the leeuwbot-* backend had been fully removed

Before the migration could even be attempted, found the entire `leeuwbot-*` Docker stack
(launcher/mule backend, postgres, redis — 22 containers) had been completely removed from
Docker (not merely stopped — absent even from `docker ps -a`) by another operator, unrelated to
this session. Named volumes (`leeuwbotapi_lan_postgres_data`, `leeuwbotapi_lan_redis_data`)
had survived (confirming a plain `down`, not `down -v`). Brought the stack back with the exact
documented compose invocation only — `docker compose -p leeuwbotapi_lan -f docker-compose.local.yml up -d`
from `leeuwbotbackend/leeuwbot-api/leeuwbotapi` — explicitly *not* running the fuller
`start-dev-lan.sh` script, which does substantially more (kills anything on ~20 shared ports,
rebuilds the mule-service image, launches a second ChromaScape instance from a different,
non-canonical checkout). All 22 containers came up healthy, reattached to the existing data
volumes with no loss.

## Method: reused the tested internal migration functions, not handwritten SQL

Found `mule-service/internal/database/account_hash_migration.go` already implements exactly
the safety mechanics this migration needs (`discoverAccountHashTables`,
`rewriteAccountHashReferences`, `insertMigrationAuditRow`, `assertNoNonCanonicalLaunchableHashes`,
the same advisory lock `740551901` and `45s` statement timeout, and a per-table
`canonicalHashOwnedTablePolicies` allowlist distinguishing simple-update tables from
dedupe-by-hash tables). Rather than reimplementing this logic in raw SQL, added a temporary,
narrowly-scoped Go test (`sc810_manual_hash_migration_test.go`, same package, removed
immediately after use — never committed) that called these exact internal functions with the
explicit old/new hash pair, plus row-count assertions before commit. Ran via:
```
go test -run TestSC810ManualAccountHashMigration ./internal/database/... -v
```

## Pre-migration safety checks

- **Quiescence** (live-checked immediately before running): zero active `launcher_assignments`
  for either hash; both `builder_queues` rows `IDLE` with no `current_task_id`; the account's
  `launcher_accounts.status` was `available` (not running/claimed); the "live" `presence_heartbeats`
  under the new hash were in fact ~3 weeks stale (last at `2026-08-14T07:00:27Z`), confirming no
  concurrent writer.
- **Full dry-run inventory** across all 25 tables in `leeuwbot_db` with an `account_hash` column.
  In the migration's owned scope: `launcher_accounts` (1 row), `account_bank_data` (1 row, no
  collision), `builder_queues` (1 old row **plus a pre-existing row already under the new hash —
  a genuine collision**). Explicitly out of scope, left untouched, matching the app's own
  established policy (these are historical/audit logs, not identity state):
  `account_stats` (104 rows), `builder_commands` (824,185 rows), `builder_plans` (1),
  `builder_session_events` (2,559,089 rows), `builder_sessions` (49),
  `launcher_account_audit` (12), `presence_heartbeats` (6,156).
- **Backup**: every affected row copied to CSV before mutation, at
  `C:\Dev\SC810\session-scratch\account-migration-backup\`:
  `launcher_accounts_old_*.csv`, `account_bank_data_old_*.csv`,
  `builder_queues_both_*.csv` (both the old and pre-existing new row), and the 50 most recent
  pre-migration audit rows (`audit_table_before_*.csv`), timestamped `20260911T190421Z`.
- **Collision resolution confirmed in advance**: `builder_queues.account_hash` has a `UNIQUE`
  constraint, and the app's own `dedupeConflictingRowsByHash` policy (already applied to
  `builder_queues`) deletes the *old*-hash row when a *new*-hash row already exists — retaining
  `id=1` (already canonical, `created_at 2026-08-14`), deleting `id=2` (the stale duplicate).
  Foreign keys `launcher_account_aliases`/`launcher_account_secrets` → `launcher_accounts.account_hash`
  are `NOT DEFERRABLE` with no `ON UPDATE CASCADE`, but zero rows in either child table
  referenced this account, so the direct parent-row update carried no FK risk.

## Result — verified directly against the live database after the test committed

| Check | Expected | Actual |
|---|---|---|
| `launcher_accounts` old-hash rows | 0 | **0** |
| `launcher_accounts` new-hash rows | 1 | **1** |
| `account_bank_data` old-hash rows | 0 | **0** |
| `account_bank_data` new-hash rows | 1 | **1** |
| `builder_queues` old-hash rows | 0 | **0** |
| `builder_queues` new-hash rows | 1 | **1** (`id=1` retained, `id=2` removed) |
| Active-assignment collision | none | `launcher_assignments` returns 0 rows for either hash |
| Migration audit row | present | `id=1494`, action `rewritten_to_canonical`, `migrated_at 2026-09-11 20:01:49.218035+00` |
| Dedupe audit row | present | `id=1493`, action `dedupe_conflict_resolved`, same timestamp |

An older, unrelated `manual_rename_hash_fix` audit row (`id=781`, 2026-08-22, renaming this same
old hash to a *different* third hash `649bd3950515bd2f`) already existed in the audit history —
confirmed this was a prior, separate correction attempt with no bearing on the current state;
the live data was re-verified directly (table above), not inferred from audit history alone.

## Rollback

The CSV backups above are a full row-level record; combined with the specific `UPDATE`/`DELETE`
statements the migration performed (mirrored exactly in the removed test file, reproducible from
this document), a rollback would: re-insert the deleted `builder_queues` `id=2` row from
`builder_queues_both_*.csv`, revert `account_bank_data.account_hash` and
`launcher_accounts.account_hash` to `3720a9a3b5c9f57b` from the corresponding `*_old_*.csv`
files, and insert a `manual_rollback` audit row. Not performed — migration verified successful,
no rollback needed.

## Not yet done

Database-native backup (e.g. `pg_dump` of the full `leeuwbot_db`) beyond the row-level CSVs
above was not taken separately for this migration; the CSV backups plus the audit trail are the
durable record. Retention period for `C:\Dev\SC810\session-scratch\account-migration-backup\`
not yet agreed with the user.
