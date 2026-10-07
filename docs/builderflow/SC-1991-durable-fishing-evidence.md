# SC-1991 durable authoritative Fishing slice — 7 October 2026

SC-1991 remains In Progress. This delivery establishes a durable headless recorded-action session through the real xRSPS production-service graph. It does not establish real-client rendering, AI task execution, or completion of the full eight-phase plan.

## Source and preservation

The original ChromaScape work was preserved in five local commits ending at `5a7c30f8`: session lifecycle/runner, combat capture, causal replay/compiler, executor/source routing, and debugger/build configuration. Meaningful untracked source/docs and binary patches were additionally backed up privately in the sibling `chromascape-checkpoint-20261007T201901Z` directory. Later concurrent edits in the original checkout were left untouched; machine settings, caches and the nested directory were not staged.

The delivery branch `codex/sc-1991-durable-fishing` starts from current main `4b2f1bd7`. Only the session baseline and this implementation were brought forward. The worker branch `codex/sc-1991-authoritative-session` starts from xRSPS main `cdaac366`, with implementation commit `19c707ab7d6ecd3f743d07bc8a8dc204997984a2`.

## Behavior

Spring's session API now uses PostgreSQL rather than the counter-only diagnostic store. V66 adds a session document containing lifecycle coordinates, an immutable journal prefix, receipts, first mismatch, pinned manifest and worker checkpoint. Database row locks serialize creation and operations across service instances. Checkpoint and receipt commit together; a failed transaction discards its private worker world, so retry restores only the last committed state.

Create requires a complete, gap-free, published authoritative scenario manifest UUID. Repeated identical creates return HTTP 200 and the existing session; the first returns 201. Recorded actions require an actual semantic payload with a matching canonical digest. A hash alone cannot execute. Supported actions come from the pinned worker, and AI mode is refused until implemented. Observations expose the real worker's inventory, XP and other canonical domain state, separately from session-relative tick/revision.

The additive `session.reset` / `session.apply` protocol drives the existing production-service graph. Atomic actions execute at the current tick; time advances through ordinary one-tick worker steps. Session reconstruction excludes a transport-only restore marker from the domain event log, allowing repeated checkpoint/restore with identical events and hashes. Ordinary headless reset/step/restore semantics stay intact. Global live-account `controlAllowed` stays false; no browser listener was added to the offline worker.

## Native verification

- ChromaScape JDK 17: 48 session tests, 0 failures/errors/skips. Six use real PostgreSQL and the real worker inside current main's bubblewrap offline isolation.
- Native tests prove Fishing awards items and XP, new-service recovery, duplicate retries without new effects, rollback before commit, durable first-mismatch/later-action suppression, concurrent creation with one initializer, HTTP create retry semantics, and rejection of hash-only or changed journal input.
- xRSPS session and legacy authoritative worker regression suites: 12 passed, 0 failed, 1 skipped. The skipped test exercises refusal of a non-certifiable worker; that condition is unreachable with this clean certified source snapshot.
- TypeScript type checking passes. Scoped Java formatting passes. `checkstyleMain` succeeds with the repository's warning-level findings.
- The staging helper was executed against a new destination and produced the same certifiable runtime identity. No sandbox bypass was used.

Worker identity:

- Build: `19c707ab7d6ecd3f743d07bc8a8dc204997984a2`
- Cache: `sha256:15dfb8861c95ed8747ed7aae612444cf779d662507bbf04978b9c5067eb82397`
- Content: `sha256:20c6934ae12434a1a78d4a749d1082389c155a0fabf442ffc90f211f10ec0755`

XML reports, command logs and capability evidence are archived privately under `chromascape-checkpoint-20261007T201901Z/sc1991-native-verification`. The live ChromaScape/Micro6 databases and accounts were not used. The PostgreSQL test database and generated test schemas are disposable.

## Reproduce

Stage a clean committed xRSPS checkout with its installed dependencies and native cache assets:

```bash
scripts/stage-sc1991-worker.sh ../xrsps-sc1991-session /absolute/path/to/new-worker-package
```

Use the package root for `REPLAY_XRSPS_WORKING_DIRECTORY` and its `server/src/replay/worker-launcher.js` for `REPLAY_XRSPS_ENTRYPOINT`. Do not point the sandbox at a development checkout with dependencies or assets symlinked outside its approved root.

Run the native integration suite with `SC1991_WORKER_ENTRYPOINT` set to that launcher and `SC1991_TEST_JDBC_URL` pointing to a disposable PostgreSQL database with test/test credentials. The suite creates and removes a unique test schema. Select JDK 17, then run:

```bash
bash gradlew --offline --no-daemon replayTest \
  --tests 'com.chromascape.web.replay.session.*' \
  -Psc967VectorsPath=../contracts/sc967/ed25519-vectors.json
```

Without the explicit worker environment, the six cross-repository integration tests are disabled; that run is not native gameplay proof.

## Remaining boundary

Worker reconstruction is bounded but intentionally reconstructs a fresh world at each operation; it is not paced real-time playback. Recorded runner completion means the submitted journal ended, not verified task success. Viewer epochs/resynchronization, actual-client bootstrap and production synchronization, verified seek, AI/provider ownership, multi-operator authorization, UI, release packaging and Windows demonstrations remain open.

`recording_b83c37e5` remains blocked by its historical capture/initial-world gaps. No actions were recovered, skipped or replaced to change its verdict. No live task was published and no runtime was restarted or deployed. After review, deployment must follow the native Fedora restart runbook and use a migration-compatible database; do not start this branch against an unrelated older schema.
