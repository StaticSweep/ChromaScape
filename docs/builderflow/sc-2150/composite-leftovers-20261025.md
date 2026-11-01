# Composite working-tree leftovers — 2026-10-25

The ChromaScape checkout at `codex/sc-2150-mouse-video-import` contained several
workstreams mixed together. SC-2150 was committed from the isolated worktree
`ChromaScape-sc2150-commit`; the original checkout was left untouched.

## Backups

- Tracked changes: `/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/ChromaScape-backups/20261025-composite/composite-tracked.patch`
- Untracked files: `/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/ChromaScape-backups/20261025-composite/composite-untracked.tar.gz`
- Untracked manifest: `/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/ChromaScape-backups/20261025-composite/untracked-files.txt`

## Committed as SC-2150

Commit `7b4530df` on `codex/sc2150-v7-tracker`:

- `src/main/java/com/chromascape/web/debug/tickdebugger/video/service/MouseVideoCursorTracker.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/video/service/MouseVideoJsonStream.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/video/service/MouseVideoObservationsService.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/video/service/MouseVideoVisualAnalyzer.java`
- `src/test/java/com/chromascape/web/debug/tickdebugger/video/service/MouseVideoCursorTrackerTest.java`
- `src/test/java/com/chromascape/web/debug/tickdebugger/video/service/MouseVideoFrameIndexServiceTest.java`
- `src/main/java/com/chromascape/web/ServePages.java` (`/mouse/video` route only)
- `docs/mouse-video-import.md`
- `docs/builderflow/sc-2150/audit-20261025.md`

Verification in the isolated worktree: `compileJava compileTestJava`, the two
focused video tests, `spotlessCheck` and `checkstyleMain` passed.

## Preserved elsewhere

- **SC-2106 / SC-967 contract cluster (51 paths)** — byte-identical to
  `codex/sc-967-sc2106-producer`, which is pushed. Includes the
  `aiplayer/contract/Current*` cluster, `CandidateExecutionClosureV1`, the
  candidate-execution fixtures and the `CurrentSlice*` changes.
- **SC-663 (21 paths)** — the contract cluster and routine/workbench work are
  on `codex/sc-663-review-20261016`. `ImitationDatasetBuilderService` and its
  test match that branch exactly. `RoutineController` matches it plus the
  unique `/workbench/contexts` endpoint, which still needs an owner.
- **SC-1991 (15 paths + 12 viewport paths)** — `XrspsSession*`,
  `LocalReplayPrincipal*`, `TickLogRepository`, `TickStreamIngestionService`,
  `TickStreamWebSocketHandler`, `SessionStartResponse`, and the viewport/screen
  sync files. These are unique to the composite tree and need their own branch.
- **Mouse research dashboard (54 paths)** — controllers, services, workers,
  templates, JS/Python tests and browser tests. Likely SC-304/SC-2150
  follow-up; owner needs confirmation.
- **Builder Goal / patch loop (6 paths)** — `BuilderGoal*`,
  `ScriptExecutionService`, `LauncherContractAdjudicationTest`; owner needs
  confirmation.
- **Unassigned (30 paths)** — listed in the CSV below with
  `preserved_at=needs owner`.

## Machine-readable list

`composite-leftovers-20261025.csv` contains one row per tracked or
code-relevant untracked path:

| column | meaning |
| --- | --- |
| `path` | working-tree path |
| `status` | `tracked-modified` or `untracked` |
| `proposed_owner` | best current classification |
| `preserved_at` | branch that holds the content, or `needs branch` / `needs owner` |

Counts: 213 rows — 51 SC-2106/SC-663, 21 SC-663, 17 SC-663/SC-967, 15 SC-1991,
12 viewport, 54 mouse research, 7 SC-2150, 6 Builder Goal, 30 unassigned.

## Rule

Do not delete or clean the composite checkout. Commit each row from the CSV to
the owning story branch, then remove it from the composite only after readback.
