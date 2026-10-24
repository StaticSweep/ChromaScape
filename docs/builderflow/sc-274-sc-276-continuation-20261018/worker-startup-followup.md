# SC-274 / SC-276 worker startup follow-up, 18 October 2026

Continuation of `../sc-274-sc-276-continuation-20261016/plan.md` and
`client-package-continuation.md`. Felix is skipped at the user's request.
This is local evidence; no whole-story acceptance or certification is claimed.

## Changed files

- Native `server/src/replay/runtime-module-guard.ts`: compile plain CommonJS
  JavaScript directly from the bytes already verified for the current load.
  Parse those bytes first; static modules, dynamic imports, meta-properties,
  JSX and parse-error cases retain the TypeScript transformation path. Every
  load/cache hit still resolves its physical path and checks current bytes
  against the pinned size/SHA-256. No file/resolution cache, network allowance
  or timeout relaxation was added.
- Native `server/tests/runtime-module-guard.test.ts`: prove `.js` and `.cjs`
  avoid TypeScript emission, exact verified bytes execute despite subsequent
  file replacement, changed cache hits fail, static exports are transformed,
  and dynamic import of an unpinned file rejects before execution.
- Plan and evidence documentation. Earlier dirty work remains intact.

## Acceptance results

The pre-change current-code diagnostic completed actual guarded Fishing
`session.reset` in about 45 seconds, under a diagnostic ceiling rather than
an acceptance test. Its CPU profile still spent roughly 16 seconds in
TypeScript and ten seconds opening files. Removing unnecessary JavaScript
emission targets that measured work; the performance result must be established
by the final current-code tests, not inferred from the implementation.

The existing Micro6 listener on 8098 belongs to PID 242640, launched on
15 October from `C:\Dev\IdeaProjects\.worktrees\sc1976-micro6`. Its command
line declares `micro6.source.mode=disabled`; its identity sidecar names the
existing shaded artifact. Read-only health requests with the three relevant
existing ChromaScape credentials returned 401. This does not establish the
runtime's health, catalog or genuine-source readiness. The process was left
untouched. Process/User/Machine environment checks found no configured source
bundle root or signing material. The checked canonical Micro6 configuration
contains examples, not a provisioned authoritative bundle. A source-disabled
client cannot be promoted into trusted signed-source evidence.

Read-only GitHub discovery also checked the owning `ikiddoi/micro6` releases
and Actions artifacts. The five most recent releases, including 2.1.4 dated
15 October, publish only shaded client JARs; no signed source bundle appears
among those assets. The Actions artifact API returned total_count=0. The
checked release workflow publishes the JAR, not an authoritative source bundle.
This is a bounded discovery result, not proof that no trusted provider exists.
Results are `micro6-release-discovery.json` and `micro6-artifact-discovery.json`.
The repository Actions secret-name metadata query succeeded and returned no
names matching `MICRO6_SOURCE`, `SOURCE_SIGN` or `SOURCE_BUNDLE`; no secret
values were requested (`micro6-source-secret-metadata.json`). This does not
enumerate unrelated external/environment-scoped signing deployments.

## Tests

