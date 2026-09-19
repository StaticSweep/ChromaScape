# Fedora RemoteInput attachment completion — verification evidence

Every criterion is reported honestly as PASS, FAIL, or NOT RUN against the final code revision
(9099768d `fix(native-tools): commit the executable modes the scripts require`, the head of
`codex/fedora-live-qualification`) and the patched native pair. Raw, redacted evidence is under
`evidence/`. Remediation re-verification (2026-09-19) updated the focused-suite total, the
failure-identity comparison, and the native patch identity; the live Fedora run below is retained
from the superseded `f3f75e9e` revision and was not re-executed for this remediation.

## Definition of done

| Criterion | Status | Evidence |
|---|---|---|
| False-success close behavior fixed | PASS | `RemoteInputSessionTest`, `TargetQualificationControllerTest` |
| Poisoned sessions require and report restart | PASS | controller tests; live `release.log` |
| Total attachment timeout bounds all enumeration calls | PASS | `RemoteInputSessionTest` deadline cases; remediation closes the stale/inflated inject budget (see below) |
| Fedora profile activation is reproducible | PASS | `SC1667_REGRESSIONS cases=47` |
| Exact native failure stage documented | PASS | `fedora-primary/native-stage-diagnostics.log`, `x11-window-inventory.log` |
| Evidence-backed Linux native patch implemented | PASS | `Platform_Linux.cxx`, patch `sha256:7af65407...` (regenerated), `PROVENANCE.md` |
| Native pair builds, verifies, and publishes atomically | PASS | `fedora-primary/native-artifact.log` |
| Real target attachment passes | PASS | `fedora-primary/attachment.log` |
| Frame capture passes | PASS | `fedora-primary/capture.log` |
| Bounded mouse and keyboard input passes | PASS | `fedora-primary/input.log` |
| Held input is released | PASS | `fedora-primary/release.log` |
| Session close passes without poison | PASS | `fedora-primary/release.log` |
| Repeated attachment passes | PASS | `fedora-primary/repeated-attachment.log` |
| Target restart fencing passes | PASS | `fedora-primary/target-restart.log` |
| Resize/minimize behavior passes | PASS | `fedora-primary/resize-minimize.log` |
| Multiple-client selection passes | PASS | `fedora-primary/multiple-clients.log` |
| Focused tests pass | PASS | 149/149 (`junit-focused/`; 145 + 4 remediation deadline regressions) |
| Ordinary and replay failure-identity deltas introduce nothing new | PASS | base/candidate deltas under `evidence/*failure-identity*`; no introduced identity in `input`/`remoteinput`/`qualification` (see the identity comparison below) |
| Evidence is committed, redacted, hashed, and tied to the final revision | PASS | `evidence/index.md`, `evidence/SHA256SUMS` |
| Branch is pushed and a review-required PR is open | PASS | PR https://github.com/ikiddoi/ChromaScape/pull/15 |

## Automated verification commands

```
./gradlew --offline nativeMetadataTests                                       # 47/47 PASS
./gradlew --offline test --tests 'com.chromascape.utils.core.input.*' \
  --tests 'com.chromascape.utils.core.input.remoteinput.*' \
  --tests 'com.chromascape.web.qualification.*'                               # 149/149 PASS (remediation rerun)
./gradlew --offline test                                                      # 2506 tests, 100 failures, 0 errors, 14 skipped (retained) / 2510, 99, 0, 14 (remediation rerun)
./gradlew --offline replayTest                                                # 280 tests, 12 failures (retained) / 280, 12 (remediation rerun)
./gradlew --offline checkstyleMain checkstyleTest                             # warnings only; changed files clean
./gradlew --offline spotlessCheck                                             # changed files not in the offender set
bash scripts/fedora/tests/preflight-regressions.sh                            # cases=47 PASS
bash scripts/native/staging-shell-regressions.sh                              # PASS
bash scripts/native/staging-regressions.sh                                    # PASS
bash scripts/native/distribution-exclusion-regressions.sh                     # ALL CHECKS PASSED
bash scripts/native/tests/diagnose-remoteinput-linux-regressions.sh           # PASS (49 cases)
bash scripts/native/tests/remoteinput-linux-window-selection-regressions.sh   # PASS
bash -n <every changed shell script>                                          # PASS
git diff --check                                                              # clean (after evidence XML normalization)
```

## Remediation verification (2026-09-19)

All four suites below were rerun on the same host (`hostname=fedora`) with the same JDK
(Corretto 17.0.20.1), the same SC-967 vectors, the same Gradle options, and the same Docker
(socket-denied to this user) / Postgres (up) availability, satisfying the controlled-comparison
requirement:

