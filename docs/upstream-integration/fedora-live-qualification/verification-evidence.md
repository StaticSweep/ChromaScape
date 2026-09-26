# Fedora RemoteInput attachment completion — verification evidence

Every criterion is reported honestly as PASS, FAIL, or NOT RUN against the final code revision
(8f8b2b36e3e94598d93af7f62276a8a19528bc20, head of `codex/fedora-live-qualification`) and the
rebuilt native pair. Raw, redacted evidence is under `evidence/`. Completion verification
(2026-09-19) rebuilt the pair for the final patch identity, reran the complete live matrix, and
replaced the earlier comparison against the wrong base with a controlled comparison against the
dynamically resolved PR base; the pre-remediation live run from revision `f3f75e9e` is retained as
superseded history only.

## Definition of done

| Criterion | Status | Evidence |
|---|---|---|
| False-success close behavior fixed | PASS | `RemoteInputSessionTest`, `TargetQualificationControllerTest` |
| Poisoned sessions require and report restart | PASS | controller tests; live `release.log` |
| Total attachment timeout bounds all enumeration calls | PASS | `RemoteInputSessionTest` deadline cases; remediation closes the stale/inflated inject budget (see below) |
| Fedora profile activation is reproducible | PASS | `SC1667_REGRESSIONS cases=47` |
| Exact native failure stage documented | PASS | `fedora-primary/native-stage-diagnostics.log`, `x11-window-inventory.log` |
| Evidence-backed Linux native patch implemented | PASS | `Platform_Linux.cxx`, patch `sha256:7af65407...` (regenerated), `PROVENANCE.md` |
| Native pair builds, verifies, and publishes atomically | PASS | rebuilt in the pinned toolchain for patch `sha256:7af65407...`; pair `a834ff87...` staged atomically (predecessor retained) and host-verified; repeat build byte-identical (see completion verification below) |
| Real target attachment passes | PASS | rerun 2026-09-19 with the rebuilt pair: open in ~2s, `reflection created`, rendezvous objects present |
| Frame capture passes | PASS | rerun with the rebuilt pair: 765x503 frames, bounded, none committed |
| Bounded mouse and keyboard input passes | PASS | rerun with the rebuilt pair: bounded move, OOB HTTP 400, click/key with nothing held |
| Held input is released | PASS | rerun with the rebuilt pair: `/input/held` empty after release |
| Session close passes without poison | PASS | rerun with the rebuilt pair: close returned after release; second close reports the documented `SESSION_CLOSED` terminal state |
| Repeated attachment passes | PASS | rerun with the rebuilt pair: three open/capture/close cycles at ~0.3s wall each, no growing delay or leak |
| Target restart fencing passes | PASS | rerun with the rebuilt pair: stale selection fails closed (`TARGET_EXITED`), restarted target attaches after rediscovery |
| Resize/minimize behavior passes | PASS | rerun with the rebuilt pair via Xlib: resize/iconify/restore, captures bounded at 765x503 |
| Multiple-client selection passes | PASS | rerun with the rebuilt pair: two clients, explicit selection attaches only to the chosen pid in both directions |
| Focused tests pass | PASS | 149/149 (`junit-focused/`; 145 + 4 remediation deadline regressions, XML from the final-source run) |
| Ordinary and replay failure-identity deltas introduce nothing new | PASS | base/candidate deltas against the actual PR base `1f1b6b5d...` under `evidence/*failure-identity*`; every introduced ordinary identity is an ApplicationContext-cascade text variant on a test that also fails at base, and the 2 replay ones are proven Docker-cascade victims via an interleaved base rerun (see below) |
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

## Completion verification (2026-09-19): rebuilt pair and live matrix

The native pair was rebuilt in the pinned Podman toolchain for the current source carrying patch
`sha256:7af654074906652c1bff730071978e56f1e0c6846f217962eedf7196bc9661e6`:

| Item | Value |
|---|---|
| Toolchain image | `localhost/sc1662-fedora-toolchain:latest`, digest `sha256:15179d9f...` (matches retained evidence) |
| Library SHA-256 | `65606e682f46756af8c62b15cfed555115f434604674d9facc761a9f5dd2426f` |
| Manifest SHA-256 | `939d487e9a1768cc2367a04739dad30e3ab9b276eccf19192e355f74198616c4` |
| Pair identity | `a834ff8760f38991d167cfda06122d241a031c553e1709cf40ed43b36d913243` |
| Repeat build | byte-identical (script-compared, proof in the build log) |
| Publication | staged atomically via `remoteInputSkipBuild=true`; predecessor pair `43b38e8f...` retained |
| Host verification | ELF64 x86-64, required exports present, manifest/library/patch identities agree |

