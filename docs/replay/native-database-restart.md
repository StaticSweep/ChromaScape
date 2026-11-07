# Native checkpoint continuation across Java processes

This is an explicit authored integration lane. It joins the production database,
artifact store, checkpoint/branch/finalization code, native xRSPS worker, expired
attempt recovery, report repair and HTTP export paths. It does not establish
genuine recording admission, the production authentication stack, the production
Linux sandbox, full game fidelity or browser Watch acceptance.

## Run the lane

Use a newly owned disposable PostgreSQL database and a new owned artifact
directory. Never supply the application's database or reset a shared schema.
Verify the database container's exact ID, random ownership labels and loopback
port before starting Gradle. Stop only that verified ID in `finally` and retain
test results even on failure. Do not run another schema/reset suite concurrently.

Set these prerequisites in the process environment:

| Variable | Required value |
| --- | --- |
| `SC276_NATIVE_CHECKPOINT_JDBC_URL` | Disposable loopback `chromascape_aiplayer_dev` database, `currentSchema=ai_player_dev`, fixture credentials accepted by the strict test target validator |
| `SC276_NATIVE_DATABASE_INPUT` | Immutable authored three-variant JSON bundle matching the native runtime/cache/content pins |
| `SC276_NATIVE_RESTART_ROOT` | New task-owned directory containing an `owner-token` file and an empty `artifacts` directory |
| `SC274_NATIVE_REPLAY_ROOT` | Native runtime checkout with `server/src/replay/worker-main.ts` and installed dependencies |

Run, in order, against the same owned database and artifact root:

```powershell
.\gradlew.bat replayNativeCheckpointPersistenceTest --rerun --no-daemon --max-workers=2 --console=plain
.\gradlew.bat replayNativeDatabasePrepareTest --rerun --no-daemon --max-workers=2 --console=plain
.\gradlew.bat replayNativeDatabaseResumeTest --rerun --no-daemon --max-workers=2 --console=plain
```

The two restart tasks select exactly one tagged method each and use separate
test JVMs. Missing prerequisites fail rather than skip. They are excluded from
ordinary test/replay discovery. The first task above separately checks the nine
persistence cases; its success is required by the owned verification orchestrator.
Each invocation/output root must be new. Preserve XML per invocation and reject
stale XML. Freeze source and input checksums before running, then compare them
afterward. The reference Windows orchestrator is retained at
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261017\native-db-restart-idle.cjs`;
it refuses to overwrite its evidence prefix. Copy it to a new prefix before a
new invocation, and review the candidate/runtime paths rather than assuming them.

## What is asserted

Prepare finalizes uninterrupted parents and commits native checkpoints and queued
children for initial-stop, action-success and running-tick-failure variants. It
records complete expected snapshots, traces, native outcome evidence, source
counts, Java hashes, event ordinals and database sample/event suffixes. It also
leaves three genuinely restored attempts unfinalized with inherited source
progress and zero local hash samples. A fourth separate authored fixture places
its first source action at tick two, accepts one idle native tick and commits one
hash sample with zero source actions. It does not bypass the source ordering guard
or mutate the immutable input bundle.

Resume requires a different JVM PID. It expires only the four exact owned attempt
IDs using the database clock, executes production recovery transactions, checks
lease fencing and source-action report counts, and repeats recovery/report repair
to prove idempotency. It restores the queued children through durable database and
artifact state with fresh native processes. Complete continued state and committed
suffixes must match uninterrupted execution. Restored terminal runs issue neither
reset nor gameplay steps. JSON evidence crossing persistence is compared as exact
canonical bytes, preserving numeric values and array order independently of Java
IntNode/LongNode representation.

Finally, an embedded test-owned Tomcat binds to `127.0.0.1` on an ephemeral port.
It uses the production export controller/service/repositories and downloads 23
artifacts: manifest/trace/hashes/report/outcome for three continued runs, plus
report/hashes for four recovered runs. Each endpoint must reject no principal
with 401 and a foreign owner with 404. Successful bytes and checksum headers are
verified and archived. The principal injector is test-only, so
`authenticationStackQualified=false`. The server is stopped in `finally`.

## Evidence and limits

Inspect `prepare/native-database-continuation.json`,
`resume/native-database-continuation.json`, `recovery.json`,
`http/http-export.json`, downloaded bytes and archived JUnit XML. A passing
combined invocation has 11 tests, zero failures/errors/skips, different prepare
and resume JVMs, three continued variants, four recoveries, 23 downloads, unchanged
frozen sources and confirmed owned cleanup. A partial or failed run is evidence
of that failure only.

Fixtures remain `AUTHORED_TEST_ONLY`, `DRAFT`, `INCOMPLETE` and `HELD_OUT`.
The Windows native stdio transport is test-only; production isolation remains
mandatory. The runtime remains `SYNTHETIC_CONTRACT`, `certifiable=false` and
`controlAllowed=false`. Consult the dated implementation and verification notes
for actual outcomes; the presence of this procedure is not a test pass.
