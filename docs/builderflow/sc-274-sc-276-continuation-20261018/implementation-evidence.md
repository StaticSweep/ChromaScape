# SC-274 / SC-276 Implementation Evidence — 2026-10-18

## Later continuation

The subsequent verified-byte JavaScript loading change, current native matrix
and original-archive recheck are recorded in `worker-startup-followup.md`.

See `client-package-continuation.md` for the subsequent exact client identity
pin fix, its route regressions and the Windows client build/unit results.
The full implementation plan and remaining P1-P11 work remain unchanged in
scope; no whole-story completion is implied.

## Changed files

The complete scope, exclusions, Given/When/Then acceptance and P1–P11 implementation/test checklists remain in `../sc-274-sc-276-continuation-20261016/plan.md`. This increment implements the missing compiler-side SC-1991 session/client integration; it does not complete that plan.

- `src/main/java/com/chromascape/web/replay/config/ReplayProperties.java`: client asset configuration.
- `src/main/java/com/chromascape/web/replay/security/LocalReplayPrincipalConfig.java`: session workspace/presentation routes under the existing principal policy.
- `src/main/java/com/chromascape/web/replay/session/`: added `XrspsAiTaskCoordinator`, `XrspsClientIsolationFilter`, `XrspsClientPresentationController`, `XrspsPlaybackController`, `XrspsRealClientConfig`, `XrspsSessionWorkspaceController`; merged `XrspsDurableSessionService`, `XrspsSessionDocument`, `XrspsSessionWorker` with the existing candidate's source scheduling, eligibility and provenance checks retained.
- `src/main/java/com/chromascape/web/replay/worker/ReplayLeaseHeartbeat.java`: production constructor explicitly selected by Spring.
- `src/main/resources/static/js/xrsps-sessions.js`, `src/main/resources/templates/xrsps-sessions.html`: operator workspace.
- `scripts/build-sc1991-client.sh`, `scripts/sc1991-real-client-browser.mjs`, `scripts/sc1991-real-client-travel-browser.mjs`, `scripts/sc1991-real-client-ai-browser.mjs`: reused reference integration tooling; these scripts have not been executed in this increment.
- Session/security/heartbeat tests and `Sc1991ConfiguredProviderHarness`: reference tests merged and regression coverage added for source metadata normalization, Windows physical path aliases and Spring construction.
- Native candidate `C:\Dev\IdeaProjects\.worktrees\sc276-completion\server\src\replay\server-replay-identity.ts`: reuse a 4 MiB hashing buffer within each synchronous inventory walk instead of allocating one per file. Every file's full bytes are still hashed. `server/tests/server-replay-identity.test.ts` adds multi-buffer, small and empty file digest coverage.
- Native `server/src/replay/runtime-module-guard.ts`: the active synchronous load hands its verified bytes directly to the compiler, then clears them in `finally`; every fresh load/cache hit still rereads and verifies current bytes and physical paths. Direct compiler-hook invocation falls back to verification. Disable JSDoc parsing for runtime transpilation after byte-for-byte emitted-code and diagnostic parity across 788 profiled files. `server/tests/runtime-module-guard.test.ts` now replaces the file immediately after the first verification read, proves the verified bytes execute, and rejects both a later cache hit and direct compiler invocation against mutated bytes.
- `../sc-274-sc-276-continuation-20261017/client-integration-assessment.md`: dated recovery/integration findings replace the stale current computer-helper blocker.

Integration used per-file three-way merges against `origin/codex/sc-1991-completion-20261011` (`6ab04515`), merge base `9968880cc402f755d3e4c2f98afa9a861aea24ce`. Existing dirty content was preserved. One service conflict was resolved by keeping `ReplaySourceSchedule.semanticAction` hashing and conditional receipt checks. Workspace playback uses semantic action bytes while retaining original source ticks, immutable manifest and provenance. Client containment compares physical paths, including a physical root, and retains escape/symlink/inventory rejection checks.

Pre-edit backups, merge results and test logs are under `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261018`. `client-integration-result.json` records the merge procedure; its `conflict` entry records the encountered conflict, subsequently resolved in source. No wholesale branch merge or commit was performed. The pre-existing V50 guard and other unrelated candidate edits remain.

