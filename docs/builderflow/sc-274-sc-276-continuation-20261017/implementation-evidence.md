# Implementation Evidence, SC-274 / SC-276, 17 October 2026

Canonical identities: C:\Dev\IdeaProjects\ChromaScape and C:\Dev\IdeaProjects\xrsps-typescript. Candidates: C:\Dev\IdeaProjects\.worktrees\sc274-completion and C:\Dev\IdeaProjects\.worktrees\sc276-completion. This note supplements the complete P1-P11 scope, exclusions, linked rules, Given/When/Then criteria and checklists in C:\Dev\IdeaProjects\.worktrees\sc274-completion\docs\builderflow\sc-274-sc-276-continuation-20261016\plan.md. No whole-story completion, merge, deployment or release is claimed.

## Implemented plan

1. Split authored native/database preparation and continuation into explicit tagged Gradle tasks using distinct JVMs and a shared owned artifact root. Commit parents/checkpoints/queued children and immutable uninterrupted expectations before process exit.
2. Use real native workers and production transactions to test durable continuation, four expired-attempt recoveries and idempotent report repair. Exercise the production export path over real loopback HTTP with a test-only principal boundary.
3. Retain failed evidence, freeze runtime/compiler inputs, verify owned teardown, document the reproducible lane and audit existing client/capture readiness. Leave whole-story gates open.

## Changed files

Relative to the compiler candidate:

- build.gradle.kts: separate replayNativeDatabasePrepareTest / replayNativeDatabaseResumeTest, exact tagged method filters, required prerequisites, ordering and exclusion from ordinary discovery.
- src\test\java\com\chromascape\web\replay\worker\NativeCheckpointPersistenceTest.java: test-only shared artifact root validated by owner-token/artifacts; restart fixture timeout 3600 seconds, lease 300 seconds. The ordinary lane retains its temporary artifact root.
- src\test\java\com\chromascape\web\replay\worker\NativeDatabaseContinuation.java: shared same-JVM/prepare/resume helpers; immutable expectations and distinct-PID check; real abandoned restores and idle native advance; exact canonical-byte outcome comparison. The idle fixture is an independently checksummed authored clone with first receipt at tick two; original input bytes remain unchanged.
- src\test\java\com\chromascape\web\replay\worker\NativeDatabaseRestartTest.java: the two selected tests and real production recovery beans.
- src\test\java\com\chromascape\web\replay\worker\NativeRestartRecovery.java: exact owned database-clock expiration, dead-attempt fence, source-action counts, repeated recovery and report repair.
- src\test\java\com\chromascape\web\replay\worker\NativeRestartHttpEvidence.java: owned loopback Tomcat and real export controller/service/repositories; checksum-verified downloads, principal/owner refusal and scoped cleanup.
- docs\replay\native-database-restart.md, this dated evidence directory and the existing continuation plan: procedure, implementation, acceptance, client integration assessment and current open gates.

No runtime production source changed in this increment. Extensive pre-existing dirty/untracked changes are preserved; these files must not be treated as wholly new disposable work. The local verification directory retains write-once orchestration, diagnostics, XML, source hashes and artifact bytes.

## Tests

| Check actually run | Result |
| --- | --- |
| replayNativeCheckpointPersistenceTest | 9 passed, zero failures/errors/skips |
| replayNativeDatabasePrepareTest | 1 passed; seven native processes prepare three parent/restore variants plus separate actionless advance |
| replayNativeDatabaseResumeTest | 1 passed; distinct JVM, four actual recoveries, three fresh native continuations, 23 successful HTTP downloads and 46 owner-boundary checks |
| Five-file scoped spotlessJavaApply / spotlessJavaCheck | Passed; native-restart-idle-fixture-format-check.log |
| Scoped tracked git diff --check | Passed; untracked Java files additionally covered by Spotless |
| Prior same-JVM regression this increment | Passed one test/three variants/six workers before the final canonical-byte correction and prepare-only idle fixture; not identical-source proof of this final run |
| Browser inventory attempts | Failed before inventory; no browser acceptance pass |

