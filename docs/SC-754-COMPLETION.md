# SC-754 corrective delivery plan and evidence

Canonical project: `C:\Dev\IdeaProjects\ChromaScape`. Existing Felix task: `task_1791128209780_3lwd52uq` (user-reported done; not updated here). Source: https://app.shortcut.com/leeuwbot/story/754.

The earlier five replay-only checks did not cover Shortcut's seven current acceptance criteria. This corrective implementation uses the current story as authority. Work is isolated from the unrelated dirty SC-275 checkout. CI monitoring and Felix remain skipped by standing user instruction; no Felix transition or synchronization marker is claimed.

## Scope and exclusions

Fix deterministic replay staging ownership, provide a local immutable artifact registry for all current/knowledge artifact classes, owner-scoped put/get/list, bounded checksum validation, atomic manifest publication, explicit quarantine/incomplete/corrupt behavior, exact immutable dependency/lineage references, a total storage quota, validated knowledge-snapshot activation, abandoned temporary-file cleanup and explicit development reset. Wire the current Fishing recording-source/dataset/manifest/model output and model reads through the registry, retaining output-path compatibility links.

Storage accepts bytes plus manifests; producer-specific semantic validation and provenance eligibility stay with their existing producers. Recording source manifests bind the frozen run context and exact training frame digests to existing immutable canonical recording frames. No video recorder, kernel execution, live dispatch, curation workflow, training algorithm, cloud storage, backup/restore, disaster-recovery certification, retention tiers or dependency-aware global garbage collector is added. Older unchecked local model files require retraining/explicit validated publication; they are not silently imported as valid evidence.

## Linked rules

Shared IdeaProjects AGENTS.md; repository AGENTS.md; current Shortcut description; SC-609 provenance/eligibility contract; existing replay fenced worker persistence. Preserve unrelated changes and avoid duplicate Felix tasks. No existing runtime or database is reset by this implementation.

## Given / When / Then acceptance and implementation checklist

1. Given clean local storage and an eligible canonical Fishing recording, when training outputs are stored, then the recording source, dataset, manifest and model resolve through immutable checksummed references. Implement the generic registry and wire Fishing writers/readers; test actual train output and corruption of a dependency.
2. Given an existing immutable ID, when identical bytes/metadata are published again, then the existing reference is reused; conflicting bytes/metadata fail. Test duplicate and concurrent writers, including reuse across owner namespaces.
3. Given interrupted preparation/publication, when a caller resolves the intended ID, then no incomplete artifact is valid. Force file writes and atomically link content/reference files; test abandoned temp/orphan bytes and complete reference visibility.
4. Given altered/missing content or a quarantined reference, when an artifact or dependent model is read, then stable errors block use. Verify content and manifest digests/size on every resolution; recursively verify dependencies; test each failure and restart reads.
5. Given a model/report/candidate manifest, when lineage is traversed, then exact immutable dependency IDs and source run/profile context are preserved. Support every required artifact kind; test Fishing model→dataset→recording-source lineage and owner isolation.
6. Given configured per-artifact and total quotas, when publication exceeds either bound, then it fails clearly without deleting existing referenced artifacts. Bound raw HTTP request reads and local verification; account for manifest bytes and unique stored objects; test limits and unchanged existing content.
7. Given artifact metadata requesting control, when it is published/read, then storage cannot alter feature state or enable gameplay. Keep metadata inert, derive API ownership from the authenticated principal, and preserve existing feature/eligibility guards.

## Implementation details

The generalized registry uses checksummed immutable JSON envelopes for references and shared SHA-256 objects. Both content and reference files are forced before atomic create-without-replacement via hard links; unsupported publication providers fail closed. A JVM mutex plus OS file lock serializes writers/reset/temp cleanup across store instances/processes. Content publication without a committed reference remains undiscoverable. Owner namespaces derive from a server-resolved principal, not request metadata. Dependent reads fail when any dependency is corrupt, missing or quarantined. Knowledge activation validates the snapshot kind and complete registered dependency tree before atomically switching an owner-scoped immutable ID/digest pointer; rejected candidates retain the previous pointer. This is storage activation only and cannot change gameplay control.

The old replay `artifact_refs` table remains fenced and unchanged. Rejected preparations no longer delete deterministic shared staging files. Retained replay staging may need terminal-run reconciliation; no stale attempt may authorize deleting another attempt's evidence.

## Test checklist

- Replay oversize preparation and reproduced stale-attempt/shared-staging deletion regression.
- Registry publication/reuse, concurrent instances, checksum/metadata corruption, missing bytes, dependency corruption, namespace isolation, quota/per-artifact rejection, interrupted temp/orphan publication, quarantine and explicit owner-scoped reset.
- Authenticated HTTP identity, reset profile restriction and raw request size bounds.
- Current Fishing train→registered outputs→model reload, exact lineage and dependency-tamper rejection.
- Existing fenced worker persistence regressions; scoped formatting and diff checks.

