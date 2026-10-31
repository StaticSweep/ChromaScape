# SC-2150 — R27 assembler/plane remediation and Fedora cleanup fix (implementation evidence)

Date: 2026-10-27. Project: `C:\Dev\IdeaProjects\ChromaScape`.
Felix task: `task_1792382767281_cd8dbu69` (`shortcut:sc-2150`, `change:ui`).
Shortcut: https://app.shortcut.com/leeuwbot/story/2150 (still In Progress; Windows stays in SC-2204).

This note records the implementation and the tests actually run for the two reproducible review
defects (R27-1, R27-2), the diagnosed preview-cleanup race (F2/F3/F9), and the real 60-label
mechanics flow on the working tree. It does not close the remaining Fedora packages or the
per-checkbox obligations in `fedora-remaining-implementation-plan-20261026.md`.

## Changed files

| File | Change |
|---|---|
| `src\main\java\...\video\service\MouseVideoReviewSessionService.java` | Identical creation retry now uses the shared plane-aware `row`/`get` projection instead of `select *` + `view`, so the persisted coordinate-plane document and digest survive the retry (R27-2). |
| `src\main\java\...\video\service\MouseVideoProcessRunner.java` | Added `containmentMembership(...)` and classified a disappearing/collected scope cgroup (`NoSuchFileException`) and a `deactivating` scope without a cgroup as empty; `close()` waits a bounded time for the stop to settle. Removes a false `CLEANUP_UNCONFIRMED` after an ordinary short preview. |
| `scripts\sc2150\assemble_label_set.py` | Requires a frozen `sc2150-fa12-derivation/v1` manifest plus its out-of-band SHA-256. Binds every input's document hash, label-index hash, count and immutable identities to it; validates state/origin agreement, visibility/drop consistency, finite PTS and output-plane-bounded coordinates; refuses duplicate sessions and empty sealed sessions. |
| `scripts\sc2150\labels_from_review_export.py` | Added `derivation_entry(...)` and `--derivation-out` so a conversion can emit its frozen derivation manifest beside the v3 labels. |
| `scripts\sc2150\qualify_review_sessions.mjs` | Preview assertions use a bounded `SC2150_PREVIEW_TIMEOUT_MS` (5–300 s) instead of the 5 s expect default, poll the preview row and fail immediately with preview/job diagnostics on a terminal error. |
| `src\test\java\...\MouseVideoReviewSessionPostgresTest.java` | New real-PostgreSQL retry regression comparing the full first/retry acknowledgement (including plane and digest), row count, revision state and later resume. |
| `src\test\java\...\service\MouseVideoProcessRunnerTest.java` | New deterministic membership-interpretation regression for inactive/`deactivating`/collected-cgroup/unreadable-cgroup/live-member states. |
| `src\test\python\test_sc2150_assemble_label_set.py` | Rewritten around genuine converter v3 output and frozen derivation entries; adds edited-label, removed-label, empty-session, out-of-bounds, non-finite, state/origin, malformed-range, duplicate-session, projection and untrusted-manifest refusals. |

## Tests

All commands below were run from the repository root on Fedora. Java tests used the JDK 21
launcher and the Java 17 toolchain, `JAVA_TOOL_OPTIONS=-Dapi.version=1.44` for Docker API
compatibility, and
`-Psc967VectorsPath=/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/contracts/sc967/ed25519-vectors.json`.

### Python

`python3 -m unittest discover -s src/test/python -p 'test_*.py'`

- **91 passed, 0 failures** (was 87; the assembler suite grew from 7 to 11 tests).
- `test_sc2150_assemble_label_set`: **11 passed**.

### JavaScript

`npm test`

- **370 passed, 0 failures, 0 skipped.**

### Java video package

`./gradlew test --tests 'com.chromascape.web.debug.tickdebugger.video.*'`

- **303 tests, 302 passed, 1 skipped, 0 failures/errors.**
- The only skip is `MouseVideoCursorCorpusHarnessTest`, which requires the opt-in
  `SC2150_FA12_CORPUS`; it is not a pass.
- Includes **14/14** `MouseVideoReviewSessionPostgresTest` (real PostgreSQL via Testcontainers),
  **10/10** `MouseVideoProcessRunnerTest` and **2/2** `MouseVideoPreviewWorkerTest`, all with zero
  skips.

### Regression proof for R27-2

With the old retry branch restored, `identicalRetryPreservesPinnedPlaneAndImmutableContext` fails
at its full-acknowledgement assertion. With the fix, it passes. The new test also asserts a single
session row and a null latest revision after the retry, then resumes the same session and re-checks
the plane and digest.

### Real generated-fixture 60-label flow

Private Spring instance: `127.0.0.1:18190`, disposable PostgreSQL database
`sc2150_r27_20261027` on the `chromascape-postgres` container (5433), private media root
`build\sc2150-r27-20261027\video-store`, `local-development` profile (loopback principal), generated
`testsrc2` fixture (160×96, 30 fps, 5.9 s).

`SC2150_PREVIEW_TIMEOUT_MS=90000 node scripts/sc2150/qualify_review_sessions.mjs`

