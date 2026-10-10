# SC-1991 completion audit and pushed remediation

SC-1991 remains In Progress. This audit supplements the approved whole-story
[implementation plan](xrsps-client-replay-and-ai-plan.md), not a completion certificate.
The earlier `SC-1991-durable-fishing-evidence.md` describes its historical Linux run;
its passing native-worker counts and worker pins do not describe this Windows verification.

## Implemented plan

Preserve the dirty canonical checkouts and reconcile PR62 against current ChromaScape
main in an isolated worktree. Retain the authenticated API and main's diagnostic
journal/lease/cursor behavior while wiring production execution to PostgreSQL and the
existing production-service worker. Scope session retry keys and reads to the owner,
require ownership of the scenario's curation, and retain owner identity on restore.
Keep legacy rows with no proven owner inaccessible; never invent a backfill.

Separate command-journal completion from recording execution. Finish only after every
pinned manifest action has an accepted receipt, matching digest and source order;
validate captured source timing when present. Advance the actual worker one tick at a
time through the recording end, committing at most 100 ticks per segment so pause and
cancel can take effect between segments. Report `taskVerified=false`: reaching the end
does not prove the user's task, real-client rendering, or certification. Preserve a
first mismatch, mark the durable lifecycle FAILED, and reject terminal journal extension.
Copy caller payloads before storing the immutable journal.

Correct lure Fishing's active observation using the supported interacting Fishing NPC
rather than net animation 621 alone. Retain actual production resource consumption and
XP. Launch test workers with Node and the tsx JS CLI on native Windows instead of a
Unix `.bin` shim. Add PostgreSQL/session boundary CI and retain its XML reports.

Excluded from this remediation: replacing the real client with a semantic viewer;
bypassing offline isolation; fabricating missing capture/world/quest state; changing
live-account authority; credentials; deployment; merging PRs; closing the whole story.

## Changed files

Canonical identity: `C:\Dev\IdeaProjects\ChromaScape`.

