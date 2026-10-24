# SC-274 / SC-276 Verification Evidence — 2026-10-18

## Later continuation

See `worker-startup-followup.md` for the later JavaScript loading regression,
current native matrix and pinned original-archive recheck. Earlier failures
remain dated evidence; they are not removed by a subsequent focused pass.

The subsequent client build, unit verification and client-package pin fix are
recorded in `client-package-continuation.md`. That record supersedes earlier
statements here that the actual client build was unrun, while retaining the
qualified-package and browser/Watch gates. The user explicitly requested
skipping Felix for that continuation. Existing historic test pins below remain
scoped to their own source versions.

## Acceptance results

Computer-use retry after the user's explicit request to test through the UI:
the helper successfully inventoried Firefox and Google Chrome windows. Selecting
Firefox returned `Computer Use was not approved to use Firefox`; selecting the
Chrome alternative returned `Computer Use was not approved to use Google Chrome`.
The browser connector inventory again returned `apps: [], browsers: []`.
No page inspection or input succeeded. User authorization to test is present;
the tool's application-access prerequisite remains unsatisfied. No application
permissions, browser extensions or security settings were changed.

The complete plan is `../sc-274-sc-276-continuation-20261016/plan.md`. P1–P11 and whole-story acceptance remain incomplete. This increment verifies compiler-side integration and selected authored/native regressions, not genuine-source execution, qualified production runtime, client attachment or human Watch.

- Candidate booted from the compiler worktree on 8090 (JVM PID 220168 at verification). Source configuration validator passed with pre-existing canonical configuration. Flyway validated 76 resolved migrations, public schema V72 already current; no migration was applied by this boot. Hibernate uses `validate`.
- Read-only database checks: 82 Flyway history rows, zero failed rows, 119 recording sessions, zero behavior profiles. Preserve pre-repair backup `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261017\db-backup\chromascape-pre-v61-20261017T201134Z.dump`.
- `/debugger` and `/xrsps-sessions`: HTTP 200. Anonymous stabilization overview: HTTP 401. `/api/xrsps/sessions` GET returns 405 (not a supported listing route); `/xrsps-client` without its required parameters returns 400. These are route checks, not client package or browser qualification. See `final-http-checks.json`.
- Live provenance endpoint returns 200 for recording_c73dfff8, recording_b83c37e5 and recording_5e99d252; each has null origin/context and `RUN_CONTEXT_REQUIRED`, eligible=false. `live-selection-provenance.json`. This confirms the current eligibility blocker and preserves prior independent historical blocked verdicts; it does not resolve missing capture evidence.
- Existing authenticated launcher registry request succeeded with zero micro6 runtimes; `authenticated-runtime-registry.json`. Signed manifest request returned 503. Source trust shape/startup verification does not attest that the configured local-development trust root supplies genuine signed provider evidence. No replacement trust material, catalog or recording identity was created.
- Native capability discovery explicitly remains `SYNTHETIC_CONTRACT`, `certifiable=false`, `controlAllowed=false`; `native-capabilities-after.json`. The measured discovery invocation improved from 59.2487s to 9.3571s after buffer reuse, with both invocations exit 0. This is a local measurement, not controlled performance certification.
- Final capability discovery after the module-guard optimization exited 0 and still reports `SYNTHETIC_CONTRACT`, `certifiable=false`, `controlAllowed=false`; `native-capabilities-final.json`. Final native/compiler `git diff --check` commands passed, test/diagnostic workers exited, and `/xrsps-sessions` still returned 200 on 8090. No background replay consumption was enabled.
- Computer-use helper inventory works, but Firefox selection failed with `Computer Use was not approved to use Firefox`; current browser connector inventory has no browser targets. No browser action or page inspection succeeded. Human Watch remains unrun.
- Original Felix task cannot currently resolve (`Project not found`); no Felix notes, transitions, outbox replay, Shortcut writes or completion markers succeeded. Ten existing story outbox records remain pending. Do not infer durable tracker state from the local evidence.

## Tests

