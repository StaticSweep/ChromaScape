# SC-2150 — R7 integrated freeze and verification (2026-11-03)

Candidate and toolchain identities are frozen in
[`candidate-manifest-20261103.md`](candidate-manifest-20261103.md) (commit `053e3669` plus the
`R7` doc commit). Every row below names the command that produced it and the receipt that holds the
raw output. Nothing here is inherited from an earlier round.

## 1. Full Java suite — run, results and dispositions

```bash
DOCKER_API_VERSION=1.44 JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
SC2150_FA12_CORPUS=<fa12 corpus> SC2150_FA12_OUT=build/sc2150-closeout/r7/fa12-out \
  ./gradlew test --continue \
  -Psc967VectorsPath=…/contracts/sc967/ed25519-vectors.json \
  -Psc967ReportDir=build/sc2150-closeout/r7/junit
```

**3290 tests, 152 failed, 20 skipped** (`build/sc2150-closeout/r7/junit`). Two runs were made; the
first (without a local `chromascape_test` database) had 164 failures, so that database was created
and the suite re-run — the numbers below are from the second run.

Failure classification, by actual message text:

|Count|Cause|Evidence|Candidate relevance|
|---|---|---|---|
|54|`NoSuchFileException …/contracts/sc967/execution-closure-vectors.json` — the host's `contracts/sc967/` holds only 4 files, this fixture is absent|message text|none; the fixture is absent for `origin/main` too|
|39|`ApplicationContext failure threshold (1) exceeded` — cascade after the first context failure in the same fork|message text|none|
|17|`MouseVideoReviewSessionPostgresTest` — `CannotStubVoidMethodWithReturnValue 'broadcast'`, a Mockito thread-state leak from an earlier failing test in the same fork|message text + standalone runs below|none: the class passes 17/17 standalone on **both** trees|
|9|`404 ARTIFACT_NOT_FOUND` in `FishingActiveModelServiceTest` — missing model artifact in the local ai-player store|message text|none (ai-player)|
|10|`Failed to load ApplicationContext` / `WebMergedContextConfiguration` — context prerequisites|message text|none|
|3|`AI_PLAYER_UNSAFE_TARGET` — needs profile `ai-player-development` and the `chromascape_aiplayer_dev` database|message text|none|
|2|`Flyway must reach repository head V57 … expected <57> but was <87>` and `expected <60> but was <87>` — hard-coded migration versions|reproduced on `origin/main` (see below)|none; `origin/main` reports 86 and fails identically|
|2|Testcontainers `Connection to 127.0.0.1:55450 refused`|message text|none|
|~16|PostgreSQL container/state and remaining context failures inside the same cascade|message text|none|

### Baseline comparison (targeted, decisive)

The recorded 2026-10-31 dispositions already list these families; to avoid a second 25-minute
full run on a loaded host, the comparison was made on the classes that matter:

```bash
cd ChromaScape-baseline-20261103   # detached worktree at origin/main 7758d867
./gradlew test --tests 'com.chromascape.web.debug.tickdebugger.video.*' \
  --tests 'com.chromascape.web.debug.tickdebugger.video.**' \
  --tests 'com.chromascape.ChromaScapeApplicationTests' \
  --tests '…accountbuilder.Sc1431BudgetIntegritySchemaTest'
```

|Tree|Tests|Failed|Skipped|
|---|---|---|---|
|`origin/main` `7758d867`|341|**2**|1|
|Candidate `053e3669` — same package plus the FA12 corpus|**346**|**0**|**0**|

The baseline's two failures are exactly `ChromaScapeApplicationTests#contextLoadsAndUsesCurrentRepositoryMigrations`
(`expected <57> but was <86>`) and `Sc1431BudgetIntegritySchemaTest#v59UpgradeAndBudgetConstraintsAreDatabaseOwned`
(`expected <60> but was <86>`) — hard-coded versions that fail on `origin/main` itself, so they are
pre-existing, not a consequence of `V87`.

**Conclusion:** no candidate-only failure touches an SC-2150 file. The mouse-video package is green on
the candidate and on `origin/main`; the full-suite residue is missing host fixtures, ai-player
prerequisites and container/ordering artifacts. No disposition was invented to make a number look
better, and the aborted mid-run state of the first attempt is recorded rather than hidden.

## 2. Focused and repository checks

|Check|Command|Result|
|---|---|---|
|JS unit tests|`node --test src/test/js/mouse-video.test.js`|40 tests, **0 failed**|
|Repository JS tests|`npm test`|324 tests, **0 failed**, 3 platform skips (needed `jsdom@26.1.0`, installed locally)|
|SC-2150 Python tests|`python3 -m unittest discover -s src/test/python -p 'test_sc2150_*.py'`|**98 tests OK**|
|Harness syntax|`py_compile` on 5 harnesses, `node --check` on 2|clean|
|Checkstyle|`./gradlew checkstyleMain checkstyleTest`|**BUILD SUCCESSFUL**|
|Spotless|`./gradlew spotlessCheck`|fails on the 40 CRLF files that also fail on `origin/main` (the recorded D6 disposition). Three **changed** files violated and were fixed with google-java-format 1.17.0 (`053e3669`); a re-check confirms zero changed-file violations|
|Formatting re-verification|`com.google.googlejavaformat.Main --dry-run` over every changed Java file|no output (clean)|

