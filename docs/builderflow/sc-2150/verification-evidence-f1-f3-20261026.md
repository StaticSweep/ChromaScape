# SC-2150 Verification Evidence — F1–F3 fixes (2026-10-26)

Project: `C:\Dev\IdeaProjects\ChromaScape`. Task: `task_1792382767281_cd8dbu69`.
Implementation evidence: `note_1792971454726_02d98dd9`.
Plan: `note_1792970439648_e46655f2`.

## Acceptance results

- F1 PASS for compatible saved review: real page creates a BLIND_UNVERIFIED label; actual Spring/PostgreSQL POST returns 201 with corrections/v2, history preserves the assertion, same request returns the same revision. Changed payload with the same UUID returns 409; an INDEPENDENT claim returns 400. New export pins the revision and reports independenceVerified=false; repeat/new and old downloads remain stable. Golden legacy corrections/v1 digest and legacy export-block shape tests pass.
- F2 PASS for uncertain write recovery in an open editor: controlled Chromium commit-with-lost-response regression sends byte-identical body/UUID/base/context first, then newer cumulative labels under a new UUID and advanced base. Existing snapshot-version acknowledgement/serialization is retained.
- F3 PASS for prevention of false certification: prediction exposure remains assisted after enabling Blind and reopening the source; no exposure record produces BLIND_UNVERIFIED only; unavailable browser storage fails conservatively. Actual converter allows development inspection with independence_verified=false, refuses held-out qualification, and emits no held-out output.
- Independent review qualification is NOT accepted. Trusted server sessions, manifest membership and exposure ledger remain required. CP25I5 is reopened for that complete obligation. No whole-story completion is claimed.

## Tests

367 JS tests, 38 SC-2150 Python tests and 37 focused Java tests passed; zero Java/JS failures or skips.
Real Chromium fault/overlay/storage scenarios and real Spring/PostgreSQL upload/index/preview/review/history/export scenarios passed. Converter exercised on the actual persisted export/review/frame index passed development conversion and refused held-out conversion.
Spotless passed in the private snapshot; scoped Checkstyle completed with 15 Javadoc warnings and no errors; owned-file whitespace/syntax checks passed.

Commands and reproducible scripts:
- `npm test` equivalent `node --test src/test/js/*.test.js`.
- `python3 -m unittest discover -s src/test/python -p 'test_sc2150*.py'`.
- Private Gradle snapshot: `test --tests '*MouseVideoCorrectionContractTest' --tests '*MouseVideoReviewServiceTest' --tests '*MouseVideoExportServiceTest' sc2150AuditClasspath spotlessJavaCheck`; launcher JDK21, compile/test toolchain17, explicit SC967 fixture and process-local Docker API compatibility setting.
- `node scripts\sc2150\qualify_review_protocol.mjs` from repository root.
- Disposable-server launcher plus `scripts\sc2150\qualify_review_backend.mjs` with loopback URL/evidence environment settings.
- Actual converter dev/heldout subprocess results: `build\sc2150-fixes-20261026\backend\converter-results.json`.

Evidence directory: `C:\Dev\IdeaProjects\ChromaScape\build\sc2150-fixes-20261026`.
No full Java/video regression run, PostgreSQL concurrency/race suite, real held-out scoring, YouTube acquisition, native/Windows qualification or release-host smoke was performed in this increment.

## Risks

- Blind is an unverified UI mode. Browser storage is not an authenticated exposure ledger; separate browsers/devices, cleared state or prior external viewing cannot establish independence.
- Held-out converter output is intentionally unavailable until trusted session evidence exists, including for legacy labels previously admitted by a command-line declaration. Existing export files and legacy retry identities are preserved.
- Pending request snapshots are held in the open editor; browser-process loss is not covered.
- The story retains its original open delivery obligations. Local WIP is not committed or published.

## Rollback

Preserve the previous implementation and user WIP when reverting these specific edits. There is no database migration. Existing v1 records/exports remain readable and byte-stable. V2 revisions/exports are immutable evidence and must not be rewritten or deleted; a client rollback must retain the v2 reader/validator or make review read-only until restored. Reverting the converter's fail-closed gate must not be used to claim independent qualification.

Disposable verification JVM stopped and dedicated database dropped. No normal application or shared database restart occurred.