compileReplayTestJava succeeded as part of the actual final invocation. Prior 379 focused Java, 22 schema and runtime 264-pass/2-skip results remain scoped to their earlier recorded inputs, not rerun full-certification results.

Final invocation: 2026-10-16T23:00:11.350Z through 2026-10-16T23:08:19.762Z; JVMs 157276 / 217056; native processes 7 + 3. Frozen source records: 131; source changes zero. Exact random-owner-labelled container 55cc4c25b7050e2f252262d46ca0af2a70fbfd3837e45010ce18d77703435888 stopped successfully and was subsequently confirmed absent. No shared database mutation or application restart occurred.

Failed attempts are preserved: native-db-restart-first failed the second-JVM outcome comparison because Java IntNode/LongNode classes differed despite equal canonical values; native-db-restart-current rejected the added idle step because a source action was due at tick zero. The test helper now requires exact canonical bytes and derives a separately checksummed authored idle fixture. Production validation and ordering were not weakened. Neither failed overall invocation is counted as a pass.

- result: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261017\native-db-restart-idle-result.json`, SHA-256 `fb07245233a1541a1bedacc7daddaeb0499a479b3b40cd9ad6e2332baf4c7124`.
- prepare: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261017\native-db-restart-idle-owned\prepare\native-database-continuation.json`, SHA-256 `9e6149e7b75b2f9f94e15271406e15938366e4ea15679ba3dbaf4f3d5344cb1b`.
- resume: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261017\native-db-restart-idle-owned\resume\native-database-continuation.json`, SHA-256 `4955141e8b42047efc15940c24ba2ced07704f083aa88ca3f2151f201c57ab97`.
- recovery: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261017\native-db-restart-idle-owned\recovery.json`, SHA-256 `36e43bef15218cc4cc99f28092cf26cd8f039cc266ecc21af1b3772fb49a0414`.
- http: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261017\native-db-restart-idle-owned\http\http-export.json`, SHA-256 `a471f841ba97fd4976564e2d555f36ec438d6f19a6ab061c443da5a20dc23ea9`.

The first independent archive-verifier invocation stopped before writing evidence
because it expected bare SHA-256 hex while export headers use `sha256:` prefixes.
It was corrected to require that exact prefix/format and recompute each digest.
The final archive check passed without changing artifacts or test/application
source. Its diagnostic is `native-db-restart-archive-check-initial-failure.md` in
the local verification directory; this is not an additional JUnit pass or failure.

## Remaining work

The complete P1-P11 plan remains open. Select and qualify a genuine finalized immutable BehaviorRunContext/task/profile/graph and published curation; extend exact bank/travel/Fishing provenance without initial-state invention. recording_b83c37e5 retains its independent blocked verdict. Complete the native method/prerequisite/interruption/restore and independent full-server oracle matrix; executable/handler/ESM/filesystem closure, OS egress/resource/cancellation/parallel isolation; integrate the existing SC-1991 actual client and experimental LIVE_XRSPS lifecycle/security/reconnect/controls, regional/plane parity and genuine typed capture; then clean final pins, build/package/CI/privacy/performance/upgrade/rollback/release review.

Browser automation failed before window inventory with helper_sandbox_lock_failed / SetNamedSecurityInfoW sandbox dir failed: 5 for C:\Users\Beheerder\.codex\.sandbox-bin. No UI action or human Watch check occurred. The reuse assessment in client-integration-assessment.md records the missing current compiler integration and packaged-client manifest. No Watch session is ready for human sign-off.

Felix's configured http://127.0.0.1:4301/mcp failed twice at transport and failed again on continuation retry. SC-274 task task_1789960012362_td2qnusp remains last known in_review, current state unverified. SC-276 task resolution remains pending; no duplicate was created. Current Shortcut reads are SC-274 blocked/In Review and SC-276 blocked/To Do. The runtime project's sync mapping is absent. No Felix note/state/checklist write or Shortcut mutation succeeded; durable per-story planning/evidence/reconciliation operations remain in each canonical project's docs\builderflow\felix-sync\pending folder.
