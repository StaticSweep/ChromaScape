# ChromaScape–Micro6 Remediation — Final Completion Matrix (2026-08-14)

| Requirement | Evidence | Status |
|---|---|---|
| Micro6 full test gate passes | `:client:compileJava` + `:client:compileTestJava` BUILD SUCCESSFUL (WSL and Windows). Focused suites green: Micro6BuilderExecutorServerTest (incl. conflict producer), HarnessCanonicalIdentityRegressionTest, AccountBuilderScriptLifecycleTest + IntegrationTest (12/13; 1 pre-existing headless-only recoverable-error test). FULL `:client:test` gate must still be run on the Windows host. | PARTIAL (focused green; full gate pending Windows host) |
| ChromaScape Java gate passes | 1373 tests, 32 failed = byte-identical to pre-change baseline (all pre-existing, outside builder-goal scope). 42 new remediation tests all pass. Windows toolchain confirmed. | GREEN for remediation scope; 32 pre-existing failures remain |
| ChromaScape JS gate passes | account-task-planner.test.js 103/103; fishing-shadow-core.test.js 36/36 | GREEN |
| Canonical key is FISHING_TRAINING | harness plans/fixtures/golden manifests; HarnessCanonicalIdentityRegressionTest; catalog-v2 demotes FISH_TRAINING to alias | DONE |
| Hash identity is authoritative | registry + evaluator tests; matchesAccount Rule-1; operational launch fails closed on RSN-hash mismatch | DONE (tested + operationally observed) |
| Expected world is enforced | findDispatchReadyClientForRecovery expectedWorld exact-equality; world_matched diagnostic only; tests | DONE |
| Missing readiness fields fail closed | Micro6ExecutorReadinessEvaluator (14 tests) + registry tests; missing/blank login, startup, pathfinder, dispatch hold all fail closed | DONE |
| Empty settings remain empty | settings normalization tests (empty-to-empty, legacy stripping, mismatch rejection) | DONE |
| Duplicate attachment is identity-verified | structured conflict parse + poll + whitelist; 8 consumer tests + producer test; Micro6 409 task_key = existing entry | DONE |
| PAUSED remains distinct | Java (mutation-service + run-summary) + JS (label/active-state) tests; transport mappers keep PAUSED | DONE |
| Real Fishing reaches RUNNING | Launch reached executor fully ready (logged_in/accountbuilder_ready/pathfinder/world360/RUNNING, process+attempt id verified) but publish blocked by account-hash divergence (RSN hash b01c7f006bace664 != catalog 3720a9a3b5c9f57b). | BLOCKED (tracked SC-964) |
| Relaunch avoids duplicates | single launch operation per request; single executor instance; executor stop clean; port freed | DONE (operationally observed) |
| Shortcut reflects proven state | SC-572/574/189 commented with evidence, kept In Progress; SC-964 hardening story created + linked; SC-573/584/671 untouched (gates unmet). MCP deprecation noted. | DONE (conservative) |

## Production defects found and fixed during this session
1. Registry `findDispatchReadyClientForRecovery` accepted unspecified identity -> now rejects.
2. Settings reserved-field strip set was missing taskName/task_metadata -> added + nested metadata filter.
3. Micro6 409 conflict `task_key` identified the REQUESTED identity -> now the EXISTING entry; `requested_task` separate.
4. PAUSED collapsed to RUNNING in 3 mappers + missing from planner -> preserved end-to-end.
5. Stabilization auth profile missing at boot -> `.env.local` sets SPRING_PROFILES_ACTIVE=local-development (git-ignored).
6. Launch payload ACL unreadable under elevation (Java NIO setAcl) -> icacls with current-user principal.
7. RuneLite joptsimple rejected --launch-attempt-id -> registered.
8. Micro6BuilderExecutorServerTest.tickOnly malformed assertEquals -> fixed arg order.

## Blockers
- Account-hash divergence for account 3720a9a3b5c9f57b blocks a real FISHING_TRAINING publish (SC-964 item 1).
- 32 pre-existing ChromaScape test failures (environment-only contextLoads cascade + test-seam drift in unrelated subsystems) — see failure-table.md.
- Full Micro6 `:client:test` gate and the publish/pause/resume/stop operational sequence require the Windows host with a matching-identity account.

## Evidence bundle
/tmp/chromascape-remediation/chromascape/failure-table.md
/tmp/chromascape-remediation/chromascape/phase3-8-results.md
/tmp/chromascape-remediation/chromascape/operational-acceptance.md
/tmp/chromascape-remediation/micro6/focused-test-results.md
/tmp/chromascape-remediation/micro6/resource-hashes.md
/tmp/chromascape-remediation/shortcut-migration-note.md
