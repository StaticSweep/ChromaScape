# SC-2150 — Review of process, lease and timestamp fixes

Reviewed 2026-10-19 in `C:\Dev\IdeaProjects\ChromaScape`.
Felix task: `task_1792382767281_cd8dbu69`, actual state `in_progress / draft`.
Reviewed Implementation Evidence: `note_1792424108569_4668a957`.

Verdict: changes improve the earlier implementation, but the five-defect increment is not yet
fully qualified. Three remaining defects were reproduced against the actual compiled classes;
one additional concurrency defect follows from the current transaction/version strategy and
still needs a real-database regression. Full SC-2150 remains incomplete independently of these.

## Findings

### F1 — P1: Short-lived parents escape descendant cleanup

Location: `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoProcessRunner.java`, lines 88–111 and 151–193.

The runner samples `process.descendants()` before each 50 ms wait, then discovers descendants
again through the root during cleanup. A root that spawns a child and exits between samples
reparents that child before cleanup can discover it. `allStopped` checks only the dead root and
previously observed handles, so the runner returns success while an owned child is still alive.
This can release admission or report cancellation cleanup incorrectly; stdout drainage is not
a process-containment mechanism.

Reproduction: a disposable Java harness called the actual runner with budget 5 s and command
`python3 -c` whose script creates `subprocess.Popen(['sleep','30'], stdout=DEVNULL, stderr=DEVNULL)`,
writes that child's PID to a private temporary file, and immediately exits. On each of three
runs the result was `root exit=0 childAliveAfterConfirmedCleanup=true`. The harness explicitly
killed every child afterward. No unrelated process was signalled.

Required fix: use an execution-owned containment boundary (for the supported host, an appropriate
process group/cgroup/job object or equivalent verified launcher) that remains enumerable and
terminable after the original root exits. Do not claim confirmed cleanup from sampled handles
alone. Retain NEEDS_RECONCILIATION when containment cleanup cannot be established. Add fast-parent,
reparented-child and late-spawn tests, including cancellation and normal root exit.

### F2 — P1: Poller-fenced/reconciliation jobs do not stop the worker

Location: `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoLeaseHeartbeat.java`, lines 81–93; `MouseVideoJob.isSettled()` includes NEEDS_RECONCILIATION.

On renewal failure the heartbeat returns immediately for any `isSettled()` row, assuming
finalization won. This includes a row fenced into NEEDS_RECONCILIATION by the expired-claim
poller or by a cancellation handled on another instance. Neither `leaseLost` nor the worker's
cancellation token is set. An old tool execution can therefore keep running after loss of its
database lease. A different generation's terminal result also cannot prove this execution stopped.

Reproduction: a disposable service subclass made `heartbeat` throw a generation-conflict error
and `find` return a NEEDS_RECONCILIATION row. The actual scheduled heartbeat ran twice with a
one-second interval. Observed `renewAttempts=2 leaseLost=false cancelled=false`. This is a
controlled service fixture, not a PostgreSQL race test.

Required fix: distinguish this execution's successful settlement from ownership loss using
job/generation/lease/cancellation identity. Reconciliation, expiry, replacement generation and
unexpected terminal ownership must stop the old execution. Preserve the user-cancellation
distinction without treating every settled row as evidence of local cleanup. Add explicit
poller-fenced and remote-cancel fixtures plus real independent-instance lease tests.

### F3 — P1: Successful process exit can discard unread frame output

Location: `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoProcessRunner.java`, lines 108–117, 196–204 and 273–275; `MouseVideoDerivativeWorker.java`, line 198.

The runner closes stdout/stderr before waiting for drain completion. A drain can still be
processing its current buffer, so closing its stream discards the next unread buffer. The
resulting IOException is swallowed as normal EOF. The timed join also does not check whether
the drain is still alive. Result can therefore be exit=0, halted=false, truncated=false even
though callbacks missed output or are still running after return. The frame index then uses
`!halted && exitCode == 0` to label that partial list complete; its ArrayList can also remain
subject to drain-thread callbacks while the caller reads it.

Reproduction: actual runner, child command `python3 -c "print(chr(10).join(['frame=1']*2000))"`,
10-second budget, 100000-byte output cap, child-shutdown setting one second, and a line consumer
that counts each line and sleeps 2 ms. The child completed successfully. The runner returned
`actualAtReturn=972 exit=0 halted=false truncated=false` for 2000 emitted lines. After waiting
three more seconds the count stopped at 1024. The deliberate callback delay makes the drainage
race reproducible; the real index consumer is faster but correctness cannot depend on its timing.

Required fix: on normal root exit, drain through actual EOF and require completed readers before
returning immutable results. Bound drainage with its own deadline; if it cannot finish, mark the
result halted/incomplete or fail truthfully. Preserve explicit errors instead of treating forced
stream closure as successful EOF. Handle callback failures and prove no callbacks occur after
return. Add buffered multi-read output, delayed consumer and frame-index completeness tests.