## 3. Browser/API isolation and persistence on a fresh instance

Disposable instance `sc2150_r7_verify` on 18190 with the assisted profile (template from the private
durable path), then:

|Harness|Result|
|---|---|
|`qualify_http.py`|**PASS** — six generated fixtures, PTS-exact frame index, byte-identical previews, range 206/416, review contract 400/400/422/201|
|`qualify_browser.cjs`|**PASS** — visible point, two-page conflict, draft retention, region, suggestion-aware keyboard navigation, 390 px layout, axe zero violations, immutable export, reconciliation-required cancellation|
|`qualify_stale_preview.cjs`|**PASS** — “delayed frame 3 response cannot replace displayed frame 4”|
|`qualify_review_protocol.mjs`|**PASS** — retry posts 3/identical, exposure persists on reopen, blind claim `BLIND_UNVERIFIED`, storage-failure assisted path, no page errors|
|Restart persistence|media checksum, review revision 5, five revisions, observation artifact checksum and selected analysis identical before/after an owned restart; `nativeTelemetry`, `exactTicksAvailable`, `controlAllowed`, `trainingEligible` all `false`|

Receipts: `build/sc2150-closeout/r7/evidence/` (`browser-results.json`, `browser-a11y.json`,
`stale-preview-results.json`, `review-protocol.json`, `persistence-after-restart.json`,
`http-records.json`, `fixture-results.json`).

## 4. Deployed instance (8090)

Deployed runtime: `ChromaScape-runtime-sc2150` at `0581f7e2`, launcher-driven, profile and template
from `.env.local`. Full detail in [`r3-8090-restore-20261102.md`](r3-8090-restore-20261102.md).
Write smoke passes on the deployed instance
(`build/sc2150-closeout/r3/smoke-8090-runtime.json`), including after the launch path was corrected to
stop pinning the temporary worktree.

## 5. Verification matrix

|Area|Status|Receipt|
|---|---|---|
|Focused mouse-video package (candidate)|verified, 346/0/0|§1|
|Baseline comparison of that package|verified, 341/2/1 with both failures pre-existing|§1|
|Full Java suite|run, residue dispositioned; no candidate-only SC-2150 failure|§1|
|JS / npm / Python / checkstyle / formatting|verified|§2|
|Spotless|pre-existing CRLF violations only; changed files clean|§2|
|HTTP + browser mechanics|verified|§3|
|Stale-response and review-protocol isolation|verified|§3|
|Restart persistence|verified|§3|
|Assisted execution, cancellation, recovery, quota|verified|[`verification-evidence-r2-20261103.md`](verification-evidence-r2-20261103.md)|
|Assisted UI interaction checks (R1.3)|verified (9 assertions)|[`verification-evidence-r2-20261103.md`](verification-evidence-r2-20261103.md)|
|Durable 8090 runtime + write smoke|verified|[`r3-8090-restore-20261102.md`](r3-8090-restore-20261102.md)|
|Held-out isolation on 18191|verified (403 on observations/exports/analysis POST, `WITHOUT_SUGGESTIONS`)|[`verification-evidence-r2-20261103.md`](verification-evidence-r2-20261103.md)|
|44 manual labels lineage|verified read-only (44 in the latest revision, session `ABANDONED`)|[`verification-evidence-r2-20261103.md`](verification-evidence-r2-20261103.md)|
|AC5 independent accuracy|**deferred** — owner: story owner; target: separate accuracy follow-up|amendment approval|
|AC2 YouTube acquisition|**deferred** — owner: story owner; target: after the four D3 pins|`r6-youtube-status-20261103.md`|
|R4 native round trip / native AC12 half|**open** — owner: story owner (Micro6 login window)|`r4-native-status-20261103.md`|

## 6. Known limitations carried forward

- The full suite cannot be completed on this host as configured (missing SC-967 execution-closure
  fixture, no `chromascape_aiplayer_dev` database, container flakiness, one fork-order Mockito leak).
  The focused package is green on both trees and is the actionable signal for this story.
- `@playwright/test` and `@axe-core/playwright` are not declared in `package.json`; the browser
  harnesses need them installed explicitly on a fresh checkout.
- The deployed 8090 process is started by the external watchdog through
  `/tmp/start-8090-candidate.sh` (now delegating to the canonical launcher); it is not a systemd unit.
- Assisted suggestions remain unqualified detector output. No accuracy claim is made.
