# SC-584A post-fetch reconciliation verification pass

Date: 2026-08-15

## Scope

Implementation and regression coverage for the SC-584A post-fetch catalog reconciliation gaps in
ChromaScape: lifecycle re-resolution precedence, strict 202/200/503 runtime-contract validation,
header/runtime/payload identity binding, total schema-bundle binding, and bounded retry semantics.

## Environment

| Item | Value |
|---|---|
| Git revision | `556d086 Save current work` |
| Branch | `sc-572-remediation` |
| Working tree | 4 modified files + 1 untracked plan doc (uncommitted) |
| Operating system | Linux (WSL2) on Windows host |
| JDK | Temurin OpenJDK `17.0.19` (`/usr/lib/jvm/java-17-openjdk-amd64`) |
| Gradle | 8.14.2, `./gradlew --no-daemon` |

## Changed files

- `src/main/java/com/chromascape/web/integration/micro6/service/Micro6ExecutorCatalogService.java`
- `src/test/java/com/chromascape/web/integration/micro6/service/Micro6CatalogReconciliationTest.java`
- `src/test/java/com/chromascape/web/integration/micro6/service/Micro6ExecutorCatalogServiceTest.java`
- `src/test/java/com/chromascape/web/integration/micro6/service/Micro6CatalogEndToEndIntegrationTest.java`

## Commands and results

| Command | Result |
|---|---|
| `./gradlew --no-daemon test --tests '*Micro6*'` | PASS — 226 tests, 0 failures, 5 skipped |
| `npm test` | PASS — 140/140 JS tests |
| `./gradlew --no-daemon compileJava compileTestJava test` | FAIL — 1445 tests, 11 failures, 11 skipped; all 11 are pre-existing (identical on clean `556d086`) |
| `./gradlew --no-daemon spotlessCheck` | FAIL on 68 pre-existing unformatted files; all four changed files are spotless-clean |
| `./gradlew --no-daemon checkstyleMain` | PASS (warning-only; 5 pre-existing `MissingJavadocMethod` warnings on unchanged lines) |

## Focused regression coverage

`Micro6CatalogReconciliationTest` (24 tests, 0 failures):

- R1 missing lifecycle headers still triggers the post-fetch resolve (`IDENTITY_UNVERIFIED`, 2 resolves)
- R2/R3/R4 machine/executor/session change mid-fetch -> `RUNTIME_CHANGED`, no retry
- R5/R6 catalog hash drift settles / never settles -> `READY` / `RUNTIME_CHANGED`, exactly two fetches
- R7 GENERATING runtime + valid 202 headers -> `GENERATING`, no cache replacement
- R8 stable READY runtime receives stale 202 then 200 -> one re-fetch, `READY`
- R9 stable READY runtime receives two stale 202 -> `RUNTIME_CHANGED`, exactly two fetches
- R10 runtime becomes ready during 202 -> one re-fetch even without hash/version change
- R11 post-fetch catalog-hash loss + malformed 200 -> `CONTRACT_INVALID`, no stale fallback
- R12 post-fetch catalog-hash loss + 202 -> `CONTRACT_INVALID`
- R13 post-fetch catalog-hash loss + 503 with prior cache -> `CONTRACT_INVALID`, no stale
- R14 header catalog/schema lifecycle differs from stable runtime -> `IDENTITY_UNVERIFIED`
- R15 payload catalog state differs from matching header/runtime -> `IDENTITY_CONFLICT`
- R16 payload bundle with runtime/header not AVAILABLE -> `IDENTITY_CONFLICT`
- R17 lightweight payload with malformed bundle hash -> `IDENTITY_CONFLICT`
- R18 concurrent callers coalesce across the bounded retry (one owner, two fetches, one cache entry)

R19/R20 observed-at skew and TTL boundary tests in `Micro6ResponseIdentityValidationTest` are
unchanged and still pass.

## Pre-existing full-gate failures (not introduced by this change)

Verified identical on the clean base revision `556d086`:

- `ChromaScapeApplicationTests` (1) — Spring ApplicationContext load failure.
- `CurrentSliceIdentityValidatorTest` (1) — `CurrentSliceContractException: run group is required`.
- `SimulatorVerifyServiceIntegrationTest` (9 across Bridge Reachability / Job Cancellation /
  Job Lifecycle / Report Retrieval) — Spring ApplicationContext failure threshold.

These are outside SC-584 ownership and block release completion until waived elsewhere.

## Not run (per policy)

- Micro6 native-Windows verification (`gradlew.bat` in `micro6`): must run on native Windows, not
  WSL; no Micro6 build was executed from WSL.
- Real Fishing operational acceptance: requires a matching canonical account hash; not performed.
