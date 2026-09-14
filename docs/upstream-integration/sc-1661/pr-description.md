# SC-1661 — Pull Request Description (draft, not yet published)

Branch: `codex/sc-1661-chromascape-upstream-reconciliation`
Intended target: `integration/chromascape-consolidated-20260901` (`origin` = `ikiddoi/ChromaScape`)
Target revision inspected: `471262bfeefa0c1d0a91a367227a3fd78586814e`

This file is the reviewable PR body. **The branch has not been pushed and no PR has been opened** —
publishing is a shared-state action that awaits confirmation.

## Read this first: the PR is larger than SC-1661

The intended target branch is **behind the baseline this story pins**. Measured against
`origin/integration/chromascape-consolidated-20260901`:

```bash
git log --oneline origin/integration/chromascape-consolidated-20260901..HEAD | wc -l   # 48
git diff --stat origin/integration/chromascape-consolidated-20260901...HEAD | tail -1   # 509 files
```

Only **7** of those 48 commits are SC-1661's. The other **41** are the inherited consolidation
lineage from `583a05b` (`wip/chromascape-consolidation-20260907`), which the target does not yet
contain. The PR therefore carries the consolidation plus this story, and the reviewer should decide
whether that is the intended shape or whether the target branch must be advanced to
`583a05b`/`966e0e3` first.

SC-1661's own commits:

| Commit | Kind |
|---|---|
| `65af3c9`, `4e0a3d5` | documentation |
| `45f943f` | carry — replay fixture `eol` attributes (source `d5b2d82`) |
| `966e0e3` | adapt — Fedora remote Builder Goal launcher routing (sources `97284f1`, `ced7487`) |
| `283328b` | documentation |
| `1978937` | evidence — durable, indexed baseline-validation evidence |
| `8b6e333` | documentation — divergence, validation and deferral corrections |

## Why this baseline was selected

The Linux port needs a single, pinned commit that contains the fork's consolidation work plus the
two custom changes that would otherwise be lost. `583a05b` was the consolidation content baseline;
SC-1661 carries the outstanding custom work on top of it and pins the result as **`966e0e3`**.
Downstream stories branch from `966e0e3`, not from `583a05b`.

Revisions:

| Role | SHA |
|---|---|
| Original consolidation baseline | `583a05b91687d156b1baa52dbda2b219604c7b39` |
| Candidate baseline (branch before the carries) | `4e0a3d5b56ae7d3d50bd4ce878b31dd541540a23` |
| **Reconciled code baseline** | **`966e0e3e47db71fe5d57629205efcb23cd11e026`** |
| Documentation / review tip | `8b6e333` (advances independently; the code baseline does not move) |
| Pinned upstream | `5ea3b995f5d13e19c592a6399973fe7dadb5e656` (`StaticSweep/main`, tag `v0.5.0`) |
| Upstream merge base | `46db1c462752d39bd0c04da630bfe5d35edc117a` |
| Intended merge target (inspected) | `471262bfeefa0c1d0a91a367227a3fd78586814e` |

## Which inherited consolidation changes are included

All 41 commits between `471262b` and `583a05b`. They are the consolidation lineage, not SC-1661
work, and they were reviewed as the *source* of reconciliation, not authored here. Their
relationship to the custom branches is recorded in `custom-change-reconciliation.md` and
`source-inventory.md`; the key point is that `583a05b` already contains the main checkout
(`7ab2a80`), the published consolidated branch (`471262b`) and the SC-1199 durable tick-batch lineage
(`16a3d72`, merge base `e5cdf81`) with **0 commits behind** each.

## What SC-1661 specifically carried or adapted

| Change | Source | Result |
|---|---|---|
| Replay fixture `eol=lf` attributes (two path-scoped lines) | `d5b2d82` | `45f943f` — verified with `git check-attr`: exactly the five intended paths; all five blobs already LF, so no blob change and no repository-wide EOL rewrite |
| Fedora remote Builder Goal launcher routing (14 files) | `97284f1`, `ced7487` | `966e0e3` — adapted, not cherry-picked: the published `97284f1` **does not compile** (`state.launchAttemptId()` where `ClientState` is a plain class with a field), the patch parent predates the baseline's rework of the same four services, and `.env.example` had to be re-applied by hand |