Result:

```json
{
  "denseLabels": 60,
  "reloadedLabels": 60,
  "sealed": true,
  "immutableExport": true,
  "convertedSchema": "sc2150-fa12-labels/v3",
  "predictionResponsesBeforeSeal": 0,
  "pageErrors": [],
  "fixtureOrigin": "GENERATED; synthetic protocol mechanics only, not held-out accuracy or human qualification"
}
```

Database job ledger after the run:

| kind | status | count |
|---|---|---|
| IMPORT | SUCCEEDED | 1 |
| PREVIEW | SUCCEEDED | 60 |

- `reconciliation privateReason` lines: **0**.
- `CLEANUP_UNCONFIRMED` lines: **0**.
- The previously failing frame-15 preview completed; all 60 preview jobs succeeded.

This is mechanics evidence only. The labels are 60 `MANUAL_HIDDEN` corrections from a generated
clip; it is not human-input, held-out accuracy or owner-truth evidence.

### Real sealed output through the hardened assembler

The sealed `mouse-video-review-export/v2` bytes were converted to v3, a derivation entry was frozen,
and the assembler was run with `--require-range 0:176`:

- Positive: **1 session, 60 labels, 0 visible / 60 absent / 0 unknown**.
- One label edited to `VISIBLE` with `x=1000000000, y=-3` but the original frozen manifest:
  refused — `label document differs from its frozen derivation (edited file)`.
- Same edit with a manifest refrozen around the edited projection: refused — `ordinal 0 cursor
  1000000000.0,-3.0 is outside the 160x96 output plane`.
- Labels deleted and the manifest refrozen: refused — `has no labels; a sealed session cannot be
  empty`.

## Diagnosis of the preview-cleanup blocker

The observed job (`35d0d78e-2c98-4ac0-a36c-c8e8ee95e522`) failed with
`CLEANUP_UNCONFIRMED` after five labels while its preview was pending. The containment probe had two
fail-closed races that produce exactly that message without a surviving child:

1. A scope in `deactivating` with an already empty/removed cgroup was treated as non-empty.
2. The `ControlGroup` path could disappear between the two `systemctl show` calls; the resulting
   `NoSuchFileException` was treated as non-empty instead of "no members".

`containmentMembership(...)` now treats a removed cgroup and a `deactivating` scope without a cgroup
as empty, while a live scope without a readable cgroup still fails closed. `close()` waits a bounded
time for the stop to settle. A 300-iteration fast-execution probe did not reproduce the race on the
old code, so this is a code-level race fix supported by deterministic state tests and by the clean
60-preview run; it is not claimed as a unique root-cause attribution for every possible host stall.

The unrelated Windows-only `OverlayBroadcaster` scheduler was disabled for the private run because it
logs a `user32` `UnsatisfiedLinkError` at 10 Hz on Linux. That is an existing environment issue, not
an SC-2150 change.

## Remaining work

1. Step 3's full host-boundary proof (owned scope/cgroup identity, descendants, control outcomes,
   drain completion, deadline) still needs the captured diagnostic run described in the plan.
2. Step 4's remaining matrix is still open: delayed nonce/POST/history, lost response with later
   edits, two sources/analyses/sessions, conflict/reload, remove-to-empty, 2,000/2,001 and
   encoded-byte limits, long-range paging/cancellation, sealed read-only across every path, and the
   registered anonymous/other-owner/actor/nonce/Origin matrix. The generated 60-label run does not
   substitute for those assertions.
3. Step 5 (frozen candidate hashes, full Java/native/replay/stabilization selections, formatting,
   corpus opt-in execution) and Step 6 (F4–F11) remain open. Owner labels, the fresh held-out
   top-up, the native capture and the product-acquisition inputs are still external.
4. `MouseVideoCursorCorpusHarnessTest` remains skipped until `SC2150_FA12_CORPUS` is supplied; no
   corpus/accuracy gate is passed here.

## Delivery note

`.gitignore` matches `/scripts/*/`, so `scripts\sc2150\assemble_label_set.py` and
`scripts\sc2150\qualify_review_sessions.mjs` are **not** included in a normal `git diff` or commit
(`scripts\sc2150\labels_from_review_export.py` is tracked). A feature delivery bundle must include
the ignored scripts explicitly (for example `git add -f`) or the R27-1 assembler fix and the preview
harness will be missing from the PR. This increment did not stage or commit anything.

## Evidence

Private receipts: `C:\Dev\IdeaProjects\ChromaScape\build\sc2150-r27-20261027\`

- `browser.log`, `result` fields in the run output, `media\labels.json`,
  `media\sealed-export.json`, `media\frame-index.json`, `corpus-policy.json`;
- `assembled.json`, `assembler-results.json`, tampered/empty label documents and derivations;
- `server.log.gz` plus `server-tail.log`;
- source hashes for the changed files were recorded in this session (the working tree is not
  committed).

Tracker state is unchanged: Felix `in_progress / spec_ready`, Shortcut In Progress. No completion
marker or transition is claimed by this note.
