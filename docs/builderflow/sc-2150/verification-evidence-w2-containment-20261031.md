# SC-2150 — W2 containment and cleanup proof (Step 3), 2026-10-31

Project: `C:\Dev\IdeaProjects\ChromaScape`. Branch `codex/sc-2150-mouse-video-import` at
`b061209f` (runtime = compiled classes of that tree; no main source newer than the classes).
Plan: `remaining-plan-20261031.md` W2. Answers R28 review P2 (containment probe).

## Setup

- Disposable instance: `scripts/sc2150/start_verification.py`, port 18190, owner `sc2150-w2`,
  database `sc2150_w2_20261031` (ChromaScape Postgres 5433), private media root, YouTube off,
  restricted launcher on. The normal 8090 service was not touched.
- Fixtures: `scripts/sc2150/qualify_http.py` (generated CFR, VFR, burst VFR, rotated, encoded crop,
  bitstream crop) — **HTTP synthetic qualification PASS** on this candidate.
- Probe: `scripts/sc2150/containment_probe.py` (named `systemctl show` properties, READ_FAILED kept
  distinct from EMPTY, per-process kernel start-time identity).
- Receipts (not committed): `build/sc2150-w2-20261031/{http,containment,faults,instance}`.

## Acceptance results

### Normal preview jobs — 60 jobs, 6 fixtures × 10 fresh frame ordinals

| Check | Result |
|---|---|
| Job outcome | 60/60 `SUCCEEDED` |
| Verdict | 60/60 `CONTAINED` |
| Scopes | 120 (two per job); **120/120 sampled while live with member processes** |
| Members seen | `bwrap`, `python3`, `ffprobe`, `ffmpeg`; parent chain to the verification JVM |
| Labels | `MainPID` absent (systemd omits it for scopes) instead of the old memory-as-MainPID mislabel; `MemoryCurrent`/`TasksCurrent` correctly named |
| Final state | 120/120 `inactive` with the cgroup removed (`NO_CGROUP`; a cgroup with members cannot be removed) |
| Descendant death | **420 distinct job processes observed; 0 alive** afterwards with the same PID and kernel start time |
| Stray scopes | 0 `chromascape-video-*` units remain |

### Fault cases — 60 s generated fixture, `visual/v1` analysis, separate re-encoded variant each

| Case | Containment | Job record |
|---|---|---|
| Cancel mid-analysis (`POST /jobs/{id}/cancel`) | 3 processes seen, 0 survivors, scope removed | `CANCELLED` (truthful, immediate) |
| JVM `SIGKILL` mid-analysis | 3 seen, 0 survivors, scope removed (`bwrap --die-with-parent`) | after restart: `NEEDS_RECONCILIATION`, `INTERRUPTED` |
| Graceful `SIGTERM` mid-analysis | 2 seen, 0 survivors, scope removed | after restart: `NEEDS_RECONCILIATION`, `INTERRUPTED` |

No case reported false success and no case left a process or scope behind.

## Finding W2-F1 — graceful shutdown does not settle in-flight jobs

Job events (`mouse_video_job_events`) for both signal cases: `QUEUED → CLAIMED`, then nothing until
the next startup writes `RECONCILE_REQUIRED` (SIGTERM at 03:51:22 UTC, fenced at 03:51:55 on
restart). `MouseVideoWorkerRegistry.shutdownAll()` (`@PreDestroy`) only requests cancellation; no
step waits for owned workers to confirm empty containment and record an outcome before the context
and datasource close. Startup reconciliation then has no evidence and correctly fences the job.

Effect: every ordinary restart during a job leaves operator reconciliation work, although the
probe proves cleanup happened. For JVM death this is the correct, truthful outcome; for a graceful
shutdown it is avoidable.

Proposed fix (needs an owner decision on the settled state):

1. A `SmartLifecycle` bean with a phase that stops before the datasource/JPA beans: request
   cancellation, then wait up to a bounded budget (child shutdown budget + margin) for each owned
   worker to finish `terminate()` → `isEmpty()` and record its outcome.
2. Outcome when emptiness is confirmed: either `CANCELLED` with reason `SERVER_SHUTDOWN`, or re-queue
   (attempt + 1, new generation) so work resumes after restart. Unconfirmed emptiness or budget
   exhaustion keeps today's reconciliation path.
3. Regression: lifecycle test with a fake containment proving the job is settled before the
   repository closes; live rerun of the SIGTERM case expecting no `RECONCILE_REQUIRED`.

## Tests

| Check | Result |
|---|---|
| `python3 -m unittest discover -s src/test/python -p 'test_*.py'` | 111 passed |
| `npm test` | 371 passed |
| `qualify_http.py` against the instance | PASS |
| Containment probe, 60 jobs | 60/60 CONTAINED |
| Fault cases cancel / SIGKILL / SIGTERM | 0 survivors each; outcomes above |

Not run here: the Java video package and full suite (W4/W5), browser matrix (W3).

## Risks

- W2-F1 above (operator reconciliation after graceful restarts).
- Fast-parent-exit and session-changing descendants were not exercised as separate synthetic
  cases; the real tool chain (`bwrap --new-session` → tools) did change sessions and was contained.

## Rollback

Evidence-only run: stop the owned JVM (`build/sc2150-w2-20261031/instance/server.pid`), drop
database `sc2150_w2_20261031`, delete `build/sc2150-w2-20261031`. No product code changed in W2's
live part.
