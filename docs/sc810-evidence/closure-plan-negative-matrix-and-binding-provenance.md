# SC-810 — live negative-security matrix, restart-persistence completion, and a source-binding provenance finding (2026-09-12)

This document covers three things: the live bound-run negative matrix (plan Step 6), the parts of
restart persistence (plan Step 7) the earlier proof did not cover, and a material finding about
source-binding provenance that came out of investigating the null `micro6RevisionId`.

**The finding in section 3 qualifies SC-810's central claim and should be read before the
acceptance narrative is treated as complete.**

---

## 1. Live bound-run negative matrix — 29 cases, all correct

Executed against the running, signed artifact (`04718fe3bf…` / `sha256:9c5fe625…`) while the
accepted Fishing run was live. The executor enforces these checks in a fixed precedence, confirmed
by reading `authorizeSourceRequest`: **method (405) → audit context (422) → token (401) →
environment (403) → rate limit (429)**, with selector syntax (`400`) applied after authorization.

### J. Audit context — 9/9, all `422 SOURCE_AUDIT_CONTEXT_INVALID`

| Case | Result |
|---|---|
| no audit headers at all | `422` |
| missing `X-Source-Actor` | `422` |
| missing `X-Source-Purpose` | `422` |
| missing `X-Correlation-ID` | `422` |
| actor containing a space | `422` |
| actor containing `../../etc/passwd` | `422` |
| actor 65 chars (one over the bound) | `422` |
| correlation id 65 chars | `422` |
| unrecognised purpose `admin-read` | `422` |

A partial context is rejected exactly like an absent one, so a caller cannot obtain an anonymous
or half-attributed read. The actor/purpose/correlation values are bounded safe tokens
(`[A-Za-z0-9][A-Za-z0-9._-]{0,63}`), so a path or control character cannot be smuggled into the
audit trail itself.

### K. Token — 4/4, all `401 SOURCE_ACCESS_DENIED`

Missing `Authorization`; wrong value; value without the `Bearer ` prefix; and a correct-prefix
truncation (`sc810phase4local`) — all refused. Comparison is constant-time
(`constantTimeEquals`).

### L. Environment — 3/3, all `403 SOURCE_ACCESS_DENIED`

Missing `X-Source-Environment`; `production` instead of `local-development`; and the case variant
`Local-Development`. The comparison is exact and case-sensitive, so a cross-environment read
cannot succeed by casing.

### M. Method — 3/3, all `405`

`POST` with fully valid credentials, `DELETE` with no credentials at all, and `PUT` on the file
endpoint. Method is checked **before** authorization, so the read-only guarantee does not depend
on authentication succeeding first.

### N. Path and selector containment — 7/7

| Case | Result |
|---|---|
| `%2Fetc%2Fpasswd` as file id | `400 SOURCE_MANIFEST_INVALID` |
| `C%3A%5CWindows%5Cwin.ini` as file id | `400` |
| `..%5C..%5Cbundle.json` (backslash traversal) | `400` |
| NUL byte in file id | `400` |
| lowercase task key `fishing_training` | `400` |
| `..%2F..%2Fbundle.json` as task key | `400` |
| unknown but well-formed task key `MINING_TRAINING` | `404 SOURCE_UNAVAILABLE` |

The last row matters for information disclosure: an unknown task key returns the *same*
`SOURCE_UNAVAILABLE` as an unavailable catalog, so the endpoint does not distinguish "this task
does not exist" from "this task is not currently servable".

### O. Rate and size limits — 3/3

| Case | Result |
|---|---|
| query string > 1024 chars | `400` — *"source query is too large"* |
| path segment > 512 chars | `400` — *"source resource identity is invalid"* |
| 140 requests in one 60-second window | **118 × `200`, then 22 × `429 SOURCE_RATE_LIMITED`** |

The rate-limit result is exact rather than approximate: the two oversize requests immediately
before the burst each consumed one slot of the 120/minute budget, leaving 118 successes, with the
first `429` landing on request #119. The limiter recovered to `200` on the next window, confirmed
after the burst.

### ChromaScape-side run lookup

