# SC-274 / SC-276: R1 and R2 execution - 25 October 2026

Native checkout: `C:\Dev\IdeaProjects\.worktrees\sc276-recovered-20261021` (branch
codex/sc274-native-recovered-20261024, base 3e465691). Changes are uncommitted.

## R1 - builtin-ESM hook registration escape (local, Windows Node 22.19)

- `runtime-module-guard.ts`: `register`/`registerHooks` are now defined non-writable and
  non-configurable; the installed resolve hook refuses ESM-initiated `node:module`/`module`
  imports (an ESM namespace snapshots the original registration functions).
- Trusted bootstrap boundary documented: references retained by code that ran before installation
  (including an already-evaluated node:module namespace) are not revocable.
- New regression `builtin ESM node:module cannot register ...` fails against the previous source
  (verified) and passes now. Guard suite: 15/15 on Windows (esbuild 0.28.1 binary).
- NOT done: pinned Linux/Node run, bootstrap/closure CLI/real worker smoke (R1 exit requires them).

## R2 - trusted oracle-reference binding (local)

- `oracle-harness.ts`: `evaluateOracleMatrix` now requires an externally supplied reviewed
  `inventory` (`OracleApprovedReference`: id, method, scenario, sealed digest, provenance, authority,
  action/initial-state digests), enforces `advertisedMethods` coverage itself, rejects duplicate
  reference ids and inventories, keys replay output by reference id with action/initial-state
  digest binding, and adds frame/skill/inventory/event/total size bounds.
- `oracle-reference-inventory.ts`: shipped inventory is intentionally empty.
- The relabel-and-reseal probe is a negative test (TAMPERED against the approved original digest,
  UNAPPROVED with no approval). Harness tests 15/15; matrix tests 3/3.
- NOT done: source/runtime/closure identity and comparison-profile binding in the actual output;
  genuine references (R10). Verdict stays INCOMPLETE; `AUTHORITATIVE_RUNTIME_QUALIFICATION.complete`
  is unchanged (false).

Remaining: R3-R12 as in remaining-implementation-plan-20261025.md; no acceptance row is closed.

## R3 (partial) - oracle semantics and promised scope (local)

- `oracle-harness.ts`: frames may carry equipment, vars, bank, exchange, quest, combat and event
  payloads (bounded plain JSON). Matrix entries list `requiredFields`; a reference lacking one is
  INCOMPLETE_CAPTURE, replay output lacking one is FAILED with the exact tick/field. `projectOracleFrame`
  fills the fields from the emitted frame. Each field has a deliberate regression test (20/20).
- `promised-method-inventory.ts`: records every originally promised method; glassblowing, Wind Strike,
  alchemy, superheat, ring enchantment and the five auxiliary methods are UNADVERTISED with a stated
  blocker. The matrix now covers all promised methods, so qualification stays INCOMPLETE until they
  have references. A test pins advertised == promised-ADVERTISED, so scope cannot shrink silently.
- The per-method `requiredFields` choices are authored plans to review against genuine captures.
- NOT done: scoped Magic/glassblowing contracts, production/headless parity evidence (needs genuine
  production captures), tick/action alignment spec.

Tests (Windows, Node 22.19): harness 20/20, matrix+capabilities+guard suites pass; typecheck of
replay-kernel clean. Server-wide typecheck and the Linux lane were not run.

## R4 (partial) - per-read filesystem enforcement (local, Windows)

- New `runtime-read-guard.ts`, installed in `worker-main.ts --stdio` right after the module guard.
  Public `node:fs` reads are limited to inventoried source/dependency/metadata/cache files
  (new `cacheBindings` on the server identity); whole-file reads are re-hashed against the pinned
  size/SHA-256 for that read; all mutating/watch/handle-listing APIs are refused; only fd 1/2 are
  writable; directory listings are filtered to inventoried entries; metadata probes are limited to
  pinned files and their ancestors; descriptor reads only on gate-opened fds.
- Limits (stated in the code): public-fs scope, not an OS sandbox; descriptor/stream reads are
  path-gated at open and not re-hashed; internal bindings, native addons and pre-captured fs
  references are outside it. The closure manifest's `filesystem: INVENTORIED_NOT_ENFORCED` label is
  deliberately unchanged until Linux verification.
- Evidence: read-guard unit tests 8/8; real worker with the guard active, sequential run of the
  worker-backed suites 44/44 (the first parallel run showed timeouts only), plus kernel, identity,
  bootstrap and closure tests passing. Windows only.
