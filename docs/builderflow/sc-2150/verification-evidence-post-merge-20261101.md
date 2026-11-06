# SC-2150 — post-merge verification of `main`, 2026-11-01

Scope: PR #86 (SC-2150 code on main, `aaccc268`) and PR #87 (held-out expansion, docs only,
`7aa3b2ec`). The repository's GitHub Actions budget is exhausted, so no CI ran on either PR or on
`main`. This is the local replacement check.

## Environment

- Clean detached worktree of `origin/main` at `7aa3b2ec` (the shared checkout was not touched).
- Launcher `JAVA_HOME=~/.gradle/jdks/jdk-21.0.12.1+1`, project toolchain 17.
- Gradle offline (`--offline -g <repo>/.gradle-home`); SC-967 fixture supplied with
  `-Psc967VectorsPath=…/worktrees/contracts-sc967-closure/sc967/ed25519-vectors.json`.
- No PostgreSQL instance in the worktree, so database-backed tests are skipped (see below).

## Results

| Check | Result |
|---|---|
| `compileJava compileTestJava` | success (0 errors) |
| `test --tests 'com.chromascape.web.debug.tickdebugger.video.*'` | 334 tests, 1 failure, 48 skipped |
| that failing test rerun alone (`--rerun`) | passed |
| `node --test src/test/js/*.test.js` | 305 tests: 300 pass, 2 fail, 3 skipped |
| `python3 -m unittest discover -s src/test/python` | 81/81 |

## Dispositions

- `MouseVideoFrameIndexServiceTest.concurrentLargeObservationReadsFitInAFixedHeap`: the child
  `-Xmx192m` process exceeded its 90 s wait while the package ran in parallel; it passed on a
  clean rerun of the class. Load/timeout flake, not a merge regression.
- `src/test/js/ai-player-catalog.test.js` and `src/test/js/recording-catalog.test.js`: fail
  identically on a clean `origin/main` before these merges, and on the pre-merge SC-2150 branch.
  Pre-existing, unrelated to SC-2150 (see `full-suite-dispositions-20261031.md`).
- 48 video-package skips are the opt-in corpus harness and PostgreSQL-backed tests that need a
  database; they were exercised in `verification-evidence-w5-candidate-20261031.md` and
  `verification-evidence-w2-containment-20261031.md`, not in this pass.

## Conclusion

`main` after PR #86 and PR #87 compiles and carries the SC-2150 code that was verified on
`codex/sc-2150-on-main`; no failure in this pass is attributable to SC-2150. CI remains unrun
because of the Actions budget, not because of a build defect.
