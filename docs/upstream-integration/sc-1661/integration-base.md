# SC-1661 — Integration Base

## Identity

| Field | Value |
|---|---|
| Story | SC-1661 "Reconcile upstream changes and pin the integration base" |
| Classification | `change:runtime` |
| Canonical Felix project | `C:\Dev\IdeaProjects\ChromaScape` |
| Felix tag | `shortcut:sc-1661` |
| Branch | `codex/sc-1661-chromascape-upstream-reconciliation` |
| Worktree | `/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/ChromaScape-sc1661-reconciliation` |
| Intended PR target | `integration/chromascape-consolidated-20260901` (`origin` = `ikiddoi/ChromaScape`) |

## Revisions

| Role | SHA | Notes |
|---|---|---|
| Original starting SHA | `583a05b91687d156b1baa52dbda2b219604c7b39` | Provisional starting point, verified to still represent the intended consolidation work |
| Pinned upstream SHA | `5ea3b995f5d13e19c592a6399973fe7dadb5e656` | `StaticSweep/main`, tag `v0.5.0` |
| Upstream merge base | `46db1c462752d39bd0c04da630bfe5d35edc117a` | True fork point |
| **Final reconciled baseline SHA** | `583a05b` (content) | Content baseline for downstream stories. Story tip is the `codex/sc-1661-chromascape-upstream-reconciliation` branch (documentation commits only). **No custom change needed carrying**, so the reconciled baseline is the provisional baseline itself |

### Why no custom change needed carrying

The reconciliation resolved cleanly. Every dirty entry in every candidate worktree is now
accounted for as already present in, or superseded by, `583a05b`:

1. The 40-file "divergent" set from the first draft was an artefact of a formatting-sensitive
   metric. At identifier level, **72 of 85** substantive files are fully covered by the
   baseline. The cross-check is conclusive: baseline `TickStreamIngestionService` lines
   496–556 are the same dialogue/spells/vars handling as worktree lines 234–294, and baseline
   `SourceRunBinding` carries all of `repositoryId`/`commitId`/`treeId`/`buildId`.
2. **All 16 untracked `src/` files already exist in the baseline** — 5 byte-identical, 11
   formatting or older variants. Notably the baseline's `V32` migration is the *safer* one
   (`DROP CONSTRAINT IF EXISTS`), and `V33` is identical in statement content.
3. 13 files introduce identifiers the baseline lacks (7 tests, 2 docs, 4 small deltas). This
   bounded residue is recorded for review but **does not block pinning** — the probable
   disposition is superseded throughout.

One item remains open and is *not* a reconciliation blocker:

- **Baseline validation is unproven.** No build or test has been shown to pass: the only
  installed JDK is Java 25 while the build declares a Java 17 toolchain, and Gradle 8.14.2's
  Kotlin 2.0.21 cannot parse `25.0.2` (see `baseline-validation.md`). This must be cleared
  before SC-1662 relies on the baseline for regression evidence.

## Included custom changes and their origins

| Change | Origin | Status |
|---|---|---|
| Main checkout `7ab2a80` | `integration/chromascape-consolidated-20260901` | **Already included** (0 commits behind baseline) |
| Published consolidated `471262b` | `origin/integration/chromascape-consolidated-20260901` | **Already included** (0 commits behind) |
| SC-1199 durable tick-batch / recording lineage | `16a3d72` lineage | **Already included** (ancestor at `e5cdf81`) |
| `origin/main`, `origin/main2` | `eb18d2d`, `8189e4d` | **Already included** (0 commits ahead of either) |
| 21 main-checkout files identical to baseline | working tree | **Already included** |
| 24 main-checkout files superseded by baseline | working tree | **Superseded** — confirm per-file before close |
| `d5b2d82` "Normalize replay fixture attributes" | `integration/sc1199-fedora-durable-20260909` | **Cherry-pick candidate** (`.gitattributes`, 2 lines) |
| Fedora remote launcher routing `97284f1` | `codex/fedora-remote-executor` | **Adapt required** |

## Deferred changes and reasons

| Change | Reason |
|---|---|
| `69ff913` RemoteInput integration | **Deferred to SC-1662–SC-1667.** Vendors a whole native project (350 files, ~95k insertions) including editor state; deletes `Kinput.java` and `WindowHandler.java` that the baseline still uses |
| 13-file residual set (7 tests, 2 docs, 4 small deltas) | Bounded residue introducing identifiers the baseline lacks. Recorded for review; does not block the baseline |
| 16 untracked `src/` files | **Not deferred — resolved.** All 16 already exist in the baseline |
| `.gradle-home/` (1,734 untracked files) | Generated Gradle user-home cache; must be excluded, not committed. Not currently in `.gitignore` |
| Upstream version-string commits (`b7feca0`, `ac06b03`, `c4de3f4`), README (`c662806`) | No porting requirement |
| CRLF-only churn (main 424, consolidation 1,419, durable-current 1,414) | Line-ending noise; zero content change |