- `src\main\java\com\chromascape\web\replay\session\`: durable service,
  repository, session/document, worker, execution facade, authenticated controller,
  semantic payload and receipt types; main diagnostic runner/store restored.
- `src\main\resources\db\migration\V67__sc1991_session_ownership.sql`:
  nullable legacy owner and owner-scoped unique retry key (V66 comes from PR62).
- `src\test\java\com\chromascape\web\replay\session\`: new boundary and
  PostgreSQL ownership tests, updated native integration fixture, retained main tests.
- `.github\workflows\main.yml`: disposable PostgreSQL session regression job.
- This audit and the approved plan copy under `docs\builderflow\`.

Canonical xRSPS identity: `C:\Dev\IdeaProjects\xrsps-typescript`.

- `server\src\replay\headless-replay-context.ts`: lure active observation.
- `server\tests\headless-replay-context.test.ts`: real lure catch, XP and bait stop.
- `server\tests\authoritative-replay-kernel.test.ts`: native Windows test launch.

## Acceptance results

- Owner/retry separation, hidden foreign/missing/unowned rows, persisted lifecycle
  serialization and eight concurrent retries initializing once: passed in real
  disposable PostgreSQL.
- Pinned journal/receipt completeness, captured source timing, finish-to-end,
  idempotent finish, no task-success claim, bounded pause/cancel, terminal mismatch:
  passed at a mocked protocol boundary; these tests are not gameplay parity proof.
- Production lure Fishing start (animation 622), XP gain and stop on exhausted bait:
  passed in the real headless service context.
- Atomic session application, repeated restore with preserved outcomes, legacy replay,
  100-run determinism and non-certifiable worker refusal: passed in direct server
  tests. Two certified-worker/differential cases were skipped for non-certifiable inputs.
- No real-client, configured-provider AI, Windows isolated worker or live gameplay
  acceptance was run. No whole-story acceptance item is marked complete by this audit.

## Tests

Windows JDK17, isolated ChromaScape worktree, disposable postgres:16-alpine on
127.0.0.1:55439 (`test/test`, database `sc1991`), unique schemas removed by tests:

```powershell
$env:SC1991_TEST_JDBC_URL='jdbc:postgresql://127.0.0.1:55439/sc1991'
.\gradlew.bat spotlessApply replayTest --tests 'com.chromascape.web.replay.session.*' checkstyleMain -I C:\Dev\IdeaProjects\.codex-temp\sc1991-spotless.init.gradle --console=plain
```

71 discovered: 64 passed, zero failures/errors, 7 native integration tests skipped
without `SC1991_WORKER_ENTRYPOINT`. Scoped Spotless passed; Checkstyle succeeded with
4257 warning-level findings across the repository. Scoped diff checks passed.

xRSPS Node direct tests:

```powershell
node --import ./server/node_modules/tsx/dist/loader.mjs --test server/tests/authoritative-replay-kernel.test.ts server/tests/authoritative-replay-determinism.test.ts server/tests/headless-replay-context.test.ts server/tests/session-atomic-actions.test.ts
node server/node_modules/typescript/bin/tsc --noEmit -p server/tsconfig.quests.json
```

18 discovered: 16 passed, zero failed, 2 skipped; includes the 100-run determinism
case and two atomic session/restore cases. Type checking passed. Initial wider tests
found the Windows shim bug and missing collision assets. The shim was fixed; actual
existing collision assets were attached for test reads through an ignored
development junction, with no fabricated inventory. Dependencies/cache junctions are
test setup, not an approved isolated worker package. The generated kernel identity
is explicitly non-certifiable development metadata; no worker used a development
override. No certified packaging/parity claim follows from these direct tests.

## Remaining work

Push read-back confirmed ChromaScape `c294644be344a06f5770314a001f3e42baf0cdb2`
and xRSPS `190d0a49dd0b81fc4b230be1117e68236b68eae7` on the existing PR branches.
PR62 reports mergeable after reconciliation. GitHub runs
[38008874639](https://github.com/ikiddoi/ChromaScape/actions/runs/38008874639) and
[38008876375](https://github.com/ikiddoi/ChromaScape/actions/runs/38008876375) failed
before any job step executed. The check annotation states exactly:
"The job was not started because an Actions budget is preventing further use."
CI requires the repository owner's Actions budget to permit execution. No CI test
pass is claimed, and no spending/billing configuration was changed.

| Whole-story requirement | Remaining deliverable |
| --- | --- |
| One world and real client | Actual OsrsClient bootstrap and shared production synchronization at intermediate ticks; separate viewer gateway outside the offline worker |
| Controls | Viewer epochs, reconnect/backpressure, verified bounded seek; rendered pause/step/cancel evidence |
| Historical recording | Audit `recording_b83c37e5` original capture; banking/widgets/quantities, jewelry and exchange native support; preserve unresolved verdict |
| Fresh recording | `recording_c4caff1d` remains blocked: missing initial Fishing target and unconfirmed manual widget actions; a finalized video alone is insufficient |
| AI | Configured provider completes an observed-state task; saved decision/action journal reproduces results without new model calls |
| Parity/recovery | Headless versus rendered hashes and viewer noninterference; bounded durable recovery with deployed worker pins |
| Packaging/CI | Supported offline launch/package for Windows, staged real-worker CI/E2E and rollback demonstration; boundary CI is only partial coverage |
| PR ownership | Reconcile the alternative persistence design in draft PR64 before merging; do not install competing session repositories |
| Tracker | Restore canonical Felix project/task resolution before writing evidence or transitioning review/done |

The Windows production launch factory requires Linux bubblewrap isolation. This
remediation does not weaken that boundary or pretend the skipped tests ran.
Latest recording audit found 211 finalized snapshots, ticks20–230, four typed actions,
57.14% coverage and four gaps; eight catches/480XP are separate from replay eligibility.

## Risks

V67 deliberately hides legacy sessions lacking proven ownership. Migrate only a
compatible database after review. Restoring a fresh worker per operation is bounded
headless execution and may be expensive; it does not provide paced presentation.
Legacy manifests without source timing cannot establish exact captured timing.
Latest and historical missing evidence cannot be reconstructed from assumed state.
Existing live capture/navigation edits in dirty canonical checkouts remain separate
unless explicitly listed in a reviewed focused patch.

## Rollback

Revert the focused remediation commit and the durable PR as appropriate before
deployment; preserve source recordings and checkpoints. Do not automatically drop
owner identity or collapse keys: two owners can legitimately share a retry key after
V67. Any database downgrade needs a reviewed backup/migration plan. No live runtime
was changed in this verification.

## Felix and Shortcut

Existing task: `task_1791276721099_3yq900flj`, stable tag `shortcut:sc-1991`,
classification `change:multi-repo`. Last previously verified state: in_progress /
spec_ready; current state cannot be fetched. The configured MCP resolves to a remote
Felix server whose project list is empty and returns `Project not found` for canonical
`C:\Dev\IdeaProjects\ChromaScape` repeatedly. This is a resolution error, not a
transport outage. No duplicate task, alternate identity, waiver, outbox outage record,
note-write success or transition is claimed. Existing unrelated pending outbox records
were inventoried; replay cannot resolve their canonical project either.

Shortcut SC-1991 was fetched and is In Progress. Its existing planned/started markers
remain intact. No workflow transition or review/done marker is emitted without the
corresponding Felix transition. The approved plan remains the plan of record in the
previously linked Felix notes; this repository copy supplies review context while
resolution is unavailable.
