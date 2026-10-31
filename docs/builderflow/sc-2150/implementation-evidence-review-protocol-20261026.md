# SC-2150 implementation evidence — review protocol hardening (2026-10-26)

Project: `C:\Dev\IdeaProjects\ChromaScape`.
Felix task: `task_1792382767281_cd8dbu69` (existing; no new task created).
Shortcut: https://app.shortcut.com/leeuwbot/story/2150 (remains In Progress).
Authoritative plan: `docs\builderflow\sc-2150\fedora-remaining-implementation-plan-20261026.md`.
Increment: F1 policy/session contract and F3 assembly hardening. This note does not claim any
package complete and does not move the tracker to review or done.

## Changed files

- `build.gradle.kts` — test-process-only `api.version` compatibility from `DOCKER_API_VERSION`; no
  daemon or application setting is changed.
- `src\main\java\com\chromascape\web\debug\tickdebugger\video\controller\MouseVideoController.java`
  — review-session policy dependency is required (constructor injection). Removed the optional
  `@Autowired(required=false)` fallback and every `reviewSessions != null` bypass so production
  cannot serve prediction-bearing routes without the exposure guard.
- `src\main\java\com\chromascape\web\debug\tickdebugger\video\controller\MouseVideoReviewSessionController.java`
  — session create and snapshot submit return 201 for new durable state and 200 for an identical
  retry.
- `src\main\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoReviewSessionService.java`
  — `Result(body, created)` distinction; coordinate-plane derivation, persistence, policy exposure,
  stale-plane refusal and sealed-export inclusion.
- `src\main\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoCoordinatePlane.java`
  (new) — canonical `coordinate-plane/v1` identity from the analysis-owned manifest, with coded and
  display dimensions, rotation, origin and pixel-centre convention plus a stable digest.
- `src\main\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoAnalysisWorker.java`
  — visual analysis manifests now publish the same coordinate plane.
- `src\main\resources\db\migration\V48__sc2150_review_coordinate_plane.sql` (new) — additive nullable
  `coordinate_plane_identity` and `coordinate_plane_digest` columns; historical rows stay unknown.
- `scripts\sc2150\labels_from_review_export.py` — sealed v3 conversion requires the authoritative
  plane and refuses `--source-size` drift or an output plane larger than the source plane.
- `scripts\sc2150\assemble_label_set.py` (new) — `sc2150-fa12-labelset/v1` assembler for multiple
  sealed sessions. Pins source/media/template/index/plane/split identity, refuses overlapping or
  conflicting sessions, refuses assisted or unknown origins, refuses missing required ranges or
  stride coverage, and reports coverage without interpolating labels.
- Tests:
  - `src\test\java\...\video\MouseVideoReviewSessionPostgresTest.java` (V48, plane pin/refusal,
    201/200 `created` distinction, legacy upgrade remains intact)
  - `src\test\java\...\video\controller\MouseVideoReviewSessionControllerTest.java` (new)
  - `src\test\java\...\video\service\MouseVideoCoordinatePlaneTest.java` (new)
  - `src\test\java\...\video\controller\MouseVideoControllerHttpTest.java` (required collaborator)
  - `src\test\python\test_sc2150_labels_from_review_export.py` (plane refusal cases)
  - `src\test\python\test_sc2150_assemble_label_set.py` (new)

## Tests

Executed on this Fedora host with JDK 21 as the Gradle launcher and the configured Java 17
toolchain. `SC967_VECTORS_PATH` pointed at the local `contracts\sc967\ed25519-vectors.json`.
PostgreSQL tests were real Testcontainers instances. Docker 29 requires an explicit API floor, so
only the test process used `DOCKER_API_VERSION=1.44` (now honored through the opt-in Gradle wiring).

- Whole external-video Java package:
  `./gradlew test --tests 'com.chromascape.web.debug.tickdebugger.video.*'`
  — **301 tests, 300 passed, 1 opt-in corpus skip, 0 failures, 0 errors**. The skip is
  `MouseVideoCursorCorpusHarnessTest`, which still requires `SC2150_FA12_CORPUS`; it is not a pass.
- `MouseVideoReviewSessionPostgresTest`: **13 passed, 0 skipped**, including V46→V48 upgrade with
  legacy rows, immutable split refusal, concurrent exposure/session barriers, bounded history,
  201/200 reuse semantics, and coordinate-plane pin plus stale-plane conflict.
- `MouseVideoMigrationTest`: **5 passed, 0 skipped**.
- JS suite (`npm test`, 370 tests): **370 passed, 0 failures, 0 skipped**.
- Python suite (`python3 -m unittest discover -s src/test/python`): **87 passed, 0 failures**.
- `spotlessJavaCheck` passed after formatting the four owned files; `checkstyleMain` exited 0 with
  pre-existing warnings outside the changed packages.
- `CdiStartupRegressionTest` was attempted and failed while building the unrelated test context:
  duplicate `objectMapper` test-configuration beans from
  `BootstrapApprovalStorePostgresTest`/`BootstrapRecordingCoordinatorTest`. This is not evidence
  about the changed classes and was not used as a pass.

## Remaining work

- Real Spring/PostgreSQL/Chromium 60-label mechanics run, delayed nonce/POST/history races, lost
  response with later edits, sealed read-only browser proof and the registered auth/nonce/Origin
  matrix (F2/F3).
- Owner-supplied independent labels, fresh held-out top-up, contamination ledger and frozen corpus
  (F4). No labels or rights were invented here.
- Dev library analysis tooling, measured cursor successor and parity (F5); additional declared
  observations/capabilities (F6); frozen evaluation (F7).
- Product YouTube sandbox/proxy/egress work (F8); Fedora containment/recovery and owner native
  round trip (F9); full regression/readiness/rollback rehearsal on one frozen candidate (F10).
- Felix/Shortcut review and done transitions remain gated. This increment only hardens the protocol
  and assembly boundary; it is not a delivery.