## Changed files

- `build.gradle.kts`: focused artifact completion test lane.
- `src/main/java/com/chromascape/web/replay/artifact/ReplayArtifactService.java` and corresponding reuse test.
- `src/main/java/com/chromascape/web/artifact/`: immutable store, bean configuration and owner-scoped HTTP API.
- `src/main/java/com/chromascape/web/aiplayer/prototype/FishingDatasetBuilderService.java` and dedicated registry integration test.
- `src/test/java/com/chromascape/web/artifact/`: registry and access/request-bound tests.
- This corrective plan/evidence document.

## Tests

Verified on Fedora with JDK 17.0.20.1:

`bash ./gradlew -I /tmp/sc754-spotless.gradle spotlessJavaApply artifactCompletionTest spotlessJavaCheck` — BUILD SUCCESSFUL. Production compileJava included. XML results: **57 tests, 0 failures, 0 errors, 0 skips**: registry 13, HTTP/access 4, Fishing integration 1, replay reuse 9, fenced worker persistence 30. Scoped Spotless checks and `git diff --check` pass. The temporary formatting init script restricts targets to changed Java files; it is not a build prerequisite.

The Fishing test executes the real training writer and model reader against a fresh filesystem, with mocked eligible canonical frames/run-context services. The HTTP test uses Spring MockMvc. This is not live-game, PostgreSQL-backed or power-loss qualification. Earlier reviewer evidence (42 tests in the dirty source checkout plus an expected failing race reproducer) remains separate.

## Acceptance results

1. PASS in integration harness: clean storage receives recording-source/dataset/manifest/model artifacts and reloads the model; canonical inputs are mocked, live training unrun.
2. PASS: identical ID/manifest/content reuse, conflicting content rejection and concurrent store instances.
3. PASS at filesystem publication boundary: abandoned temps/orphan bytes have no valid reference and cleanup retains existing evidence. Actual process/power-loss injection unrun.
4. PASS: corrupt/missing content, dependency tampering and quarantine fail closed.
5. PASS: registered model dependencies resolve back to the exact source recording/run context; all declared kinds retain metadata. Knowledge activation rejects invalid/corrupt candidates and preserves the previous digest pointer across store reopen.
6. PASS: artifact/quota bounds preserve existing references; both known-length and chunked HTTP requests are bounded.
7. PASS: artifact metadata is inert, owner comes from principal, production reset is forbidden, and training test keeps control disabled. No feature configuration mutation API is introduced.

Producer-specific knowledge/index schema and eligibility validation remains producer-owned; merely accepting a storage kind does not certify domain correctness. This corrective branch is ready for review, not yet merged or marked Done.

## API and configuration

Registry root: `${ai-player.dataset.output-dir}/artifacts`, default `.chromascape/datasets/artifacts`. `artifacts.max-artifact-bytes` defaults to 100 MiB; `artifacts.quota-bytes` to 1 GiB. The internal Fishing namespace is `fishing-prototype`; external namespace identity comes from the existing replay principal resolver.

`POST /api/replay/artifacts/v1` accepts bounded `application/octet-stream` bytes plus an `X-Artifact-Manifest` JSON header containing `id`, `kind`, `mediaType`, nonempty `lineage` and immutable-ID `dependencies`. GET list, `/{id}`, `/{id}/content` and `/{id}/state` verify references. Owner-scoped quarantine and explicit local-development-only reset are available. `/{id}/activate-knowledge` and `/active-knowledge` publish/read validated immutable snapshot digest references. No live service was restarted and no existing storage was reset during verification.

## Risks

The generalized local registry requires same-filesystem hard-link support (Fedora target); other providers fail closed. Owner reset deliberately retains shared blobs and does not perform global GC; retained/orphan bytes count against quota. Retained shared replay staging requires terminal-run reconciliation. Physical power-loss, Windows/provider qualification and full unrelated test suites are unrun. Old unregistered model files fail closed until retrained/explicitly validated. Domain producer validation remains separate from storage integrity.

## Rollback

Revert the corrective code commit, preserve all stored bytes/manifests and replay staging, and keep control disabled. No SQL migration or automatic shared-data reset is introduced. Restoring old readers would weaken checksum enforcement, so review rollback before reusing newly registered training artifacts.

## Remaining work

Review and merge the corrective PR. Perform live Fishing qualification if required beyond the canonical-input integration harness. Windows/provider, physical power-loss and PostgreSQL/full-suite qualification are unrun. Producer integrations beyond the current Fishing flow use the new registry API; implementing their domain workflows is outside this storage correction.

Felix task `task_1791128209780_3lwd52uq` remains untouched (earlier Done status is user-reported). Shortcut SC-754 was fetched and is still **To Do** (`500000007`); no transition/comment or Felix synchronization marker was posted. Reconcile the prior completion claim after the corrective change is integrated. Deferred backup/DR/global-GC tasks were not falsely checked off.