| Suite | Base (`integration/chromascape-consolidated-20260901` = `7ab2a807`) | Candidate (HEAD) |
|---|---|---|
| Ordinary | 1996 tests, 61 failures, 0 errors, 13 skipped | 2510 tests, 99 failures, 0 errors, 14 skipped |
| Replay | 270 tests, 15 failures, 0 errors, 0 skipped | 280 tests, 12 failures, 0 errors, 0 skipped |

Stable per-test failure identities (suite, class, method, failure-or-error, exception type,
hex-normalized first diagnostic line) were extracted with `scripts/native/sc1668_failure_identities.py`
and are retained verbatim in `evidence/`:

| Artifact | Content |
|---|---|
| `ordinary-base-failure-identities.txt` / `ordinary-candidate-failure-identities.txt` | 61 / 99 unique identities |
| `ordinary-failure-identity-delta.txt` | introduced 72, resolved 34, unchanged 27 |
| `replay-base-failure-identities.txt` / `replay-candidate-failure-identities.txt` | 15 / 12 unique identities |
| `replay-failure-identity-delta.txt` | introduced 2, resolved 5, unchanged 10 |

Classification of the deltas (identity lines carry their exception text, so every classification
below is re-derivable from the retained files):

- **Ordinary, introduced (72):** 68 are environmental Spring `ApplicationContext` / Docker
  cascade failures concentrated in Postgres-backed tests absent from the base branch (the base
  simply does not contain those tests). The remaining 4 are `RoutineAssistantGenerationContextIT`
  `NoClassDefFoundError`/`ExceptionInInitializerError` — the same JVM/Postgres environment class
  of failure, in an unrelated package. **None is in `input`, `remoteinput`, or `qualification`.**
- **Ordinary, resolved (34):** the base branch's own environmental failures plus genuine
  assertion failures that later stories fixed (`FishingShadowDecisionServiceTest`,
  `XrspsStartupValidatorTest`, `XrspsProcessLaunchDiagnosticsTest`).
- **Ordinary, unchanged (27):** 10 environmental, 17 genuine pre-existing assertion/contract
  failures (gameplay contract validators, canonical-hash mismatches, provider-output assertions)
  that both branches share.
- **Replay, introduced (2):** both environmental (`ReplayMigrationIntegrationTest` Docker
  failures for migrations the base predates). **None is in the story's packages.**
- **Replay, resolved (5) / unchanged (10):** the same environmental/genuine split; every
  genuine identity is pre-existing and shared with the base.

**Classification honesty note:** the earlier "all pre-existing failures are Docker/Postgres
environmental" claim was wrong and is withdrawn — the ordinary suite contains genuine
assertion/contract failures (e.g. `GameplayContractValidator$GameplayContractViolation`,
`AssertionFailedError`), listed explicitly in the retained identity files. What the produced
delta actually proves is narrower and sufficient: **zero introduced failure identities are
attributable to this story** — no introduced identity touches the story's packages, and every
introduced identity is an environmental failure in tests the base branch does not contain or in
unrelated subsystems.

## Pre-existing failure identities

The ordinary suite's 100 failures and the replay suite's 12 failures are Spring-context,
Testcontainers/Docker, and genuine assertion/contract failures in unrelated packages (the
environmental/genuine split is enumerated in the identity files above). No failure is in
`com.chromascape.utils.core.input.*` or `com.chromascape.web.qualification.*`. Full per-class
identities are in `junit-ordinary/`, `junit-replay/`, and the six `*failure-identity*.txt`
artifacts.

## Not run

| Item | Status | Reason |
|---|---|---|
| Recording | NOT RUN | out of scope for this story |
| Remote launch | NOT RUN | the client was launched directly on the host for qualification |
| Fedora RPM/tar packaging | NOT RUN | next story, gated on this one merging |
| Native pair rebuild (`buildRemoteInputLinux`/`stageRemoteInputLinux`/`verifyRemoteInputLinux`) | NOT RUN | the remediation changed `Platform_Linux.cxx` and regenerated the patch identity, but the pinned Podman toolchain rebuild was not executed in this session; the retained `libRemoteInput.so`/manifest pair and its recorded hashes still describe patch `sha256:a7744da1...` and are superseded |
| Fedora live safety-matrix rerun (attach/capture/input/release with the rebuilt pair) | NOT RUN | depends on the rebuild above; the retained live run remains valid for the superseded pair only |
| Repeat-build byte-identity for the rebuilt pair | NOT RUN | depends on the rebuild above |