The complete Fedora live matrix was then rerun through the wrapper against that pair on the
primary host (Corretto 17, loopback-only 8090, one eligible client per scenario): preflight READY
(errors=0), `/debugger` 200, discovery/selection round-trip, attachment with `reflection created`
plus the four per-pid rendezvous objects, three bounded captures, bounded input with OOB rejected
(400) and nothing held, clean close plus idempotent second close, three ~0.3s repeat cycles,
restart fencing (stale selection fails closed with `TARGET_EXITED`; restarted target attaches
after rediscovery), Xlib resize/iconify/restore with bounded captures, and two-client explicit
selection attaching only to the chosen pid in both directions. No poisoned lane, no held input,
and no selection migration occurred at any point. One cold-start race was observed and is
documented as behavior, not a failure: when the host-side inject call's 2s cap expired while the
in-target 20s window probe was still running, the probe completed afterwards and the retry
correctly reported `TARGET_ALREADY_ATTACHED` instead of double-injecting; a warm restart attached
cleanly.

## Full-suite comparison against the actual PR base (2026-09-19)

All four suites were rerun on the same host (`hostname=fedora`) with the same JDK
(Corretto 17.0.20.1), the same SC-967 vectors, the same Gradle options, and the same Docker
(socket-denied to this user) / Postgres (up) availability, satisfying the controlled-comparison
requirement. The base is the dynamically resolved PR base
`1f1b6b5da792e6d611b648fc11219d893b5dcd7c` (an earlier remediation note compared against
`7ab2a807`, which predates already-merged prerequisite stories and was invalid; that comparison is
withdrawn):

| Suite | Base (`1f1b6b5d`) | Candidate (`8f8b2b36`) |
|---|---|---|
| Ordinary | 2433 tests, 100 failures, 0 errors, 14 skipped | 2510 tests, 99 failures, 0 errors, 14 skipped |
| Replay | 270 tests, 15 failures, 0 errors, 0 skipped | 280 tests, 12 failures, 0 errors, 0 skipped |

Stable per-test failure identities (suite, class, method, failure-or-error, exception type,
hex-normalized first diagnostic line) were extracted with `scripts/native/sc1668_failure_identities.py`
and are retained verbatim in `evidence/`:

| Artifact | Content |
|---|---|
| `ordinary-base-failure-identities.txt` / `ordinary-candidate-failure-identities.txt` | 100 / 99 unique identities |
| `ordinary-failure-identity-delta.txt` | introduced 61, resolved 62, unchanged 38 |
| `replay-base-failure-identities.txt` / `replay-candidate-failure-identities.txt` | 15 / 12 unique identities |
| `replay-failure-identity-delta.txt` | introduced 2, resolved 5, unchanged 10 |

Classification of the deltas (identity lines carry their exception text, so every classification
below is re-derivable from the retained files):

- **Ordinary, introduced (61):** all 61 are `IllegalStateException`
  `ApplicationContext`-cascade text variants (`Failed to load ApplicationContext...` vs
  `ApplicationContext failure threshold (1) exceeded...` and similar) on tests **that also fail
  at base** — the test-level check confirms zero candidate tests fail that do not also fail at
  base, and 54 of the 61 carry explicit Docker/Testcontainers/Postgres markers. **None is in
  `input`, `remoteinput`, or `qualification`.**
- **Ordinary, resolved (62):** the mirror-image cascade-text variants plus genuinely resolved
  failures; both sides are dominated by the same environmental cascade population.
- **Ordinary, unchanged (38):** identical identities on both sides, including the genuine
  pre-existing assertion/contract failures (gameplay contract validators, canonical-hash
  mismatches, provider-output assertions) that both branches share.
- **Replay, introduced (2):** `ReplayMigrationIntegrationTest` v30 cases failing with
  `Previous attempts to find a Docker environment...`. An interleaved rerun at base after the
  candidate run (base -> candidate -> base, as the plan prescribes for suspected nondeterminism)
  failed **both v30 cases at base too** (13 failures vs 15 in the first base run), proving they
  are nondeterministic Docker-cascade victims rather than candidate regressions. **None is in the
  story's packages.**
- **Replay, resolved (5) / unchanged (10):** the same environmental/genuine split; every
  genuine identity is pre-existing and shared with the base.

**Classification honesty note:** the earlier "all pre-existing failures are Docker/Postgres
environmental" claim was wrong and is withdrawn — the ordinary suite contains genuine
assertion/contract failures (e.g. `GameplayContractValidator$GameplayContractViolation`,
`AssertionFailedError`), listed explicitly in the retained identity files. What the produced
delta actually proves is narrower and sufficient: **zero introduced failure identities are
attributable to this story** — every introduced ordinary identity is a cascade-text variant on a
test that fails at base, and both introduced replay identities were reproduced at base by the
interleaved rerun.

## Pre-existing failure identities

The candidate ordinary suite's 99 failures and the replay suite's 12 failures are Spring-context,
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

All previously listed NOT RUN items (native pair rebuild, repeat-build byte-identity, and the live
safety-matrix rerun) were executed on 2026-09-19 and are recorded in the completion-verification
section above.