Evidence root:
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261018`.

- `worker-current-startup-profile.log`,
  `worker-current-startup.cpuprofile`,
  `worker-current-startup-profile-timing.json` and
  `worker-current-startup-profile-summary.json`: pre-change startup diagnostic,
  exit 0, response observed,
  elapsed 44842 ms. This is a measurement, not an acceptance pass.
- `worker-js-fastpath-guard-tests.log`: eleven tests passed, zero
  failures/skips/cancellations, exit 0, including byte mutation, dynamic
  import, physical alias/junction, module, offline and decoder regressions.
- `native-typecheck-after-js-fastpath.log`: quests TypeScript check, exit 0.
- `native-matrix-after-js-fastpath-inputs.json`, `.log` and `-result.json`:
  26 selected suites, sequential and unfiltered; normal request limits retained.
  Final exit 0: 188 passed, zero failures/skips/cancellations, 1299039.4408 ms.
  All three previously failing cases passed: source-receipt pending-action
  restore (55847.8176 ms), player-death ordered worker replay (98257.0624 ms),
  and seven-tier ring-enchantment cold restore (87984.8272 ms). These durations
  include multiple worker requests; each request retains its 60-second bound.
  The prior failed matrix remains evidence, not an overwritten result. This
  selected authored matrix is not full runtime/stress certification.
- `worker-js-fastpath-source-hashes.json`: all four native files remained
  unchanged throughout the matrix. `native-capabilities-after-js-fastpath.json`
  is a fresh CLI exit-0 result, retaining SYNTHETIC_CONTRACT, certifiable=false
  and controlAllowed=false.
- `candidate-http-after-js-fastpath.json`: debugger/workspace 200, anonymous
  stabilization 401. Runtime discovery reported `registry_unreachable` on the
  final check and retry; this error's count=0 is not a successful empty result.
  Earlier successful authenticated discovery remains separately dated evidence.
- `historical-archive-client-audit-recheck.json` and `.log`: the existing
  revision-241 decoder script revalidated all nine pinned artifacts, compiled
  the audit and read the unchanged original recording archive. Exit 2:
  38,458 frames; 2,620 client packets, 1,459 fully consumed and 1,161 failures.
  Archive SHA-256 remains
  `c67cb5c0f2891475c580c0336eba630cf71336bb2943e71d7bfeaf55ea281a0e`.
  `historical-archive-client-audit-comparison.json` confirms those counts and
  framing match earlier evidence. Server state was not decoded and archive
  tick-to-recording alignment remains unresolved. This supports the retained
  blocked verdict; it is neither complete semantic reconstruction nor a
  causal execution eligibility claim.

## Remaining work

Registry follow-up: `registry-health-address-probes.json` distinguishes the
address failure: 127.0.0.1:8084 timed out, while localhost:8084 and
192.168.1.6:8084 returned health 200. Two authenticated requests through
localhost returned 200 with `runtimes: []`
(`registry-localhost-authenticated-probes.json`). Existing registry credentials
therefore work at that address, but no runtime was registered in those results.
The proposed private registry-URL update and candidate restart were rejected
before execution by automatic approval review, with only `blocked by policy`
given as the reason. Neither configuration nor restart was applied. A fresh
listener check still finds candidate PID 32712 on 8090. The current candidate's
`registry_unreachable` response remains unresolved; the successful direct
localhost probes must not be reported as a repaired candidate route.

The selected current-code matrix passes; full runtime/stress qualification is
still incomplete. Genuine capture needs a provisioned authoritative source
runtime/bundle, authenticated catalog,
immutable profile and run context. Complete qualified worker/client packaging,
actual same-world actor/client lifecycle and Watch, and the remaining full
oracle/privacy/resource/ESM/filesystem/OS-isolation/CI gates. Keep the historical
recording's blocked verdict and the independent migration fresh-deploy risk.

## Risks

Final read-only checks: `candidate-final-readonly-http.json` records debugger
and workspace 200, anonymous stabilization 401.
`worker-js-fastpath-final-source-check.json` confirms all four tested native
source hashes still match. Both worktrees' `git diff --check` returned exit 0.

## Tracker result

Official Shortcut MCP created progress comments 2124 on SC-274 and 2125 on
SC-276, marker `[continuation:20261018-worker-startup]`:
https://app.shortcut.com/leeuwbot/story/274#activity-2124 and
https://app.shortcut.com/leeuwbot/story/276#activity-2125.
SC-274 remains In Review (500000009); SC-276 remains To Do (500000007).
No state transition or Felix synchronization is claimed. Felix is skipped at
the user's request; existing pending records remain preserved.

## Risks (continued)

The JavaScript path changes dependency loading and requires real-worker
regressions in addition to guard unit tests. Certification/control remain
disabled. No credential, source identity, catalog, profile or capture was
fabricated. No database mutation or unrelated client restart was performed.

## Rollback

Review and revert only the JavaScript compilation path and its added regression.
Scoped pre-change copies are `runtime-module-guard.before-js-fastpath.ts` and
`runtime-module-guard.test.before-js-fastpath.ts` in the evidence root. Preserve
the previous WASM initialization, byte-verification guards, client pin checks,
V50 edit and all unrelated dirty work. No database rollback is needed.
