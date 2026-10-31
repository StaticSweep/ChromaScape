# SC-2150 — W5 frozen candidate and rerun, 2026-10-31

Candidate: `f39cb8b1` on `codex/sc-2150-mouse-video-import` (PR #70 head). Plan: `remaining-plan-20261031.md` W5.

## Freeze

`build/sc2150-r29-candidate.json` (`sha256:d2f5e362…`, not committed) lists all **420** files touched by
`SC-2150:` commits with their blob hashes at `f39cb8b1`.
`scripts/sc2150/verify_candidate_extraction.py … --rev f39cb8b1` and `--rev origin/codex/sc-2150-mouse-video-import`:
**420/420** match, so the pushed branch is exactly the frozen candidate.

## Tests (clean detached worktree of `f39cb8b1`, not the shared working tree)

| Check | Result |
|---|---|
| `./gradlew test --tests 'com.chromascape.web.debug.tickdebugger.video.*'` | 314 tests, 0 failures, 0 errors, 1 skip (opt-in corpus harness, run below) |
| `SC2150_FA12_CORPUS=build/sc2150-fa12 … MouseVideoCursorCorpusHarnessTest` | 1/1 passed (explicit corpus run, not a skip) |
| `spotlessCheck`, `checkstyleMain`, `checkstyleTest` | passed |
| `npm test` | 318/318 |
| `python3 -m unittest discover -s src/test/python` | 75/75 |
| Full repository suite | see `full-suite-dispositions-20261031.md`: 0 failures attributable to SC-2150 |

The shared working tree shows more JS/Python tests (372/111) only because it holds other features'
untracked tests (mouse campaign/research/recording, SC-663, ai-workbench); every SC-2150 test is present and
byte-identical in the clean candidate.

## Live evidence on this candidate's code

- W2 containment: `verification-evidence-w2-containment-20261031.md` (60/60 contained; cancel, SIGKILL,
  SIGTERM; graceful shutdown re-queues).
- W3 browser matrix: `verification-evidence-w3-browser-matrix-20261031.md` (all Step 4 cases pass; 0 unconfirmed
  cleanup after `273c6088`).

## Not covered here

Accuracy: the detector still fails the dev gates (recall 96.57 %, absent false 12.95 % at R28). No owner or
held-out truth exists. W6–W12 remain open (see the plan).

## Rollback

Evidence-only. Remove the worktree `worktrees/sc2150-w5`; the candidate is the pushed branch head.
