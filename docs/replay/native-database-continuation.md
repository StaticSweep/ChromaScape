# Native checkpoint continuation through committed storage

## Verified authored lane, 16 October 2026

The actual lane passed all three variants through six fresh native processes:
initial stop, action success, and a running checkpoint at tick 1 that later fails
before the second source action. Restored/uninterrupted complete snapshots,
traces, native outcome evidence, Java chain hashes, event ordinals and committed
sample/event suffixes match. Finalized owner-scoped child branching/artifacts are
verified. The same invocation first repeated all nine persistence cases; every
case passed with zero failures/errors/skips. Frozen source hashes were unchanged,
and the exact random-owner-labelled PostgreSQL container was stopped and removed.

Actual commands, source hashes and XML:
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261016\native-db-continuation-current-result.json`.
The full parity record is beside it at
`native-db-continuation-current-output\native-database-continuation.json`.
Current schema: `sc276-current-v2`; runtime content:
`sha256:70db1bab5c42cc97ece5aa27066c81e209ffb41a841d021dcca44ba9c6ec3959`.

This qualifies this authored worker/storage join. It does not qualify public
recording admission, a new JVM, recovery/HTTP export, real-client Watch, complete
runtime fidelity or the production sandbox. The runtime remains non-certifiable,
`SYNTHETIC_CONTRACT`, and `controlAllowed=false`.

Run `gradlew.bat replayNativeDatabaseContinuationTest` explicitly. Ordinary
`test`, `replayTest` and the existing certification tasks do not select this
lane. Its exact method filter selects one tagged test, excluding the inherited
persistence tests. Missing prerequisites fail before fixture initialization.

The required environment is:

- `SC276_NATIVE_CHECKPOINT_JDBC_URL`: a newly owned disposable loopback
  `chromascape_aiplayer_dev` PostgreSQL database accepted by the strict schema
  target validator, using the fixture-only sc665 credentials.
- `SC276_NATIVE_DATABASE_INPUT`: an immutable authored three-variant bundle
  pinned to the actual native runtime/cache/content identity.
- `SC276_NATIVE_DATABASE_OUTPUT`: a new directory; existing output is refused.
- `SC274_NATIVE_REPLAY_ROOT`: the native runtime checkout containing
  `server/src/replay/worker-main.ts` and its installed dependencies.

The orchestrator must verify its PostgreSQL container's exact ID, random ownership
label and loopback port before supplying the URL, and stop only that owned ID in
`finally`. Shared application databases are never valid fixtures. Do not run a
schema-reset suite concurrently against this database. The separate
`replayNativeCheckpointPersistenceTest` lane remains available for its nine
schema, transaction, fencing and artifact cases.

The continuation test joins actual repositories, transaction proxies, artifact
files, finalization receipts and owner-scoped branching to six fresh native stdio
processes. The three variants cover a running checkpoint, an initial terminal
checkpoint and an action-terminal checkpoint. It compares resumed and
uninterrupted snapshots, traces, native outcome evidence and Java chain hashes;
checks persisted sample/event suffixes; refuses reset before restore or a new
gameplay step after a terminal restore; and checks finalized child artifact bytes.
Native requests retain the existing bounded stdout reader. The authored-lane
transport deadline is 180 seconds after a measured 63-65 second cold worker
startup on the 19 October host; runtime resource budgets and production limits
are unchanged. The earlier 60-second bound is retained in the 16 October
evidence as the result of that invocation only.

Inputs and database rows are explicitly `AUTHORED_TEST_ONLY`, `DRAFT`, `INCOMPLETE`
and `HELD_OUT`. This lane does not confer published compiler authority, repair
historical capture, qualify a fresh JVM or recovery/HTTP export, or certify the
production Linux sandbox. The Windows native transport is test-only. Runtime
fidelity and public admission checks remain independently enforced. Consult the
recorded task XML and evidence output for actual execution results; the presence
of the task or helper alone is not a passing result.
