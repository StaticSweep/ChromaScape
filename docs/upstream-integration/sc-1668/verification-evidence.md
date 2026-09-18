# SC-1668 verification evidence

Status vocabulary: PASS / FAIL / NOT RUN / BLOCKED.

Base: `ab8c4ccf43e09ce5d52b5e75a712e672984d1561`.

## Current results

| Check | Result | Evidence |
|---|---|---|
| AWT startup mode, first-cycle hook, and profile path | PASS | focused Java regressions; 8 tests |
| Cross-host evidence contract | PASS | Python/Gradle fixture suite; 8 tests |
| Fedora preflight fixture suite | PASS | 19/19 deterministic cases |
| Native metadata suite | PASS | 47/47 tests |
| SC-1662–SC-1667 focused Java suite | PASS | 490 tests, 0 failures/errors, 7 platform/environment skips across input, capture, recording, launcher, startup, and lifecycle selection |
| Ordinary suite | BASELINE | 2,433 tests, 100 inherited failures, 14 skips; all eight added SC-1668 tests pass and the failure count matches SC-1667 |
| Replay suite | BASELINE | 280 tests, 13 established failures, unchanged from SC-1667 |
| Fedora primary live matrix | NOT RUN | no live result recorded for this candidate revision |
| Fedora secondary live matrix | NOT RUN | no second-host result recorded for this candidate revision |
| Windows live matrix | NOT RUN | must run from Windows |
| Strict three-host evidence verification | BLOCKED | correctly refuses until all three records are complete |

Quality gates: Checkstyle exits zero with inherited warnings and no warning on an SC-1668 file.
Spotless retains its inherited offender set; no SC-1668 Java file is named. `git diff --check` is
clean.

Fixture, compile, and single-host checks cannot satisfy the three real-host acceptance criteria. The
story may be implementation-complete and reviewable with explicit NOT RUN results, but the strict
qualification gate remains incomplete until the three host records pass at one revision.