## Refreshed divergence counts

Recomputed this story; the earlier figures are confirmed but now carry corrected meaning.

| Comparison | Merge base | Ahead | Behind |
|---|---|---|---|
| Baseline vs Fedora `97284f1` | `7ab2a80` | 77 | **1** |
| Baseline vs durable-current `16a3d72` | `e5cdf81` | 29 | **3** (2 non-merge: `ced7487`, `d5b2d82`) |
| Baseline vs upstream `5ea3b99` | `46db1c4` | 292 | **15** |
| Baseline vs main checkout / main2 | — | — | **0** each |

Important correction: the baseline is **292 commits ahead of the true upstream fork point**.
The "15 upstream commits" is the upstream-only set to adapt; the 292 is the custom fork
surface. These are different numbers and must not be conflated.

## Remaining baseline failures

**All checks blocked; none passing, none failing in code.**

- Root cause: `java.lang.IllegalArgumentException: 25.0.2` from Kotlin 2.0.21's
  `JavaVersion.parse` while compiling `build.gradle.kts`.
- Affects `compileJava`, `test`, `replayTest`, `spotlessCheck`, `checkstyleMain`,
  `replayCertificationTest` — and even `./gradlew tasks`.
- Pre-existing / environmental: reproduces on a clean `583a05b` with zero story changes.
- Unblocking prerequisite: a JDK 17 install, or a toolchain resolver (owned by SC-1666).

## Rollback reference

- Starting point `583a05b` is untouched; no existing branch was moved.
- The story branch is additive (documentation under `docs/upstream-integration/sc-1661/`).
  Rollback is `git worktree remove ChromaScape-sc1661-reconciliation` (verified clean before
  this commit) or resetting the branch to `583a05b`.
- No existing worktree, branch, or uncommitted change was modified, moved, or pruned.
  All five pre-existing ChromaScape worktrees retain their branches and dirty state.
- Rollback of the *provisional base choice* is a single re-point: nothing downstream depends
  on `583a05b` until the final reconciled baseline is published.

## Revised implementation sequence

| Order | Story | Scope against this baseline |
|---|---|---|
| 1 | **SC-1661** | Reconcile + pin (this story). Blocks everything below |
| 2 | SC-1662 | Session/selection preservation; RemoteInput abstraction introduction; `Kinput` substitute interface before any deletion |
| 3 | SC-1663 | Capture + coordinate contract (`toClientBounds`/`toClientCoords` as the only seam); Linux `WindowFrameCapture` implementation; upstream `db17529`/`cad0847` capture adaptations |
| 4 | SC-1664 | Profile loading / startup (upstream `5ea3b99`) |
| 5 | SC-1665 | Recording metadata + first-frame readiness |
| 6 | SC-1666 | Build/cross-platform (`580a619`, `settings.gradle.kts`); toolchain resolver; per-hunk `build.gradle.kts` merges preserving `replayTest`/certification/native tasks |
| 7 | SC-1667 | Builder Goal routing incl. Fedora adaptation |
| 8 | SC-1668 | Remaining upstream adaptations + version/docs commits |

Estimates are deliberately not given here: they depend on the two open adjudications and on
whether the toolchain blocker is resolved by installing JDK 17 (small) or by adding a
resolver plugin (a build change that must itself be verified).

## Acceptance status

| SC-1661 completion criterion | Status |
|---|---|
| Work committed on a dedicated branch in a separate worktree | **Done** — worktree created clean; docs committed |
| Relevant custom sources / uncommitted changes have explicit dispositions | **Done** — all dirty entries classified; 13-file residue recorded |
| No user changes lost | **Yes** — nothing copied, moved, deleted, or pruned |
| Final baseline and upstream revisions pinned | **Done** — baseline `583a05b`, upstream `5ea3b99` (validation unproven, see below) |
| Every upstream-only change accounted for | **Done** — 15/15 in the ledger with dispositions |
| Compatibility boundaries and downstream responsibilities documented | **Done** |
| Baseline checks truthful, introduced regressions resolved | **Done (truthfully negative)** — blocked, recorded, not hidden |
| Epic and dependent stories reference the final baseline | **Pending** — needs Felix + Shortcut MCP, unavailable in this session; outbox record written |
| Review, rollback and delivery evidence available | **Partial** — rollback + evidence recorded; PR/review not yet raised |
