# Shortcut MCP Tracker Sync Instructions (SIM-04 / Gate-01)

## Stories to Update

### sc-479 (SIM-04: Simulator Verifier Integration)

**Status:** Move to **Done** (was: To Do)

**Comment to add:**

```
SIM-04 Closure Complete - 2026-04-06

## Changes Implemented
1. OpenRune test dependency conflict resolved (kotlin-test-junit vs kotlin-test-junit5)
2. MainErrorReportTest strengthened (6/6 tests pass) - covers error report structure, serialization, fallback JSON, and runtime-failure CLI invocation
3. ShadowJar artifact rebuilt: simulator-verifier-all.jar (35 MB)
4. ChromaScape synthetic report generation added for exception paths (timeout, bridge unreachable)
5. OverlayBroadcaster gated via @ConditionalOnProperty to reduce test noise
6. Nullable receiver compilation errors fixed in MonotonicValidatorTest and ActionTest
7. MCP timeout default normalized to 120 (verify-codex-mcp-servers.js, MCP_QUICK_START.md)
8. Non-evidence runtime crash/log artifacts removed and gitignored (hs_err_pid*.log, openrune-*.log)

## Evidence Artifacts
- OpenRune test results: /mnt/f/Dev/IdeaProjects/OpenRune-Server/simulator-verifier/build/test-results/test/TEST-org.alter.simulator.verifier.MainErrorReportTest.xml (6/6 pass, timestamp 2026-04-06T15:51:00Z)
- OpenRune artifact: /mnt/f/Dev/IdeaProjects/OpenRune-Server/simulator-verifier/build/libs/simulator-verifier-all.jar (35 MB, timestamp 2026-04-06)
- ChromaScape test results: /mnt/f/Dev/IdeaProjects/ChromaScape/build/test-results/test/ (8/9 pass, 1 environment-constrained)
- ChromaScape test report: /mnt/f/Dev/IdeaProjects/ChromaScape/build/reports/tests/test/index.html
- OpenRune evidence doc: /mnt/f/Dev/IdeaProjects/OpenRune-Server/docs/SIM-04-Gate01-Evidence.md
- ChromaScape evidence doc: /mnt/f/Dev/IdeaProjects/ChromaScape/docs/simulator/SIM-04-Gate01-Evidence.md

## Gate Status: CONDITIONAL PASS
All error report generation paths verified. OpenRune verifier tests 6/6 pass (including runtime-failure CLI test). ChromaScape integration tests 8/9 pass; 1 test (shouldReachTerminalFailedStatusWithReportAvailableWhenExitCodeTwo) requires live bridge at localhost:8092 and Windows-native environment (User32/JNA dependencies) - environment constraint, not code defect. Code path verified via unit tests and review.

## Files Modified
- OpenRune-Server/simulator-verifier/build.gradle.kts
- OpenRune-Server/simulator-verifier/src/test/kotlin/org/alter/simulator/verifier/MainErrorReportTest.kt
- OpenRune-Server/simulator-verifier/src/test/kotlin/org/alter/simulator/verifier/MonotonicValidatorTest.kt
- OpenRune-Server/simulator-verifier/src/test/kotlin/org/alter/simulator/verifier/model/ActionTest.kt
- OpenRune-Server/.gitignore (added hs_err_pid*.log, openrune-*.log patterns)
- ChromaScape/src/main/java/com/chromascape/web/simulator/SimulatorVerifyService.java
- ChromaScape/src/main/java/com/chromascape/web/debug/OverlayBroadcaster.java
- ChromaScape/src/test/resources/application-test.yml
- ChromaScape/scripts/mcp-bridge/verify-codex-mcp-servers.js (timeout default 90 -> 120)
- ChromaScape/docs/MCP_QUICK_START.md (timeout examples updated to 120)
- ChromaScape/docs/simulator/SIM-04-Gate01-Evidence.md
- OpenRune-Server/docs/SIM-04-Gate01-Evidence.md
```

### sc-397 (Gate-01 Dependency)

**Status:** Update only after sc-479 is updated and evidence attached.

**Comment to add:**

```
SIM-04 Gate-01 evidence attached to sc-479. Gate status: CONDITIONAL PASS.

Evidence links:
- OpenRune: /mnt/f/Dev/IdeaProjects/OpenRune-Server/docs/SIM-04-Gate01-Evidence.md
- ChromaScape: /mnt/f/Dev/IdeaProjects/ChromaScape/docs/simulator/SIM-04-Gate01-Evidence.md
- Test artifacts: simulator-verifier/build/test-results/test/ (6/6 pass), ChromaScape/build/test-results/test/ (8/9 pass)
- Latest run timestamp: 2026-04-06T15:51:00Z

Note: SIM-04 is CONDITIONAL PASS (not fully unblocked). One ChromaScape integration test remains environment-constrained (requires live bridge + Windows-native environment). All code paths are verified via unit tests and code review. This dependency should be reviewed in context of the conditional verdict before marking fully unblocked.
```

## MCP Verification Timeout Alignment

The codex chromascape timeout default is 120 seconds. Updated in:
- `scripts/mcp-bridge/verify-codex-mcp-servers.js`: default expectedChromascapeTimeout changed from 90 to 120
- `docs/MCP_QUICK_START.md`: all examples and output snippets updated to reflect 120s default
- Probe scripts accept `--expectedChromascapeTimeout 120` override (still works, but no longer required)

## Verification Commands (for reference)

```bash
# OpenRune tests
cd OpenRune-Server
./gradlew :simulator-verifier:test --tests 'org.alter.simulator.verifier.MainErrorReportTest'
./gradlew :simulator-verifier:shadowJar

# ChromaScape tests (requires Java 17 + live bridge + Windows-native environment)
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
cd ChromaScape
./gradlew test --tests 'com.chromascape.web.simulator.SimulatorVerifyServiceIntegrationTest'

# MCP probe
cd ChromaScape/scripts/mcp-bridge
npm run probe:mcp:servers
npm run probe:mcp:shortcut:smoke
```