## Tests

- Focused `replayTest`: 115 tests, zero failures/errors/skips, including client boundary/configuration/AI/principal/workspace/heartbeat and compiler/import/preflight regressions. See `compiler-client-test-results.json` and `compiler-client-tests.log`.
- Native inventory regression: four tests passed, zero failures/skips; `inventory-buffer-tests.log`.
- Runtime module/offline guards after the startup optimization: nine passed, zero failures/skips; `native-startup-guard-tests.log`. Quests TypeScript check repeated after the guard edit, exit 0; `native-typecheck-after-startup-fix.log`.
- Startup diagnosis: an unchanged worker reset responded successfully at 66,879ms, beyond acceptance's unchanged 60s bound; a separate CPU-profile run took 70,983ms before response and exited 0 after disposal. These are diagnostic results, not acceptance passes. `worker-startup-timing.json`, `worker-startup-profile-timing.json`, `worker-startup-profile-summary.json`. Transpiler comparison covered 788 files with zero emitted-byte/diagnostic mismatches; `worker-transpile-parity.json`.
- Both worker cases still fail their unchanged 60s reset bound after optimization; two failures, zero skips, exit 1 in `native-worker-after-startup-fix.log`. Runtime startup acceptance remains open. The earlier 143 matrix passes are evidence for the earlier guard pin, not a full rerun of the final pin.
- Native quests TypeScript configuration: `node server/node_modules/typescript/bin/tsc --noEmit -p server/tsconfig.quests.json`, exit 0; `native-typecheck.log`.
- Scoped Spotless apply and full `checkstyleMain`: successful; project-wide warnings remain. `compiler-style.log` and `client-format.init.gradle` identify the exact formatting scope.
- Additional Java session regressions: 54 passed, zero failures/errors/skips; `compiler-session-regressions.log` and `compiler-session-regression-results.json`.
- Selected native matrix: 143 passed, two worker-reset timeouts, zero skipped, exit 1; `native-matrix-result.json` and `native-acceptance-after.log`. The isolated retry is separate evidence; the matrix is not an all-green certification pass.
- Both candidate `git diff --check` commands passed after formatting.

## Remaining work

8090 now serves the candidate, started using the canonical checkout's existing ignored `.env.local`; no signing key or source/access token was fabricated. The source trust validator passed, but this establishes configuration only. The signed source-manifest endpoint returned 503. An authenticated read of the existing launcher registry returned `{"runtimes":[]}`. Operator profile credentials are not configured, the profile table is empty, and all three audited recordings remain ineligible with `RUN_CONTEXT_REQUIRED`. Do not assign provenance or append a fabricated catalog/profile.

The launcher at `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261018\start-candidate.ps1` retains Hibernate `validate`, disables absent MCP auto-start and keeps replay worker consumption disabled to protect nine pre-existing finalization runs from the older configured runtime. The running app's configured old replay package does not establish current native candidate integration. The current native candidate explicitly remains `SYNTHETIC_CONTRACT`, `certifiable=false`, `controlAllowed=false`; packaged-client production qualification and manifest generation remain gated.

Finish native residuals, production boundary/client packaging, same-world client lifecycle/noninterference, genuine source admission, computer-assisted execution and human Watch, full CI/differential/privacy/resource/isolation certification, scoped review and tracker reconciliation. Browser inventory through the current browser surface is empty; the dedicated computer-use skill can inventory Firefox but rejects selecting it with `Computer Use was not approved to use Firefox`. No page was inspected or Watch signed off.

Felix task `task_1789960012362_td2qnusp` is unresolved. A current configured request failed transport; the immediate retry successfully returned `Project not found: C:\Dev\IdeaProjects\ChromaScape`, so this pair does not establish a new two-failure transport outage. Existing pending records were scanned and left pending. No replacement/duplicate task, note, validated transition or Shortcut write was made. Last reported SC-274 status was In Review/blocked; current Felix status is unverified. SC-276's task remains unresolved and its canonical repository has no reviewed Shortcut mapping.

The missing original production recording change-request/proposal/build-job migrations remain a fresh-deployment risk. Existing live tables are not proof that a fresh candidate deployment creates them. Do not add duplicate V58/V59/V60 migrations or silently repair history again.
