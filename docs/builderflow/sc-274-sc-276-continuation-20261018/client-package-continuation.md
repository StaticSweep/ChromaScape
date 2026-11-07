# SC-274 / SC-276 client package continuation, 18 October 2026

This increment continues the authoritative P1-P11 plan in
`../sc-274-sc-276-continuation-20261016/plan.md`. It does not close either story.
The user explicitly requested skipping Felix during this continuation. No Felix
mutation was performed; existing pending records are preserved. Final progress
comments were posted through hosted Shortcut MCP: SC-274 comment 2122 and SC-276
comment 2123, both with marker `[continuation:20261018-client-package]` after
checking existing comments. No workflow state, blocker flag or acceptance item
was changed; no Felix synchronization marker was emitted.
Hosted Shortcut reads confirmed SC-274 In Review/blocked (500000009) and SC-276
To Do/blocked (500000007), including their descriptions, comments and links.

## Changed files

- `src/main/java/com/chromascape/web/replay/session/XrspsRealClientConfig.java`:
  require the client package adapter version to match `xrspsAdapterVersion`,
  alongside build/cache/content pins. Every compared pin must be nonblank text.
  This also closes the case where two missing pins compared equal as empty
  strings, or a non-text package pin matched through coercion.
- `src/test/java/com/chromascape/web/replay/session/XrspsRealClientConfigTest.java`:
  load temporary hashed client/cache packages and exercise the owner-scoped
  client route with matching, mismatched, missing, blank and non-text pins.
  Cover each of the four identity fields; matching packages still forward to
  the real client index.
- This evidence and the continuation plan record the current checks. All other
  existing changes in the dirty candidates remain outside this increment.
- Native `server/src/replay/worker-main.ts`: initialize the existing shared
  WASM bzip decoder after installing the offline and module guards, before
  importing the game graph. Synchronous cache loading previously used the
  JavaScript fallback. Initialization failure remains fatal; no guard or
  acceptance timeout is relaxed.
- Native `server/tests/replay-compression.test.ts`: compare the shared decoder
  against the JavaScript fallback and exact fixture bytes; cover empty streams
  and ensure subsequent decodes do not overwrite returned results.

## Acceptance results

The actual client production bundle compiles successfully on Windows. Its 39
output assets are hashed in `client-build-diagnostic.json` under the evidence
root. This is a diagnostic build from the dirty candidate, not a qualified
client/worker package. No `replay-client-manifest.json` was generated and the
certifiable-worker gate in `scripts/build-sc1991-client.sh` was not bypassed.
ESLint was disabled exactly as in that build script; compilation does not imply
lint verification. CRACO's missing-ESLint-plugin message, the webpack assets
deprecation and bundle-size warning remain in the build log; exit code is zero.

The candidate was restarted through the documented Windows wrapper at 15:38 UTC
with the pin fix and existing source-trust configuration, PID 32712. Debugger
and session workspace return HTTP 200; anonymous stabilization overview returns
401. Flyway validated the existing schema at v72 with no migration necessary;
the unconditional SC-810 validator passed. Replay consumption remains configured
disabled. This is service startup verification, not qualified client attachment.

## Tests

