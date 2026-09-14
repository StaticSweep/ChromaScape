# SC-810 remaining work plan (2026-09-11)

Status: **In Progress.** This plan covers everything left before SC-810 can be considered for
Done, in dependency order, with explicit gates called out. Nothing past step 2 executes without
the authorizations already required by the closure plan (`C:\Dev\SC810\package-20260910\README-evidence-index.md`
indexes everything proven so far).

## What is already proven (do not re-verify unless something changes)

- Implementation, packaging, signing, integration onto `sc-572-remediation`
  (`sc810-integration-20260911`, HEAD `c6dfbaaf15`), final artifact/bundle, running-artifact
  gate, packaged-JAR catalog-discovery fix — all evidenced under `docs/sc810-evidence/` in
  `ChromaScape-consolidation` (commit `751c5e12`), checksummed (`SHA256SUMS.txt`).
- LeeuwBot bridge and launcher backend (`192.168.1.6:8092`, `:8084`) are reachable.
- `CZC8518C27 (Beheerder)`, UUID `1d391d65-6c97-5b77-b676-964f157e8c73`, is the legitimate,
  currently-active, correctly-heartbeating machine identity (confirmed directly against the raw
  launcher endpoint, sub-10-second-old heartbeat). The `ikiddoi` registration is stale
  (last seen 2026-08-23) and should not be used. No launcher processes were started, stopped, or
  reconfigured to reach this state — it was already live.
- `catalog_state: DEGRADED` (schema/validation catalog defect, `schema_failed_count: 14`,
  `validation_error_count: 8`) is a separate, pre-existing, out-of-scope issue — documented,
  explicitly not being chased further per prior decision.

## Step 1 — Confirm dispatch-readiness through ChromaScape itself (read-only proof, no Fishing)

The raw launcher endpoint says online; ChromaScape's own `/api/debug/builder-goals/machines`
should now agree, but that hasn't been re-checked since the earlier (accurate-for-its-time,
now-stale) "offline" read. Before trusting dispatch-readiness for a real recording:

1. Relaunch the same isolated `ChromaScape-consolidation` instance (dedicated port/DB, same
   `local-development` config as the last check) — or, if still running from the last check, just
   re-query it.
2. `GET /api/debug/builder-goals/machines` — confirm `CZC8518C27 (Beheerder)` now shows
   `online: true`, `runnable: true`, `builderGoalRunnable: true`, `disabledReason: null`.
3. Do **not** call any launch/dispatch/publish endpoint yet. Read-only confirmation only.
4. Stop the ChromaScape instance again afterward (same clean-shutdown discipline as before);
   leave the dedicated Postgres container stopped, data preserved.
5. Record the result as `closure-plan-phase10b-dispatch-readiness-confirmed.md` (or a revision to
   the existing `closure-plan-phase10-...` file) under `docs/sc810-evidence/`.

If step 2 still shows offline/not-runnable despite the raw endpoint being online, stop and report
— that would be a genuine, newly-discovered ChromaScape-side bug, not the timing artifact already
diagnosed, and needs its own investigation before continuing.

## Gate — account-record migration (closure plan Phase 9)

**Still not authorized. Nothing below this line proceeds without it.** Per the closure plan's own
Phase 9 procedure:

1. Back up the affected account records first.
2. Confirm the old profile-derived hash (`3720a9a3b5c9f57b`) and the intended canonical
   AccountConnector hash (`b01c7f006bace664`).
3. Inventory every referencing table before mutating anything.
4. Perform the migration transactionally; verify referential integrity afterward.
5. Confirm the canonical account appears in the Builder Goal catalog and the old identity can no
   longer be selected accidentally.
6. Preserve rollback instructions and evidence; touch no unrelated account.

This is a real, irreversible-in-practice data mutation on shared state. It should not be
attempted from an inline "go ahead" the way the launcher read-only checks were — it needs an
explicit, unambiguous authorization naming this specific action, the same way starting the
launcher agent was separately and explicitly authorized earlier.

## Step 2 — Isolated ChromaScape acceptance environment (closure plan Phase 11)

Once the migration is authorized and done:

