# Verification Evidence, SC-274 / SC-276, 17 October 2026

Canonical identities: C:\Dev\IdeaProjects\ChromaScape and C:\Dev\IdeaProjects\xrsps-typescript. Candidates: C:\Dev\IdeaProjects\.worktrees\sc274-completion and C:\Dev\IdeaProjects\.worktrees\sc276-completion. This note supplements the complete P1-P11 scope, exclusions, linked rules, Given/When/Then criteria and checklists in C:\Dev\IdeaProjects\.worktrees\sc274-completion\docs\builderflow\sc-274-sc-276-continuation-20261016\plan.md. No whole-story completion, merge, deployment or release is claimed.

## Acceptance results

- Distinct-JVM storage/native continuation passed for initial stop, action success and a running checkpoint that fails before its next source action. Complete snapshots/traces/native outcome values, source counts, Java state/chain hashes, event ordinals and committed sample/event suffixes match uninterrupted execution. Terminal restore dispatches neither reset nor a gameplay step. Three finalized child artifact sets remain owner scoped.
- Four production recovery cases passed in the second JVM: three committed restores with zero local samples and inherited source progress, and one real actionless native step with one committed sample but zero actions. Reports count committed source actions; expired fences increment; repeated recovery and report repair are idempotent.
- 23 actual successful HTTP downloads and 46 refusal checks passed. Downloaded byte checksums were independently rechecked after the run. No principal receives 401; foreign owner receives 404. The test principal injector does not establish the real authentication stack.
- All immutable input/frozen source checks passed, JVM PIDs differ (157276 / 217056), ten native processes were used, and exact owned container removal was confirmed.
- This is the bounded restart acceptance increment only. The full P1-P11 implementation and 17 whole-story acceptance rows remain open. No genuine recording, production public admission, Linux sandbox, real authentication, client Watch or game-fidelity certification is qualified.

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

Final XML and actual commands: C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261017\native-db-restart-idle-result.json. Independently checked summary: C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261017\native-db-restart-idle-verified-summary.json.

- result: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261017\native-db-restart-idle-result.json`, SHA-256 `fb07245233a1541a1bedacc7daddaeb0499a479b3b40cd9ad6e2332baf4c7124`.
- prepare: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261017\native-db-restart-idle-owned\prepare\native-database-continuation.json`, SHA-256 `9e6149e7b75b2f9f94e15271406e15938366e4ea15679ba3dbaf4f3d5344cb1b`.
- resume: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261017\native-db-restart-idle-owned\resume\native-database-continuation.json`, SHA-256 `4955141e8b42047efc15940c24ba2ced07704f083aa88ca3f2151f201c57ab97`.
- recovery: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261017\native-db-restart-idle-owned\recovery.json`, SHA-256 `36e43bef15218cc4cc99f28092cf26cd8f039cc266ecc21af1b3772fb49a0414`.
- http: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261017\native-db-restart-idle-owned\http\http-export.json`, SHA-256 `a471f841ba97fd4976564e2d555f36ec438d6f19a6ab061c443da5a20dc23ea9`.

The independent archive check initially stopped on a bare-hex versus `sha256:`
prefix expectation. After requiring the exact production checksum format and
recomputing every digest, it passed. Artifacts, JUnit results and application/test
source were unchanged. The failed verifier diagnostic is preserved separately.

## Risks

- Fixtures are AUTHORED_TEST_ONLY/DRAFT/INCOMPLETE/HELD_OUT. Public completeness/curation/provenance guards remain required. Native runtime remains SYNTHETIC_CONTRACT, certifiable=false, controlAllowed=false; adapter 0.1.0, build 1903922a1132e4a9f30dd6c630510b7eb523fc75, cache sha256:15dfb8861c95ed8747ed7aae612444cf779d662507bbf04978b9c5067eb82397, content sha256:70db1bab5c42cc97ece5aa27066c81e209ffb41a841d021dcca44ba9c6ec3959. Dirty source hashes in the result, rather than HEAD alone, identify tested inputs.
- Windows native stdio is test-only. Production Linux filesystem/network/resource isolation and the complete activity/oracle matrix remain separate unrun gates. The loopback injector qualifies export/ownership behavior only; authenticationStackQualified=false.
- Browser/helper startup, client integration/package, genuine source/capture, final clean-pin build/CI/privacy/performance/upgrade/release gates remain open. The prior SC-1991 Linux authored/client results use earlier pins and do not qualify this Windows candidate.
- Initial failed invocations are preserved and excluded from passing totals. Full Checkstyle, full source test suite and hosted CI were not rerun for this infrastructure-only increment; the five Java helper formatting checks passed.
- Felix remains unavailable and the runtime sync mapping is absent; no tracker state success or completion is inferred from durable local records.

## Rollback

No deployment, shared schema migration, application restart, commit, push or merge occurred. The owned PostgreSQL fixture is absent and the embedded HTTP server/native transports were closed. Preserve archived artifacts/source hashes and all unrelated dirty work. To withdraw this increment, review/revert only its Gradle/test-helper/documentation edits, retaining pre-existing helper content and released migrations. Keep the previously supported paired runtime/schema package and do not point tests at application data.

## Remaining work

The complete P1-P11 plan remains open. Select and qualify a genuine finalized immutable BehaviorRunContext/task/profile/graph and published curation; extend exact bank/travel/Fishing provenance without initial-state invention. recording_b83c37e5 retains its independent blocked verdict. Complete the native method/prerequisite/interruption/restore and independent full-server oracle matrix; executable/handler/ESM/filesystem closure, OS egress/resource/cancellation/parallel isolation; integrate the existing SC-1991 actual client and experimental LIVE_XRSPS lifecycle/security/reconnect/controls, regional/plane parity and genuine typed capture; then clean final pins, build/package/CI/privacy/performance/upgrade/rollback/release review.

Browser automation failed before window inventory with helper_sandbox_lock_failed / SetNamedSecurityInfoW sandbox dir failed: 5 for C:\Users\Beheerder\.codex\.sandbox-bin. No UI action or human Watch check occurred. The reuse assessment in client-integration-assessment.md records the missing current compiler integration and packaged-client manifest. No Watch session is ready for human sign-off.

Felix's configured http://127.0.0.1:4301/mcp failed twice at transport and failed again on continuation retry. SC-274 task task_1789960012362_td2qnusp remains last known in_review, current state unverified. SC-276 task resolution remains pending; no duplicate was created. Current Shortcut reads are SC-274 blocked/In Review and SC-276 blocked/To Do. The runtime project's sync mapping is absent. No Felix note/state/checklist write or Shortcut mutation succeeded; durable per-story planning/evidence/reconciliation operations remain in each canonical project's docs\builderflow\felix-sync\pending folder.