Evidence root: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261018`.

- `client-build-continuation.log`: Windows CRACO production build, exit 0.
- `client-unit-continuation.log`: the complete nine-command `client/package.json`
  test script, exit 0. Includes orientation, NPC refresh, cache streaming, chat,
  camera, renderer lifecycle, widget root race and observer integrity checks.
  The observer portion reports four tests, zero failures/skips; the other
  scripts use assertions without a shared numeric test count.
- `client-observer-continuation.log`: separate four-case observer test pass,
  zero failures/skips, before the complete client script.
- `native-worker-continuation-recheck.log`: unchanged worker retry, two tests,
  zero passes, two failures, zero skips, exit 1, 168614.9328 ms. Both still time
  out on `session.reset` at 60 seconds. This is further failure evidence, not
  an optimization success or a wider final-pin native matrix.
- `client-runtime-pin-tests.log` and `.xml`: six Java client-package tests,
  zero failures/errors/skips; exit 0. Scoped Spotless apply passed. Full
  `checkstyleMain` passed with warnings (`client-runtime-pin-style.log`).
  `client-runtime-pin-hashes.json` pins both changed Java files.
- `probe-bzip-bootstrap.log`: diagnostic-only WASM initialization succeeded
  behind both existing guards. `probe-bzip-parity.log`: actual guarded authored
  Fishing reset compared all 18 encountered bzip blocks, 15,924,060 decoded
  bytes, byte-identical between JS and WASM; exit 0. This is scoped decoder
  parity, not a full native or activity differential oracle.
- `worker-compression-guard-tests.log`: ten tests passed, zero failures/skips,
  exit 0, including the new decoder test and existing module/offline guards.
- `native-typecheck-after-compression.log` and
  `native-worker-after-compression.log`: quests TypeScript check exited 0;
  both previously failing worker cases now pass, zero failures/skips, exit 0,
  205489.9413 ms. Individual test durations include multiple separate worker
  requests; each request retains its 60-second limit. No wider matrix pass is
  inferred from this isolated result.
- `native-matrix-after-compression-inputs.json` records the 26 selected native
  suites for the broader final-change run. The final log and result JSON report
  187 tests: 184 passed, three failed, zero skipped/cancelled, exit 1,
  1580450.7494 ms. Failures: source-receipt pending-action `session.reset`,
  player-death ordered replay, and ring-enchantment cold `restore` exceeded
  the unchanged request limit. The isolated passes above do not supersede this
  failed broader run. `worker-compression-hashes.json` pins the worker,
  decoder regression and unchanged module guard; all five Java/native source
  hashes still match in `continuation-source-recheck.json`.
- `native-capabilities-after-compression.json`: fresh CLI exit 0 retains
  `certifiable=false`, `controlAllowed=false`, `fidelity=SYNTHETIC_CONTRACT`.
  No activity or runtime qualification was advertised by these changes.
- `live-provenance-continuation.json`: all three provenance GETs still return
  200 with null origin/context, `RUN_CONTEXT_REQUIRED`, eligible=false. No
  assignment or promotion was performed.
- `micro6-executors-continuation.json`: the running candidate's registry call
  reports HTTP 401 inside an HTTP-200 error payload. A direct retry using the
  existing mule-service credential returns HTTP 200 with zero runtimes;
  `authenticated-runtime-registry-continuation.json`. The first authenticated
  attempt timed out. The existing credential is prepared in the candidate's
  private environment for activation on restart; no secret value is included
  in these evidence notes or tracked files, and no credential was minted.
- `candidate-restart-continuation.log`, `candidate-boot-after-restart.log`,
  `candidate-after-restart.json` and `candidate-http-after-restart.json` record
  the wrapper restart, candidate-owned PID and HTTP/auth results. The first
  registry request after restart reported `registry_unreachable`; two retries
  succeeded with authenticated source, count=0 and no error payload, recorded
  in `candidate-registry-after-restart.json`. No registered runtime is available.

## Remaining work

Startup timing remains unreliable: the broader native matrix recorded request
timeouts in source-receipt reset, player-death replay and ring-enchantment
restore despite the isolated worker passes. Retain these failures and resolve
startup performance without relaxing the request bound or module/offline guards.
Qualify a clean, current worker package
before generating its paired client manifest and enabling real client serving.
Complete genuine source/catalog/profile/context admission, native method and
oracle/privacy/resource/OS-isolation gates, live lifecycle/client attachment,
viewer noninterference and human Watch. Retain the precise historical blocked
verdicts; an eligible original historical recording is not required where the
acceptance explicitly permits a supported blocked audit.

The earlier Windows helper pipe failure recovered, but the last Firefox attempt
was stopped by unsupported browser URL policy enforcement. Supported browser
inventory again returned `apps: [], browsers: []`. The supported in-app-browser
attempt to open the live session workspace returned `Browser is not available:
iab`. No browser UI or human Watch pass was obtained. None of the client
build/unit checks is a browser or human Watch pass. The live migration-number/fresh-deploy gap remains
unresolved. Full acceptance stays unchecked.

## Risks

The pin fix has focused route coverage rather than a currently qualified
client/worker integration. Build output is ignored, local and noncertifying.
No profile, provenance identity, catalog, worker capability or certification
claim was fabricated. No database or source trust material was changed. The
existing registry credential was copied into the candidate-private environment;
no credential was minted or rotated. Tests and bundle outputs are scoped to
this dirty candidate, not CI or production qualification. Module enforcement
remains scoped to CJS; WASM/cache filesystem reads and OS isolation do not become
qualified merely because decoder initialization succeeds behind the Node guards.

## Rollback

Revert only this increment's pin checks and test additions after reviewing any
intervening edits; preserve the prior client integration and V50 guard. The
diagnostic client build is not configured into the running service. Restart the
candidate through the reviewed wrapper after reverting code. Remove only the
new registry auth entry from its private environment if reverting that config;
the upstream credential itself was not rotated. No database rollback is needed.
For the native startup change, remove only the new guarded Bzip2 initialization
block and its decoder test; retain all earlier module guard and inventory work.
