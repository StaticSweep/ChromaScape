# SC-810 — Phase 4/5: isolated instance startup and migrated-identity reconfirmation (2026-09-11)

## Phase 4 — isolated ChromaScape acceptance instance started

Started natively on Windows from `C:\Dev\IdeaProjects\ChromaScape-consolidation` via
`gradlew.bat --no-daemon bootRun`, log at `chroma_sc810_phase4_bootrun.log` (repo-root,
untracked/gitignored build output — not committed). Environment used the **corrected**
`CHROMASCAPE_SOURCE_ENVIRONMENT=local-development` (the user's Phase 3 instruction text said
`production`; this was flagged and corrected in the prior Phase 3/5-8 evidence and is repeated
here for the same reason — the bundle itself was generated with
`MICRO6_SOURCE_ENVIRONMENT=local-development`, so the runtime trust setting must match).

Verified:
- `Started ChromaScapeApplication in 22.64 seconds` (log line, `com.chromascape.web.ChromaScapeApplication`).
- `Tomcat started on port 8195 (http) with context path '/'`.
- Flyway: `Current version of schema "public": 45` / `Schema "public" is up to date. No migration
  necessary.` — the dedicated `chromascape-sc810-p9-postgres` container (started via
  `docker start`, previously `Exited (0)`, not recreated) retained its prior schema state.
- `GET /debugger` → 200.
- `GET /health` → 404. Checked against the boot log directly (`No static resource health`,
  `NoResourceFoundException`) — this route does not exist in this application at all; it is not
  a startup failure, just the wrong endpoint name to probe. `/debugger` and the
  `/api/debug/builder-goals/*` endpoints are this application's actual liveness/readiness
  surface, consistent with every prior phase this session.

## Phase 5 — migrated identity reconfirmed through ChromaScape's own dispatch path

`GET /api/debug/builder-goals/machines` (not just the raw launcher API): `CZC8518C27
(Beheerder)`, UUID `1d391d65-6c97-5b77-b676-964f157e8c73` — `online: true`, `runnable: true`,
`builderGoalRunnable: true`, `localMachine: true`, `disabledReason: null`. All `ikiddoi`-owned
machine registrations for the same/other hostnames correctly show `online: false`,
`runnable: false`, `disabledReason: "Launcher machine is offline"` — unchanged from Phase 9/10b.

`GET /api/debug/builder-goals/accounts` (the real dispatch-account-catalog endpoint, not a raw
DB query) — searched the full response text directly:
- `3720a9a3b5c9f57b` (old hash): **0 occurrences** anywhere in the catalog.
- `b01c7f006bace664` (new hash): occurs exactly twice (the `accountId` and `accountHash` fields
  of a single entry), confirming no duplicate/stray row.
- That entry: `"accountHash":"b01c7f006bace664","accountName":"udasuAvuco","displayName":
  "udasuAvuco","email":"uglyratman123@gmail.com","status":"available"`.

This proves the account-hash migration is visible and correct **through ChromaScape's own
dispatch surface**, not only via direct database inspection — satisfying the specific
instruction to reconfirm "through ChromaScape itself, not just the raw launcher."

## Blocker found: the migrated account has no launcher-managed password (pre-existing, not caused by the migration)

The same `/api/debug/builder-goals/accounts` entry for `b01c7f006bace664` also reports:
`"hasPassword": false, "launchReady": false, "launchDisabledReason": "Add a password to this
account in launcher control"`.

Verified this is real, not a stale-cache or metadata-sync artifact, by three independent checks:

1. **Direct database query** against `launcher_accounts` for `account_hash =
   'b01c7f006bace664'`: `has_password` (`password IS NOT NULL AND password <> ''`) = **false**.
2. **`launcher_account_secrets`** (the separate encrypted-credential table) has **0 rows** for
   this account hash — no fallback credential stored there either.
3. **Pre-migration backup CSV** (`launcher_accounts_old_20260911T190421Z.csv`, captured before
   today's migration touched anything) already shows `password=""`, `gmail_password=""`,
   `pin=""`, `totp_secret=""`, `session_data=null`, and `created_at = updated_at =
   2026-08-28 17:23:45` — i.e. this exact row was already in a credential-wiped state nearly two
   weeks before today's migration ran. **The migration did not cause this**; my migration only
   relabeled the `account_hash` column value on the existing row (verified in the account-migration
   evidence: a single-column `UPDATE ... SET account_hash`), and touched no other column.

Root-cause trace via `launcher_account_audit` (a separate, general-purpose audit table from the
migration-specific `launcher_account_hash_migration_audit` used in the account-migration
evidence): the password **did exist and was successfully revealed six times** between
2026-08-21 and 2026-08-22 under the old hash. A broad `update` operation at
`2026-08-22 16:09:46` rewrote many fields at once, including `account_hash` itself (a separate,
earlier, unrelated rename event — not this session's migration, and not to
`b01c7f006bace664`) alongside `email`, `notes`, `manual_status`, etc. `password` is not listed
in that operation's `changed_fields`, so the exact moment of credential loss cannot be pinned to
that single event from the audit trail alone — but the row was fully reset again on
2026-08-28 (per its `created_at`/`updated_at`), and has held blank credentials ever since,
independent of today's hash migration.

## Impact on the remaining closure plan

**Phase 7 (execute a genuine Fishing recording through the real LeeuwBot dispatch path) cannot
proceed** — there is no password for the launcher to authenticate this OSRS account with, and:

- I do not have and cannot recover the real account password (irreversible one-way hashing
  aside, this is simply data that was never given to me).
- Setting a new password/credential for this account is a live-credential mutation on a shared
  system, outside the scope of the explicit authorization already given (which covered only the
  `account_hash` relabel, not credential provisioning), and is exactly the class of action this
  session has consistently paused on rather than performed unilaterally.

This is being reported to the user as a blocking finding rather than routed around. Per the
user's own Definition of Done, SC-810 remains **In Progress** — Phases 1-6 (through migrated-
identity reconfirmation) are genuinely complete and evidenced; Phase 7 onward (genuine
recording, atomic binding, debugger byte verification, restart/cross-revision proofs) cannot
start until this credential gap is resolved by the user, or the user gives different direction
(e.g. use a different already-credentialed account for the live-recording proof, accept a
narrower closure scope, or supply/authorize setting the password).

Instance and dedicated Postgres container left running for now (isolated port 8195, dedicated
`chromascape-sc810-p9-postgres`); no shared LeeuwBot service was affected by any check in this
document (`/api/debug/builder-goals/*` and the direct read-only Postgres queries above are all
non-mutating).