**Nothing else is carried.** The 13 residual main-checkout files were adjudicated and all are
Superseded; the 16 untracked `src/` files resolve to 5 byte-identical, 2 whitespace-only and 9
substantive (one, `V33`, deferred — see below). Details in `custom-change-reconciliation.md`.

## Source revisions and reconciliation ledger

- `source-inventory.md` — every pinned revision with its full SHA and worktree.
- `upstream-commit-ledger.md` — all 15 upstream-only commits, each with a disposition, an owning
  story drawn from the corrected scopes, and required verification.
- `compatibility-map.md` — seven required contract decisions and seven boundaries, each with its
  owning story and regression tests.
- Divergence, measured from `966e0e3`: **296 ahead / 15 behind** vs upstream; 81/1 vs Fedora;
  33/3 vs durable-current; 0 behind main/main2.

## Actual validation results and limitations

Full per-run record with revisions, commands, exit codes and SHA-256: `evidence/index.md`.

| Check | Candidate `4e0a3d5` | Reconciled `966e0e3` |
|---|---|---|
| `tasks` / `compileJava` / `compileTestJava` | exit 0 | exit 0 |
| `checkstyleMain` | exit 0 — 3280 warnings / 561 files | exit 0 — 3291 / 563 (carried files add 11 `MissingJavadoc*`) |
| `spotlessCheck` | exit 1 — 16 files | exit 1 — the **same 16** (complete sets byte-identical) |
| `test` | exit 1 — 2250 / 110 failed / 13 skipped | exit 1 — 2255 / 110 / 13 |
| `replayTest` | exit 1 — 280 / 12 failed | exit 1 — 280 / 12 |
| focused launcher suites (6 classes) | — | exit 0 — 175 / 0 |
| review run (`*Micro6RemoteLauncherServiceTest`) | — | exit 0 — 3 / 0 |

**What is claimed.** No **additional** failures were observed in the executed checks: the failing
identity sets are byte-identical before and after (110 `test` identities, 12 `replayTest`
identities). The reconciled branch adds 5 passing tests (3 + 1 + 1 across three classes).

**What is not claimed.** Docker and PostgreSQL are unavailable on the validation host, so 92 of the
110 `test` failures and all 12 `replayTest` failures are environmental, and the database-, migration-
and container-backed behaviour those suites cover **was never exercised on either revision**. Tests
that did not execute cannot show that no regression exists. Compilation plus unit-test success is
**not** end-to-end Fedora qualification; that is SC-1667's to prove.

## Deferred defects and their references

Recorded in `deferred-findings.md`. Neither is a tracker ticket yet — no Felix/Shortcut transport was
available — so each is a durable in-repo record with the intended tracker operation stated:

- **SC-1661-D1** — `ck_tick_snapshots_replay_state_json_sizes` in `V33` is logically weaker than
  intended (`dialogue IS NULL OR (…AND…)` leaves `spells`/`vars` unbounded). V33 must not be
  rewritten; the remedy is a forward migration. Owning story not yet established.
- **SC-1661-D2** — 18 pre-existing non-environmental test failures (16 gameplay/provider + 2 other),
  with all 18 identities retained. Root cause **not confirmed**: a baseline defect and environment
  sensitivity are not yet separated.

Neither blocks this baseline handoff, and fixing either is **outside** SC-1661.

## Rollback

- Discard both carries: reset the branch to `583a05b`.
- Revert individually: `git revert 45f943f` (attributes — zero source impact) and
  `git revert 966e0e3` (launcher routing — returns `isBuilderGoalRunnableMachine` to local-only).
- Documentation and evidence commits (`283328b`, `1978937`, `8b6e333`) revert independently and
  touch no source.
- No existing worktree, branch or uncommitted change was modified, moved or pruned.

## Code baseline vs documentation tip

The pinned **code** baseline is `966e0e3` and is unchanged by the follow-up commits. The
documentation/review tip (`8b6e333`) advances independently, so the authoritative artifacts do not
need to embed their own commit hash. Downstream stories reference the **code** SHA.

## Out of scope

RemoteInput implementation, Linux process discovery, and rendered-buffer capture (SC-1662–SC-1668).
This PR establishes and validates the baseline those stories build on.