- NOT done in R4: reachable-handler graph, JSON/data race coverage on Linux, reproducible Linux
  package from the latest commit, independent-directory rebuild, owned compiler service,
  closure CLI timeout diagnosis, 27-path recovery boundary.

## R4 - Linux verification of 075c6f61 (Ubuntu-22.04 WSL, Node 22.19.0 linux-x64, JDK 17)

Recovery: WSL instance creation was timing out (0x8007274c); `wsl --shutdown` was run after the
workspace instructions were changed to allow it. Ubuntu came back; Docker Desktop restarted itself.

Lane recipe (Linux-native copy, not DrvFs): clone 075c6f61 from a `git bundle` into `~/r4/<dir>`;
Yarn 4.12.0 bundle downloaded to `~/r4/tools` (Ubuntu's apt npm is broken); `yarn install --immutable`
in root, `server` and `client` (client is required: server imports `client/rs/*`, needs `gl-matrix`);
copy the real cache bytes (`server/caches`, `server/cache/collision` are Windows junctions into
`xrsps-typescript` - use `cp -rL`, a symlink copy makes the tree look dirty); then
`node packages/replay-kernel/scripts/generate-runtime-identity.mjs`.

Evidence:
- Certifiable identity generated on Linux (`certifiable=true`, commit 075c6f619250, cacheRevision
  sha256:5300de2e..., 2897 cache files). Two independent directories (`a2`, `b2`) produced a
  byte-identical `runtime-identity.ts` (sha256 d9ca70a65b30f545...). The Windows generated file was a
  stale non-certifiable dev identity (commit 1903922a), so no Windows comparison was possible.
- Guard/oracle suites on Linux: module guard 15/15, read guard 8/8, oracle-acceptance-matrix 6/6,
  oracle-harness 20/20, runtime-closure-manifest 13/13, runtime-closure-worker 1/1.
- Worker-backed suites with the read guard active, run sequentially: manifest-action-policy 10,
  misthalin-mystery-replay-parity 10, native-bank-travel-fishing 5, native-npc-bindings 8,
  native-outcomes-worker 2, native-run-control-worker 3, recorded-action-schedule 5,
  replay-combat-worker 2, replay-ring-enchantment-worker 1, rune-mysteries-replay 6,
  self-contained-snapshot-worker 2, worker-bootstrap-boundary 5 - all pass.
- `--runtime-closure` CLI returned in 2 s warm on Linux; the cold/concurrent Windows timeout was not
  reproduced or diagnosed.

Finding (pre-existing, not from this branch - no diff vs 3e465691 in these files):
`authoritative-replay-kernel.test.ts` "differential: the real-server kernel matches the synthetic
kernel through a catch" FAILS on Linux (10 pass, 1 fail, 1 skip) with
`manifest.actions[1]: WAIT_UNTIL_TICK is not attested by the authoritative worker`. On Windows it is
silently SKIPPED because the identity is non-certifiable, so the failure was hidden. The test manifest
contains WAIT_UNTIL_TICK under `XRSPS_AUTHORITATIVE`, while `capabilities.ts` says WAIT_UNTIL_TICK
cannot be advertised as authoritative. Needs a decision: change the test, or change the contract.

Still NOT done in R4: the `INVENTORIED_NOT_ENFORCED` label is unchanged (guard is public-fs only,
not an OS sandbox); reachable-handler graph; JSON/data race coverage; new native recovery bundle;
owned compiler service; Windows cold-load closure timeout diagnosis; 27-path recovery boundary.
Nothing pushed.

Update: the stale differential test was fixed in a5f6a8dd (synthetic kernel gets the scenario without
WAIT_UNTIL_TICK; Linux authoritative-replay-kernel now 11 pass / 0 fail / 1 skip). The remaining skip
("worker-main --stdio serves the real-server kernel ...") is the intended blocker: on a clean Linux tree
the server identity's only non-certifiable reason is "authoritative runtime closure, isolation and oracle
qualification is incomplete" (AUTHORITATIVE_RUNTIME_QUALIFICATION.complete=false, deliberately not flipped).

## R4 - closure identity, timeout check and new recovery bundle

- Closure comparison: `--runtime-closure` output (623,099 bytes, sha256 d4599e62...) and
  `--capabilities` output are byte-identical between the independent Linux directories a2 and b2
  (different paths, same commit 075c6f61 and cache bytes).
- Closure CLI timeout (test deadline 45 s, `runtime-closure-worker.test.ts`): NOT reproduced.
  Linux: ~2 s alone, ~6 s with 8 concurrent on 4 cores. Windows: 4.8 s alone, ~6 s with 4 concurrent.
  The earlier timeout therefore was not caused by the CLI itself under this load; the likely cause is
  whole-machine load from parallel ~47 s worker suites. The deadline was not changed.
- New recovery bundle (original NOT touched; still tip 79382be3, sha256 e0365bc5...):
  `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261025\r4-native-20261025\native-sc274-20261025.bundle`
  tip a5f6a8dd on codex/sc274-native-recovered-20261024, `git bundle verify` ok (complete history),
  sha256 60d09a9faabab972a088af77f4c943203a7e30fb21d467de3322afed4632d96b. It covers commits through
  a5f6a8dd only; later commits need a new bundle.

## R4/R2/R6 - continuation commits (native branch codex/sc274-native-recovered-20261024)

- cda75033 Descriptor verification: openSync / promises.open / createReadStream now hash the pinned
  bytes on the descriptor they hand out (positional reads, offset untouched) and stream that verified
  descriptor. Regression test fails on the previous guard, passes now (read guard 10/10 on Windows+Linux).
- 19020717 Observed graph + relabel: `observedRuntimeModules()` and `worker-main.ts --observe-graph`.
  Guarded bootstrap loads 634 of 1306 inventoried source files, vanilla-only, no extrascripts / non-vanilla
  gamemodes / native / .mjs (test: runtime-observed-graph). The list is saved in
  `observed-bootstrap-graph-20261025.json` (this folder). This is OBSERVED reachability (lower bound), not
  a static proof. Closure manifest filesystem label is now `PUBLIC_FS_READ_GATE_NOT_OS_SANDBOX`; the
  qualification `remaining` list now states the real gaps (static handler graph, OS-level fs isolation).
- 8e3958e1 R2 binding: `OracleActualRun` carries source/content/cache/closure identity + comparison
  profile; `evaluateOracleMatrix` requires the pinned `expectedIdentity` and fails on any mismatch
  (new status IDENTITY_MISMATCH). `oracleRuntimeIdentity()` refuses a partial identity. Oracle harness 21/21.
  Same commit adds `server/scripts/build-replay-package.ts` (deterministic USTAR of exactly the closure
  inventory, every file re-hashed while read) and its test (byte-identical across two builds; Windows ok).
- 8159745e R6 verifier: `evaluateQualificationResult` (QUALIFIED/FAILED/INCOMPLETE/REJECTED) over a
  versioned record; unknown/approval fields, stale identity/environment, duplicate/unknown components and
  PASSED-without-evidence digest are REJECTED; never changes AUTHORITATIVE_RUNTIME_QUALIFICATION. Tests use
  explicitly synthetic evidence (5/5).

Linux evidence (fresh Ubuntu-22.04 clones, Node 22.19.0, Yarn 4.12.0, same cache bytes):
- 19020717 full suite: module guard 15, read guard 10, observed graph 1, matrix 6, kernel 11 (1 intended
  skip), manifest-action-policy 10, misthalin 10, bank/travel/fishing 5, npc-bindings 8, native-outcomes 2,
  run-control 3, recorded-schedule 5, combat 2, ring 1, rune-mysteries 6, closure-manifest 13,
  closure-worker 1, snapshot 2, bootstrap-boundary 5, oracle-harness 20 - all pass, 0 fail.
- 8159745e package: two independent clones (p1, p2) each generate a certifiable identity and build a
  byte-identical package: 4583 files, 544319488 bytes, sha256
  117579fd967e6b69e369105f1a4d838565ed627aa3883eeabe19d0fd0d9fa4d0, closure checksum
  sha256:0b2415806e91b8634817b32052bd7e438411efd7b7407c25ab532323f2778824. Build takes ~8-9 s.
  The package excludes the Node executable and OS (identity recorded in its manifest). `certifiable` stays
  false by design (AUTHORITATIVE_RUNTIME_QUALIFICATION.complete=false).
- Tooling notes: Ubuntu's apt npm is broken (use the Yarn 4.12 bundle); DrvFs copies can fail with
  "Cannot allocate memory" - copy the cache once into Linux and reuse it.

## R5 (partial) - confinement supervisor and measurements (Linux, unprivileged)

- a4e6c01b `server/src/replay/confined-worker.ts`: `spawnConfinedWorker` = `unshare --user --map-root-user
  --net --pid --fork --kill-child=SIGKILL` + `prlimit` (--as, --nproc = current user tasks + budget, --nofile,
  --cpu, --core=0, --fsize=0) + supervisor wall-clock SIGKILL with attributed outcome (EXIT / WALL_CLOCK /
  TERMINATED). No unconfined fallback: missing primitive or invalid limit throws ConfinementUnavailableError.
- Linux tests 7/7 (confined-worker.test.ts): no network even to a supervisor-owned loopback listener; no bytes
  persisted (EFBIG/SIGXFSZ); 6 GiB allocation contained under a 4 GiB address-space ceiling; 200-process fan-out
  bounded at the task ceiling; terminate() leaves zero surviving descendants; silent worker killed at the
  wall-clock deadline; the real worker's `--capabilities` output is byte-identical confined vs unconfined.
  Unconfined control run confirmed the same payloads connect, write and allocate (so the negatives are real).
- NOT enforceable unprivileged here and therefore NOT claimed: aggregate memory/CPU across the tree (cgroup v2
  exists but is root-owned and not delegated; no systemd-run). RLIMIT_NPROC counts the real uid's tasks, so it
  is a fan-out ceiling, not an exact tree limit. Cancellation during checkpoint/report writes and shutdown races
  are not yet exercised; the supervisor kills the whole PID namespace so descendants cannot survive.
- Measurements (provisional, WSL2 4 cores / 6.4 GB, Node 22.19, `--stdio` first protocol response, 3 runs each):
  plain cold 8.7-10.2 s, plain warm 8.4-9.7 s, confined cold 9.4-10.2 s, confined warm 9.4-11.2 s; peak RSS
  361-387 MB. Cold == warm because the guard transpiles in memory regardless of the tsx cache. Per-scenario
  throughput through the production transport and parallel scaling were NOT measured.

## R10 preparation

- `reference-requests-20261025.md` (this folder) is generated from the acceptance matrix + promised-method
  inventory: 18 method rows, reference ids, required state fields, approval record shape and blockers.
  These are requests only; no reference has been obtained.

## Windows-side continuation (Linux work split to Shortcut sc-2201)

- Windows rerun of all native suites at a4e6c01b: every suite passes (module guard 15, read guard 10, observed
  graph 1, matrix 7, qualification-result 5, kernel 10 pass / 2 skip, manifest policy 10, misthalin 10,
  bank/travel/fishing 5, npc-bindings 8, native-outcomes 2, run-control 3, recorded-schedule 5, combat 2, ring 1,
  rune-mysteries 6, closure-manifest 13, closure-worker 1, snapshot 2, bootstrap 5, oracle-harness 21),
  EXCEPT replay-package, which failed with ENOSPC: the Windows system drive had ~0.8 GB free and the test wrote
  two ~0.5 GB archives. Fixed in 6b5b4b40 (--digest-only; one archive on disk); passes at ~1.1 GB free.
  confined-worker is Linux-only (7 skipped on Windows, passed on the Linux lane).
- Server-wide `tsc -p tsconfig.json --noEmit` is clean (14.8 s); added tests/scripts typechecked with a temp
  config (one type error in my own test fixed in 6b5b4b40).
- f7406542 R3: `server/src/replay/method-contracts.ts` = scoped contracts for glassblowing, alchemy, superheat
  and ring enchantment, derived from production tables; `method-contracts.test.ts` (27/27) drives the headless
  adapter from every contract entry (exact level boundary, consumption, product, XP, cooldown). The four methods
  stay UNADVERTISED; blocker text now says: independent parity evidence + capability advertisement.
  Wind Strike and the five auxiliary methods have NO contract: Wind Strike depends on combat accuracy/hit rolls
  that need an independent reference; auxiliary actions are not advertised in the kernel's action vocabulary.
- Disk: the Ubuntu-22.04 disk image (C:\WSL\Ubuntu-22.04\ext4.vhdx) is 128.6 GB for 48 GB of Linux data;
  WSL has sparse VHD disabled for corruption risk (not forced). Compaction needs an elevated shell.
  I deleted my scratch Linux clones (kept ~/r4/tools and ~/r4/cache-store) and my Windows temp files.