1. Use the `sc810-integration-20260911` lineage (or its final merged successor, if by then this
   branch has been integrated further — check before assuming it's still current).
2. Dedicated port + dedicated Postgres container (new, not `chromascape-postgres` or any other
   operator's container) — same discipline as every prior live check this session.
3. `.env.local` (or process env) pointed at the final Micro6 artifact path, the real machine UUID
   (`1d391d65-...`), the matching source verification key, separate metadata/content access
   tokens, and the same `local-development` source environment used throughout Phase 3-8.
4. Confirm on startup: Flyway validation succeeds, Hibernate validation succeeds, `/debugger`
   returns 200, source trust configuration accepted, no migration unexpectedly reapplied.

## Step 3 — Record a genuine Fishing run (closure plan Phase 12)

Through the real Builder Goal launcher, not simulated:

1. Select the migrated canonical account (post-migration).
2. Select `CZC8518C27` (Beheerder identity).
3. Select Fishing Training.
4. Confirm the executor advertises the final commit/build ID/artifact digest/snapshot matching
   what Phase 3-8 produced — if the integration branch has moved since, rebuild and re-sign first
   (the existing evidence already documents that this is required whenever the commit changes).
5. Publish and launch; capture the real run ID, attempt ID, session ID, recording ID, and
   correlation IDs. Let it run to completion or a valid recorded state. No synthetic run, no
   direct database insert.

## Step 4 — Verify atomic binding (closure plan Phase 13)

For the genuine run: confirm one transaction durably binds run ID, recording ID, task key,
repository/commit/tree/build ID, artifact digest, manifest hash, snapshot ID, environment, and
sets `verification_state`/`source_provenance` to `VERIFIED`. Verify exactly one manifest
reference and one run binding exist, the recording association is one-way and immutable, a
failure midway rolls back atomically, retrying identical evidence is idempotent, and any identity
mismatch fails closed.

## Step 5 — Verify live debugger content (closure plan Phase 14)

Using real metadata/content credentials: load the binding, list allowlisted files, open the
Fishing entry point and representative helpers, query symbol/byte-range metadata, read bytes from
ChromaScape, and compare every sampled byte sequence against the promoted bundle — recomputing
hashes, confirming sizes/ranges, confirming no filesystem path or raw upstream URL leaks, and
confirming audit events carry actor/purpose/environment/run/snapshot/file/result/correlation ID
with no secrets in the metadata.

## Step 6 — Final negative matrix (closure plan Phase 15)

Exercise both startup and live API boundaries for every case the closure plan lists (missing/
wrong metadata or content token, cross-environment request, missing audit context, unknown run/
binding, absolute path, traversal/encoded separators, unknown file ID, symlink/junction escape,
untrusted signing key, invalid signature, altered manifest/content object, mismatched JAR digest/
build ID/commit/tree, missing/pruned bundle, redacted/pruned persisted snapshot, raw
caller-supplied target URL, redirect/DNS-rebinding where applicable, rate/size limits). Several of
these (untrusted key, invalid signature, altered content, pruned bundle, application-level path
containment) are already proven at the `Registry`/`Catalog` mechanism level via
`Sc810RealBundleVerificationTest` — this step is about the *live, bound-run* path specifically,
which is a different, not-yet-exercised surface.

## Step 7 — Restart persistence of the real recorded run (closure plan Phase 16)

With the genuine run bound: record row identities/hashes, stop only the SC-810 ChromaScape
process (confirmed by full command-line inspection, as done every time this session), preserve
the database and bundle root, restart with identical config, reopen the same historical run, and
confirm identical binding/manifest/content/symbol/audit data, unchanged Flyway history, and no
duplicate rows. This supersedes the earlier schema-only restart-persistence check (Phase 9 of the
original plan), which explicitly did not cover actual recorded data.

## Step 8 — Cross-revision protection (closure plan Phase 17)

Requires two legitimate clean Micro6 revisions: bind run A to artifact/bundle A, build and sign
a distinct artifact/bundle B from a different clean revision, launch B, bind a new run B to
snapshot B, reopen historical run A and confirm it still shows snapshot A, attempt (and confirm
rejection of) binding run A to snapshot B, and confirm bundle promotion never rewrites historical
bindings.

## Step 9 — Documentation and Shortcut reconciliation (closure plan Phase 18)

Only after steps 1-8 have real evidence: update Shortcut #810 with the genuine run/recording IDs,
the final acceptance matrix, references to the Micro6/ChromaScape delivery commits, the
packaged-runtime `READY`-equivalent evidence, and the restart/cross-revision evidence. Correct
earlier comments with follow-ups rather than rewriting them. Continue using
`closure-plan-phaseN-*` naming and the evidence index for anything new, with checksums recorded
before any claim of closure. Do not mark Done while any row remains blocked.

## Step 10 — Felix reconciliation (closure plan Phase 19)

When Felix is reachable: search `shortcut:sc-810` and the pending record's `offline-sync:` tag
before creating anything, replay the committed outbox record, record actual Felix task/note IDs,
check only evidence-backed checklist items, advance to `in_review`/`done` only once genuinely
warranted, move the outbox record to `synced/sc-810/`, and post a Felix-sync marker to Shortcut
only after a real Felix transition succeeds. If Felix remains unreachable, SC-810 stays In
Progress regardless of how much else is done.

## Final completion gate (closure plan Phase 20)

Unchanged from the closure plan: Done requires every one of steps 1-10 above to have genuine,
non-fabricated evidence, no legacy source-manifest behavior remaining, no secrets or generated
artifacts committed, and remaining unrelated suite failures documented (never called green).
Until then: **SC-810 stays In Progress.**

## Immediate next action

Step 1 above (confirm dispatch-readiness through ChromaScape itself) is the only thing in this
plan that doesn't need a new authorization — it's a read-only continuation of the launcher
investigation already approved. Everything from the account-migration gate onward waits for
explicit, specific authorization before any action is taken.