### F4 — P2: Normal heartbeat/progress overlap can be mistaken for lease loss

Location: `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoJobService.java`, lines 121–161 and 371–381; `MouseVideoLeaseHeartbeat.java`, lines 74–93; `MouseVideoJob.java`, lines 101–103.

Heartbeat and progress both load through unlocked `findById` and save the same @Version entity.
The dedicated heartbeat introduces competing transactions within a healthy execution. If both
read version v, one commit advances it and the other fails optimistic locking. When heartbeat
loses, its error handler can observe an otherwise valid RUNNING job and set leaseLost/cancel
instead of recognizing or retrying a benign version collision. When progress loses, the import
can enter its generic failure path. A row lock used by finalization does not make the unlocked
heartbeat read/write atomic.

This finding is based on transaction and ORM source inspection; no real PostgreSQL reproduction
was run. Required fix: serialize same-job mutations or use atomic conditional SQL that distinguishes
actual fence loss from a retryable version collision. Recheck generation/owner/expiry under the
same concurrency boundary. Add barrier-controlled real-database heartbeat/progress/finalize tests.

## Improvements verified

- The original positional CSV key-frame/PTS error is replaced by named-field frame-index/v2
  parsing; the updated real-media test asserts actual timestamps, integer PTS and key flags.
- Periodic renewal is scheduled independently from the worker executor and tool stdout.
- Startup now locks candidates and explicitly preserves valid foreign running/cancelling leases
  in the covered fixtures. Real persistence-context/race behavior remains an unrun gate.
- Existing focused tests cover a silent deadline, cancellation of a still-parented child tree,
  flooded output and a stalled downloader. These pass but do not cover F1–F4.

## Changed files

This review changes no application or test source. It adds this review document and its linked
Felix documentation note. The implementation file inventory remains in Implementation Evidence
`note_1792424108569_4668a957` and
`C:\Dev\IdeaProjects\ChromaScape\docs\builderflow\sc-2150\implementation-evidence-process-and-timestamp-fixes.md`.

## Tests

Actually rerun during review:

- With JDK 17: `./gradlew test --rerun --tests 'com.chromascape.web.debug.tickdebugger.video.*' -Psc967VectorsPath=../contracts/sc967/ed25519-vectors.json --offline --console=plain` — BUILD SUCCESSFUL, 75 selected, 69 passed, 6 skipped, zero failures/errors.
- Six skips remain four migration and two PostgreSQL tests; they are not passing database gates.
- `node --test src/test/js/mouse-video.test.js` — 6 passed.
- `python3 -m unittest discover -s src/test/python -p 'test_mouse_video*.py'` — 2 passed.
- Disposable actual-class Java harnesses reproduced F1 three times, F2 with two scheduled renewals,
  and F3 with a successful child producing more than one drain buffer. Temporary harnesses were
  outside the repository; all intentionally spawned surviving children were explicitly stopped.
- `git diff --check` for the scoped video main/test paths — clean for tracked hunks; these video
  directories are currently untracked, so this command does not establish their whitespace status.

Initial Gradle invocation reported test UP-TO-DATE; the explicit `--rerun` invocation above actually
executed the suite. The first slow-consumer harness had an invalid Python string and was corrected;
only the corrected successful-child reproduction supports F3. An initial fixture compile picked
source jars and failed; excluding source jars produced the successful F2 reproduction.

Not rerun: Spotless/Checkstyle, real PostgreSQL integration, browser/active-content matrix,
release-host or public YouTube success, complete repository/native regression. Historical evidence
for these remains historical; this review does not promote it to a new pass.

## Remaining work

Correct F1–F4 and retain the missing regressions. Keep the existing complete plan and all original/
remediation/new acceptance obligations open until their actual evidence exists. Separate remaining
story work includes real DB concurrency, cursor-template/held-out qualification, correction/source-use
UI, successful pinned public import, browser/operator workflows and full regression/acceptance.

## Risks

False cleanup confirmation can leave tools alive after capacity is released. Failure to stop a
fenced execution wastes resources and prolongs unsafe workspace ownership. Discarded output can
be presented as complete frame evidence. Version collisions can fail healthy jobs. The passing
small-fixture suite does not disprove these independently reproduced edges.

## Rollback

No application edits or runtime changes were made by this review. Keep intake/provider flags off;
retain originals and immutable historical evidence. Use the existing completion plan's compatible
app/tool rollback and owned-job reconciliation instructions when implementing corrections.

## Tracker result

The existing task read-back is in_progress / draft. No Shortcut mutation or state-sync marker is
emitted by this review, and no feature enablement, deployment or restart is performed. All source
references in the Felix copy use canonical Windows paths.