`GET /api/debug/builder-goals/runs/buildergoal_000000000000` → `404` (*"Builder goal run not
found"*). A traversal-shaped run id (`..%2F..%2Fetc%2Fpasswd`) is rejected `400` by Tomcat before
reaching application code.

### Cases from the plan deliberately **not** executed live, with reasons

Honesty about coverage matters more than a full-looking table:

- **Symlink/junction escape, untrusted signing key, invalid signature, altered manifest or content
  object, pruned/missing bundle.** All require *mutating the signed bundle root* that the live,
  accepted run is currently bound to. Doing so would destroy the running evidence. These remain
  proven at the `Registry`/`Catalog` mechanism level by `Sc810RealBundleVerificationTest`, which
  the plan itself notes; they are **not** additionally proven on the live path.
- **Mismatched JAR digest / build id / commit / tree.** Requires launching a deliberately
  mismatched artifact. Covered separately by `phase-10-running-artifact-gate.md`.
- **Raw caller-supplied target URL, redirect / DNS rebinding.** **Not applicable to this
  surface**: the authoritative source endpoints accept no caller-supplied URL. The executor's only
  outbound target is its configured ChromaScape base URL, which is not caller-controlled.
- **Redacted/pruned persisted snapshot.** Not exercised; see section 3, which makes clear that no
  per-run snapshot is persisted in the first place.

---

## 2. Restart persistence — the parts the earlier proof did not cover

`closure-plan-restart-persistence.md` established Flyway immutability, run-record field stability
and manifest identity. The plan's Step 7 also asked for content, symbol and duplicate-row checks.
Completed now, against the same restarted instance:

| Check | Result |
|---|---|
| File content after restart | 20,492 bytes, `sha256:6fb83c3e1626…` — identical to the pre-restart capture |
| `ETag` header | `sha256:6fb83c3e1626…` — matches the signed manifest's declared `content_hash` |
| Symbol data | `200`; `task_key FISHING_TRAINING`, `source_snapshot_id srcsnap_1c1527f1843cf6e36d490e0c`, `file_id file_1a4614c082f9642c8c0cbb75f1fcf9a2`, `total 19` |
| Duplicate rows | `SELECT count(*) FROM patch_loop_iteration WHERE iteration_id='buildergoal_3958a3af'` → **1** |

The live run remained `RUNNING` with `current_task: Fish Training → ∞` throughout all 29 negative
cases and the 140-request rate burst.

---

## 3. Finding: the accepted run's source binding is **not persisted**

### What was investigated

Previous evidence recorded `micro6RevisionId: null` on the accepted run and flagged that
source-binding provenance was "inferred from `source_snapshot_id` rather than asserted". That
framing assumed `micro6RevisionId` was the binding field. It is not.

### `micro6RevisionId` is a patch-lineage field, not a source binding

`setMicro6RevisionId` has exactly two call sites in ChromaScape, both on the **patch** path:

- `BuilderGoalRunnerService:1919` — set from `micro6TaskSourcePatchService.applyPatch(...)`'s
  result, during patch application/recovery.
- `BuilderGoalRunnerService:2483` — set when *importing* a run that already carries a revision id.

Neither executes for an ordinary dispatch run that never patched task source. `micro6_revision_id`
is `NULL` for **all 8** rows in `patch_loop_iteration`, not just the accepted run. So its being
null is correct behaviour, not a defect — but it also means it can never serve as provenance for a
dispatch run.

### The binding is recorded nowhere in the database

A search of **every** `text`, `varchar`, `char`, `json` and `jsonb` column of every base table in
the `public` schema found **zero** occurrences of any of:

- the snapshot id `srcsnap_1c1527f1843cf6e36d490e0c`
- the artifact digest `9c5fe625ff57807f4d059afbe756e0727c2daf323707c8891b6f754f686b8319`
- the build id `04718fe3bf0f86a27f8eb9d18d769f721748727d`

**The search mechanism was positively controlled** before drawing this conclusion: the identical
query for `buildergoal_3958a3af`, `recording_2a98057f` and `649bd3950515bd2f` returned hits across
`patch_loop_iteration.iteration_id`, `.task_metadata_json`, `.run_recording_id`,
`.imported_payload_json`, `recording_sessions.recording_id` and `debug_sessions.recording_id` —
including inside JSON columns. The zero result is therefore a real absence, not a broken query.

Of 161 leaf fields on the persisted run record, only three are source-related: `sourceType:
"builder_goal"` (a category, not an identity), `micro6RevisionId: null`, and `accountSource:
"launcher"` (unrelated).

### What this means

The binding between run `buildergoal_3958a3af` and signed snapshot `srcsnap_1c1527f1843cf6e36d490e0c`
exists **only in live executor state** — `/health` and the manifest it serves. It is real while the
client runs, and this session verified it byte-for-byte against the signed bundle. But it is not
durable: once that client stops, **nothing in ChromaScape records which source snapshot or which
artifact any historical run executed against.**

Stated precisely, so this is neither overblown nor minimised:

- ✅ The run genuinely executed on the signed artifact, and the served source was byte-identical to
  the signed bundle. That was verified live and is not in question.
- ✅ The snapshots themselves are immutable, content-addressed and signature-verified.
- ❌ The *association* between a run and its snapshot is not persisted, so it cannot be
  reconstructed or audited after the fact from ChromaScape's records.

This bears directly on SC-810's title claim. "Immutable source snapshots" holds. "Runs are bound to
them" holds only *at execution time*, and only for as long as the executor is alive.

It also qualifies the earlier restart-persistence proof: that proof showed the run record survived
the restart unchanged — which is true, but what survived contains no source binding to begin with.
`closure-plan-restart-persistence.md` has been annotated with a pointer to this section rather than
being rewritten.

### Not fixed here

No schema or code change was made. Adding a persisted binding is a `change:data` +
`change:api` alteration to a shared table on a delivery whose evidence chain is currently frozen
around a live run; it needs its own planned change, not an opportunistic edit during verification.
Recorded as the primary outstanding gap.

---

## 4. Status after this document

| Plan step | State |
|---|---|
| Step 6 — negative matrix | Live surface complete (29 cases). Bundle-mutation cases remain mechanism-level only, by deliberate choice, with reasons recorded above. |
| Step 7 — restart persistence | **Complete** (this document closes the content/symbol/duplicate-row gaps). |
| Step 8 — cross-revision | API surface complete (`closure-plan-cross-revision-protection.md`). The plan's artifact-B half — build and sign a second revision, bind run B, confirm run A's binding is untouched — is **not** done, and section 3 shows why it cannot be done meaningfully yet: there is no persisted binding for a promotion to preserve or rewrite. |
| Source-binding provenance | **Open — see section 3.** |
| Signing key / `devtoken123` | Unchanged; both remain user decisions. |
