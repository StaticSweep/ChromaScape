# Implementation evidence — SC-274 provenance identity (3 November 2026)

Commit scope: the code change that makes the reuse guard compare *canonical* capture identity and
that makes both refusal sites name the differing fields. It does **not** take the capture, and it
does not claim the live `IMMUTABLE_PROVENANCE` refusal is explained: the field is named by the new
WARN on the next attempt (P2.3a).

## Changed files

| File | Change |
|---|---|
| `src/main/java/com/chromascape/web/recording/provenance/BehaviorRunContextService.java` | `sameProvenance(expected, stored)`: a bound context is identified by its canonical digest, both digests are re-verified before they are trusted, and numbers are compared canonically. `differingPaths(expected, stored)`: bounded (16 fields, depth 3) field-path diagnostic that never returns values. |
| `src/main/java/com/chromascape/web/debug/tickdebugger/service/DebugSessionService.java` | The preferred-session reuse guard calls `sameProvenance` instead of `JsonNode.equals` and logs `differingFields`. The second refusal site (task key / settings hash, reached when the client start carries no run context) now logs which of the two fields differs instead of throwing silently. |
| `src/main/java/com/chromascape/web/debug/tickdebugger/websocket/TickStreamWebSocketHandler.java` | The 3 November DEBUG diagnostic is kept but redacted: it logs presence and top-level field *counts* of `taskArgs`/`runContext`, never the body. The previous version serialized the payload, which would have written the account name and task settings to the log. |
| `src/test/java/com/chromascape/web/recording/provenance/BehaviorRunContextIdentityTest.java` | New. Canonical identity: number representation alone keeps one identity; a changed value, a changed recording, a changed session, a tampered stored digest and a missing digest are all still refused; the diagnostic names fields and not values. |
| `src/test/java/com/chromascape/web/debug/tickdebugger/service/DebugSessionProvenanceReuseTest.java` | New. End-to-end on the service with mocked stores: an echo of the armed capture whose only difference is floating-point number representation is accepted and the stored context is returned; a different executor is refused `IMMUTABLE_PROVENANCE`; a different task key on a reused recording is refused at the settings guard. |

Why the guard was stricter than its own contract: `contextHash` is the capture identity every
downstream consumer uses, and `Micro6CanonicalHash` hashes numbers canonically (`5` and `5.0` are
the same digest). The old guard compared raw Jackson trees, so a representation-only difference was
refused even though no consumer could see any difference. Nothing is loosened: a single differing
value, recording id or session id still refuses, and a stored digest that no longer verifies its own
tree is refused outright.

## Tests

| Lane | Command | Result |
|---|---|---|
| Focused provenance | `gradlew.bat --offline test --tests com.chromascape.web.recording.provenance.BehaviorRunContextIdentityTest --tests com.chromascape.web.debug.tickdebugger.service.DebugSessionProvenanceReuseTest --tests com.chromascape.web.debug.tickdebugger.service.DebugSessionServiceTest --tests com.chromascape.web.recording.provenance.HumanFishingFinalizationTest` | **31 tests, 0 failures** (6 new identity, 3 new reuse, 20 existing session, 2 existing finalization) |
| Full replay lane | `gradlew.bat --offline replayTest` | **871 tests, 26 failed, 42 skipped.** 16 failures are the recorded environmental baseline (`wp0/baseline-failures.txt`). The other 10 are all `ContainerLaunchException: Container startup failed for image postgres:16-alpine` — Docker Desktop is not running on this host (`docker version` → pipe not found). No new behavioural failure. |
| Checkstyle | `gradlew.bat --offline checkstyleMain checkstyleTest` | Build succeeds; 4837 pre-existing warnings across 692 files. The touched files add no new class of violation. |

## Remaining work

1. P2.3a: one lease, one instrumented start, read `Immutable provenance mismatch ... differingFields=[…]`
   — this is the only way to name the live cause.
2. `spotlessApply` on this branch reformats 1384 unrelated files, so it was applied to the touched
   files only and the unrelated churn was reverted. `spotlessCheck` cannot be green on this branch
   without that mass reformat; that is a pre-existing repository condition, not a result of this change.
3. P5.4 (clean candidate build), P5.5 (push/PR — needs approval), P6 (Felix/outbox/Shortcut).