Evidence root: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261018`.

- 115 focused Java tests passed, zero failures/errors/skips: compiler/import/preflight plus durable boundary, client presentation, asset validation/containment, AI coordinator, principal config, workspace source hashing and heartbeat Spring-construction coverage. `compiler-client-tests.log`, `compiler-client-test-results.json`.
- 54 additional Java session regressions passed, zero failures/errors/skips: recorded action runner (12), contract schema (2), execution service (13), lifecycle (6), preflight (4), controller (7), store (10). `compiler-session-regressions.log`, `compiler-session-regression-results.json`.
- Four native inventory tests passed, zero failures/skips, including a file larger than the reusable buffer and small/empty file digests. `inventory-buffer-tests.log`.
- Native quests TypeScript check exited 0: `node server/node_modules/typescript/bin/tsc --noEmit -p server/tsconfig.quests.json`; `native-typecheck.log`. This configuration does not typecheck every test file.
- Scoped Spotless apply and full `checkstyleMain` succeeded; project warnings remain, including warnings in reused integration code. `compiler-style.log`, `client-format.init.gradle`. Both candidate `git diff --check` commands passed.
- Native matrix before the final module-guard startup optimization: 145 tests, 143 passed, two failed, zero skipped/cancelled, exit 1, 1,159,353.9062ms. `native-acceptance-after.log`, `native-matrix-result.json`. The two-worker NPC-binding test and guarded STEP_LIMIT case each timed out on `session.reset` at the unchanged 60-second request bound. Bank/travel/Fishing, combat, item training, ring enchantment, outcomes, other run-control cases, presentation projection, atomic actions and closure/inventory checks passed within that source scope. The isolated retry and final guard-change checks are separate evidence. Earlier parallel and pre-optimization sequential attempts were interrupted and their retained logs are not passes.
- Isolated unchanged-bound retry: two tests, zero passed, two failed, zero skipped/cancelled, 222,500.1488ms. `native-worker-isolated-retry.log`. NPC binding again timed out on `session.reset`; STEP_LIMIT reached cold `restore` and timed out there. This repeated failure is not resolved and is not attributed solely to the full matrix's load. Startup profiling is diagnostic only and cannot substitute for passing these acceptance tests.
- Startup diagnosis: unchanged reset first response 66,879ms; separate CPU-profile reset first response 70,983ms, followed by disposal/exit 0. `worker-startup-timing.json`, `worker-startup-profile-timing.json`, `worker-startup-profile-summary.json`. Dominant sampled costs were native file opens, TypeScript transpilation and cache decompression. A proposed no-JSDoc runtime transpilation mode produced identical emitted bytes and diagnostics for all 788 profiled source files; `worker-transpile-parity.json`. This comparison is scoped to those files.
- After the module-guard change, all nine runtime-module/offline-boundary tests passed with zero skips, including the earlier-file-replacement regression, cache-hit mutation refusal, direct-compiler stale-input refusal, symlink/junction/Windows alias/native-addon checks and public Node network/process/thread denials. `native-startup-guard-tests.log`. Quests TypeScript check repeated after this edit, exit 0; `native-typecheck-after-startup-fix.log`. Guard bytes exist only during the synchronous load and are cleared in `finally`; every new load/cache hit still verifies fresh bytes. The two previously failing worker cases are rerun separately at their unchanged 60s request bound; their final result is recorded below when available.
- Final worker retry after optimization: two tests, zero passed, two failed, zero skipped/cancelled, 151,730.8782ms, exit 1. Both timed out on `session.reset` at the unchanged 60-second request bound; `native-worker-after-startup-fix.log`. The optimization does not resolve the startup acceptance blocker. No wider native matrix is claimed for this final guard pin; the prior 143 passes remain scoped to the preceding matrix inputs.
- `XrspsDurableSessionIntegrationTest`, actual client build/browser scripts, production sandbox/OS isolation, full oracle/differential/privacy/resource matrices and CI were not run to completion in this increment. The native gated integration test requires a qualified worker package and disposable PostgreSQL configuration; the current candidate remains noncertifiable.

`increment-file-hashes.json` pins the touched source/integration files at verification. Older evidence remains scoped to its own hashes; it is not silently promoted to the current full story.

## Risks

No whole-story acceptance gate is satisfied by this increment alone. Genuine source/context/profile/catalog prerequisites remain absent. The running application's old replay entrypoint is not the current native candidate; background replay consumption is intentionally disabled to avoid consuming nine existing finalization runs against that package. The newly imported client integration has focused coverage but no real attached-client verification. General filesystem/ESM/OS isolation and full reachability qualification remain open; public certification/control guards were retained.

The current branch omits production's original change-request/proposal/build-job migrations and reuses their V58–V60 numbers for other scripts. Fresh deployment remains unsafe to assume equivalent to the live store. No migration/history repair was repeated.

Changes remain uncommitted across already dirty candidates. Checkstyle success includes warnings; it is not a clean-warning claim. The unresolved worker timeout prevents an all-green native matrix claim. Tracker task resolution and native Shortcut mapping remain outstanding.

## Rollback

Pre-integration copies are retained under the evidence root's `pre-client-integration`, `server-replay-identity.before.ts`, `runtime-module-guard.before.ts` and `runtime-module-guard.test.before.ts`. Revert only this increment's hunks against those backups after reviewing intervening edits; do not reset either dirty worktree. Restore/add/remove integration files individually as appropriate. Preserve the user's V50 guard and unrelated edits. No new DB migration, profile, provenance assignment or recording mutation was performed by this integration increment.

The candidate's exact launch script and logs are retained outside the repository. Stop only its owned PID after confirming identity; restore an approved known-good service using the existing ignored configuration and reviewed runbook. Keep replay consumption disabled until the intended worker package is verified. Database rollback to the earlier dump would discard subsequent data and needs a separate deliberate decision; it is not the routine rollback for these code changes.
