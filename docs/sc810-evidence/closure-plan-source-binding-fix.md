# SC-810 — source-binding gap closed (2026-09-12)

Closes the finding in section 3 of `closure-plan-negative-matrix-and-binding-provenance.md`: a
dispatched builder-goal run recorded **no** durable link to the source snapshot or artifact it
executed against, so once the executor stopped, that provenance was gone.

Commit: `f7700a81`.

## What the gap actually was

A wiring omission inside SC-810 itself, not a missing design.

`source_run_bindings` was created by SC-810's **own** migration,
`V21__sc810_source_provenance.sql`, and `SourceManifestBindingService` already performs the whole
security-critical path: signature verification, environment enforcement, availability checks,
content-addressed identity reuse detection, and idempotent binding that refuses to rebind a run to
a different identity. All of it worked.

It was simply never called from builder-goal dispatch. Only the AI-player stabilization flow
(`SourceRunOrchestrationService`) used it. The path SC-810's acceptance run actually took wrote no
binding at all.

The fix therefore routes builder-goal dispatch through that same verified path rather than adding a
second, parallel implementation of source verification.

## Changes

| File | Change |
|---|---|
| `BuilderGoalSourceBindingService` (new) | Fetch outside any transaction; verify, persist and bind inside one; retry an immutable-identity conflict exactly as `SourceRunOrchestrationService` does. |
| `BuilderGoalRunStateMutationService` | Binds once, at the moment execution is first confirmed. Idempotent via a non-null state check, so later status polls never rebind. |
| `V46__sc810_builder_goal_source_binding_state.sql` (new) | `source_binding_state` on `patch_loop_iteration`: `BOUND` / `NOT_CONFIGURED` / `FAILED`, CHECK-constrained and indexed. |
| `BuilderGoalRunnerService` | Exposes `sourceBindingState` and `sourceBinding` on the run API. |
| `PatchLoopIteration` | New field, plus javadoc recording that `micro6RevisionId` is patch lineage and **not** this run's source binding. |
| `SourceManifestBindingService` | `isManifestConflict` widened to public — a pure predicate over a throwable chain, granting no access to manifests, bindings or trust configuration. |

**Why the new column exists at all.** The binding row is the authoritative record. The column exists
so that the *absence* of a binding is explicit and queryable, rather than indistinguishable from a
run nobody ever checked — which is precisely the condition that let this gap survive unnoticed.

**Historical rows are deliberately not backfilled.** No binding data exists for them. Writing a
plausible-looking value would be fabricated provenance, which is worse than a null.

## Two decisions that deserve review

**1. A binding failure does not fail the run.** By the time binding happens the client is already
executing. Aborting would convert a provenance-recording problem into lost work, and would make
ChromaScape's control path depend on the source endpoints being reachable. The failure is recorded
durably as `FAILED` instead — queryable, and impossible to mistake for a bound run. A test pins
this: on binding failure the run stays `RUNNING_SCRIPT` with `runSuccess` null.

**2. Binding is currently unpinned — stated plainly rather than glossed.** The Micro6 heartbeat
carries no artifact identity; `Micro6OnlineClientRegistryService.TargetClient` has no
`artifact_digest` or `build_id` field. So the binding attests **the signed source the executor
served**, not an independently corroborated artifact. That is genuinely weaker than the ideal.

The pinning path is implemented and activates the moment those fields appear in the heartbeat. What
was deliberately *not* done: echoing the manifest's own `build_id` / `artifact_digest` back as the
"expected" identity. That would be a self-consistent tautology that reads like verification in an
evidence document while proving nothing. A test (`doesNotFabricateAnExpectedIdentityWhenNothingWasAttested`)
asserts nulls are passed instead.

Closing this properly requires adding those fields to the heartbeat contract, which changes the
Micro6 artifact and therefore **re-issues the signed bundle that every existing piece of acceptance
evidence rests on**. That is a deliberate, separately-authorized step, not something to fold into a
verification pass.

**Transport note.** The manifest is read through `Micro6SourceManifestClient`, which by design
accepts a task key and no caller-supplied URL. Binding therefore targets the configured source
transport, so an instance dispatching to several distinct executors would need that transport
configured per executor. Recorded as a real limitation rather than worked around by passing a
run-supplied URL — which would reintroduce exactly the request-forgery surface that client avoids,
and which the plan's own negative matrix lists as a case to defend against.

## Verification

| Check | Result |
|---|---|
| New tests | 13 (6 service, 7 wiring), all passing |
| Builder-goal + patch-loop + startup suites | **788 tests, 0 failures** |
| SC-810 suite | 8 tests, 0 failures |
| V46 against a real Postgres 16 container | `Migrating schema … to version "46 - sc810 builder goal source binding state"` |
| Hibernate schema validation | `source_binding_state` present in full-context bootstrap |
| Spring context wiring | `CdiStartupRegressionTest` passes, confirming the optional constructor injection resolves |

What the tests pin, beyond the happy path: binding happens **only** after task start is confirmed;
exactly once across repeated status polls; `NOT_CONFIGURED` rather than a silent null when no binder
is present; `FAILED` without failing the run; `FAILED` when no task key is available; and no
fetch or write at all when trust configuration is incomplete.

## Not verified, and why

**No run has been bound against a live executor.** The SC-810 stack — ChromaScape on 8195, the
database container, and the game client — was stopped during cleanup before this change was written.
Proving the fix end to end means restarting all three and dispatching a fresh run, then confirming a
`source_run_bindings` row exists and survives the executor stopping. That is a live session of its
own, and this document does not claim it has happened.

Concretely, the outstanding live check is: dispatch a run, then confirm
`GET /api/debug/builder-goals/runs/{id}` returns `sourceBindingState: "BOUND"` with a populated
`sourceBinding`, and that the same row is still readable after the client process exits.

## Effect on the earlier finding

`closure-plan-negative-matrix-and-binding-provenance.md` section 3 stated: *"runs are bound to them"
holds only at execution time, and only for as long as the executor is alive.* That is now addressed
in code for newly dispatched runs, pending the live confirmation above. It remains true, and
permanently so, for every run already recorded — including the accepted Fishing run
`buildergoal_3958a3af`, which has no binding row and cannot be given one honestly.

Signing key remains the **development** key `sc810-dev-ed25519-611ab9dd95a518af`.
