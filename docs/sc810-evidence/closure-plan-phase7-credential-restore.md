# SC-810 — credential restore for the migrated account (2026-09-11)

## Authorization

The user directed: "Use option 1: restore the existing account's real password through launcher
control, without posting it here. Once set, confirm only that udasuAvuco shows
launchReady: true. ... Do not fabricate/reset the password or switch accounts." An intervening
message referenced a different account/email (`bigGreenMeat` / `AesthesiaAbattage92@outlook.com`)
that did not match the launcher's own records for that profile; this was flagged back to the user
before acting. The user then confirmed `uglyratman123@gmail.com` — which matches the migrated
account's email of record exactly (`b01c7f006bace664` / `udasuAvuco`) — resolving the
inconsistency. The credential value itself was supplied by the user directly in chat; it is not
reproduced here, was not stored in any evidence file, and was used only in a single direct API
call (below), never echoed back.

## Method: the real launcher-control API, not a direct database write

Found the exact application endpoint backing "Add a password to this account in launcher
control" (the `launchDisabledReason` text seen in every prior phase this session):
`PUT /api/v1/accounts/{hash}/secrets` (`mule-service/handlers/launcher_accounts.go`,
`UpdateAccountSecrets`, routed in `main.go:459`), which is revision-gated (optimistic
concurrency via `expected_revision`) and idempotency-keyed (`mutation_id`), and writes through
`h.db.UpdateLauncherAccountSecrets` — the same tested application code path a real launcher-
control UI action would use. Used this instead of a raw SQL `UPDATE`, consistent with this
session's established practice of preferring existing tested mechanisms over handwritten
mutations against shared data.

Steps:
1. `GET /api/v1/accounts/b01c7f006bace664` (authenticated, dev static token) to read the current
   `revision` (`1`) before mutating — required by the endpoint's concurrency check.
2. `PUT /api/v1/accounts/b01c7f006bace664/secrets` with `expected_revision: 1` and the
   user-supplied password value. Response: `{"revision":2,"success":true}` — the revision
   counter advanced by exactly one, confirming a single, uncontested write.

## Verification — read-only, through ChromaScape's own dispatch endpoint (not the mutating API)

`GET http://localhost:8195/api/debug/builder-goals/accounts`, filtered to `accountHash:
"b01c7f006bace664"`:

| Field | Before | After |
|---|---|---|
| `hasPassword` | `false` | **`true`** |
| `launchReady` | `false` | **`true`** |
| `launchDisabledReason` | `"Add a password to this account in launcher control"` | **`null`** |

No other field on this account changed (email, display name, account hash all identical to the
Phase 5 reconfirmation). No other account in the catalog was touched.

## Scope discipline

- Did not fabricate, reset, or randomize any password — used the exact value the user supplied.
- Did not switch accounts — the mutation targeted `b01c7f006bace664` only, matching the
  migrated identity this entire closure plan is about.
- Did not write the credential to any file, log, or evidence document, and it does not appear
  in this document or in the git history of this commit.

Phase 7 (genuine Fishing recording through the real LeeuwBot dispatch path, using canonical
hash `b01c7f006bace664`) can now proceed.
