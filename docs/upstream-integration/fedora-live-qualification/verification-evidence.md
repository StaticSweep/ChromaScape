# Fedora RemoteInput attachment completion — verification evidence

Every criterion is reported honestly as PASS, FAIL, or NOT RUN against the final code revision
(`f3f75e9e`) and the patched native pair. Raw, redacted evidence is under `evidence/`.

## Definition of done

| Criterion | Status | Evidence |
|---|---|---|
| False-success close behavior fixed | PASS | `RemoteInputSessionTest`, `TargetQualificationControllerTest` |
| Poisoned sessions require and report restart | PASS | controller tests; live `release.log` |
| Total attachment timeout bounds all enumeration calls | PASS | `RemoteInputSessionTest` deadline cases |
| Fedora profile activation is reproducible | PASS | `SC1667_REGRESSIONS cases=47` |
| Exact native failure stage documented | PASS | `fedora-primary/native-stage-diagnostics.log`, `x11-window-inventory.log` |
| Evidence-backed Linux native patch implemented | PASS | `Platform_Linux.cxx`, patch `sha256:a7744da1...`, `PROVENANCE.md` |
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
| Focused tests pass | PASS | 145/145 (`junit-focused/`) |
| Ordinary and replay failure-identity deltas introduce nothing new | PASS | 100 ordinary + 12 replay failures, all pre-existing Docker/Postgres; none in input/qualification |
| Evidence is committed, redacted, hashed, and tied to the final revision | PASS | `evidence/index.md`, `evidence/SHA256SUMS` |
| Branch is pushed and a review-required PR is open | PASS | PR https://github.com/ikiddoi/ChromaScape/pull/15 |

## Automated verification commands

```
./gradlew --offline nativeMetadataTests                                       # 47/47 PASS
./gradlew --offline test --tests 'com.chromascape.utils.core.input.*' \
  --tests 'com.chromascape.utils.core.input.remoteinput.*' \
  --tests 'com.chromascape.web.qualification.*'                               # 145/145 PASS
./gradlew --offline test                                                      # 2506 tests, 100 pre-existing failures, 14 skips
./gradlew --offline replayTest                                                # 280 tests, 12 pre-existing failures
./gradlew --offline checkstyleMain checkstyleTest                             # warnings only; changed files clean
./gradlew --offline spotlessCheck                                             # changed files not in the offender set
bash scripts/fedora/tests/preflight-regressions.sh                            # cases=47 PASS
bash scripts/native/staging-shell-regressions.sh                              # PASS
bash scripts/native/staging-regressions.sh                                    # PASS
bash scripts/native/distribution-exclusion-regressions.sh                     # ALL CHECKS PASSED
bash scripts/native/tests/diagnose-remoteinput-linux-regressions.sh           # PASS
bash scripts/native/tests/remoteinput-linux-window-selection-regressions.sh   # PASS
bash -n <every changed shell script>                                          # PASS
git diff --check                                                              # clean
```

## Pre-existing failure identities

The ordinary suite's 100 failures and the replay suite's 12 failures are all Spring-context or
Testcontainers/Docker failures in unrelated packages. No failure is in
`com.chromascape.utils.core.input.*` or `com.chromascape.web.qualification.*`. Full per-class
identities are in `junit-ordinary/` and `junit-replay/`.

## Not run

| Item | Status | Reason |
|---|---|---|
| Recording | NOT RUN | out of scope for this story |
| Remote launch | NOT RUN | the client was launched directly on the host for qualification |
| Fedora RPM/tar packaging | NOT RUN | next story, gated on this one merging |
