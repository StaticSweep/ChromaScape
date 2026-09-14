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
| **Candidate baseline (content)** | `583a05b` | The consolidation content every downstream story builds on |
| **Reconciled code baseline SHA** | **`966e0e3`** | The branch tip after the two carries. Downstream stories branch from here; its content baseline is `583a05b` plus the additive carries below |
| Documentation / review tip | *recorded separately* | Deliberately not embedded, so the artifact does not need to contain its own commit hash |
| Pinned upstream SHA | `5ea3b995f5d13e19c592a6399973fe7dadb5e656` | `StaticSweep/main`, tag `v0.5.0` |
| Upstream merge base | `46db1c462752d39bd0c04da630bfe5d35edc117a` | True fork point |

An earlier revision of this document declared `583a05b` the *final* baseline, claimed that
"no custom change needed carrying", and recorded the reconciliation as complete. **That claim is
withdrawn.** It rested on an identifier-presence test, which locates differences but does not
establish behavioural equivalence. Reconciliation has since been completed properly, and the tree
now contains the carried changes.

## Reconciliation status: complete

| Item | Status |
|---|---|
| 13 residual files adjudicated | **Done** — all 13 Superseded, each with the replacement and evidence named (`custom-change-reconciliation.md`) |
| Gitattributes carry (`d5b2d82`) | **Done** — `45f943f` |
| Fedora remote routing (`97284f1` / `ced7487`) | **Done** — adapted as `966e0e3` |
| Untracked-file inventory | **Done** — 5 identical / 2 whitespace-only / 9 substantive, each dispositioned |
| Secrets scan | **Done** — no credentials found |
| Baseline validation | **Done with actual results** — see `baseline-validation.md` |
| Tracker handoff | **Pending** — Felix/Shortcut MCP transport is unavailable in this session; the outbox record carries the handoff |

Every relevant custom change now has one of the four allowed dispositions. Nothing is left as
"probable", "candidate", or "needs review".

## Custom changes carried into this branch

| Commit | Source | Change |
|---|---|---|
| `45f943f` | `d5b2d82` | Replay fixture `eol=lf` attributes (two path-scoped lines) |
| `966e0e3` | `97284f1`, `ced7487` | Fedora remote Builder Goal launcher routing (14 files, adapted) |

## Custom changes already included (no action)

| Change | Origin | Evidence |
|---|---|---|
| Main checkout `7ab2a80` | `integration/chromascape-consolidated-20260901` | 0 commits behind the candidate baseline |
| Published consolidated `471262b` | `origin/integration/chromascape-consolidated-20260901` | 0 commits behind |
| SC-1199 durable tick-batch / recording lineage | `16a3d72` lineage | Merge base `e5cdf81`; lineage is an ancestor |
| `origin/main`, `origin/main2` | `eb18d2d`, `8189e4d` | 0 target-only commits |
| 72 of 85 substantive main-checkout files | working tree | No identifier absent from the baseline; representative cross-checks recorded |
| 5 untracked `src/` files byte-identical, 2 whitespace-only | working tree | Direct comparison |
| 56 consolidation / durable-current differences | both worktrees | `git diff --ignore-cr-at-eol` empty — EOL-only |

## Deferred changes and reasons

| Change | Reason |
|---|---|
| `69ff913` RemoteInput integration | **Deferred to SC-1662–SC-1667** (split by the corrected ownership table below). Vendors a whole native project (350 files, ~95k insertions) including editor state; deletes `Kinput.java` and `WindowHandler.java` that the baseline still uses |
| **`V33` CHECK-constraint strengthening** | **Explicit deferral.** The candidate baseline's `ck_tick_snapshots_replay_state_json_sizes` is logically weaker than the dirty draft (`dialogue IS NULL OR (…AND…)` short-circuits the `spells`/`vars` bounds). V33 is already committed and Flyway-checksummed, so the remedy is a new forward migration, not an in-place edit. Not a prerequisite for the Linux/RemoteInput work. **Tracked as `SC-1661-D1` in `deferred-findings.md`** with the two predicates, seven database validation cases, the forward-migration requirement and the delivery impact. **Owning story not yet established** — no SC-1662–SC-1668 story owns migrations; nearest existing owner is SC-1668, and a dedicated schema-hardening ticket is required. **No tracker ticket exists yet** (no Felix/Shortcut MCP in the preparing session), so "handed to the integration stream" is not claimed |
| Unrelated upstream demo-script commits (`88b7b5f`, `6038886`) | Demo scripts and their failsafe logic have no Linux-port requirement; assigning them to a port story would give that story a different purpose. Explicit deferral |
| Upstream version-string commits (`b7feca0`, `ac06b03`, `c4de3f4`), README (`c662806`) | No porting requirement |
| `.gradle-home/` (1,734 untracked files), `.commandcode/**`, consolidation `.micro6/` | Generated caches, tool configuration and runtime state — excluded, never committed |
| CRLF-only churn (main 424, consolidation 1,419, durable-current 1,414) | Line-ending noise; zero content change |

## Divergence counts (recomputed against the pinned upstream)

Every row is measured from the **reconciled code baseline `966e0e3`** and is reproducible with the
command given beside it.

| Comparison | Command | Merge base | Ahead | Behind |
|---|---|---|---|---|
| Reconciled branch `966e0e3` vs Fedora `97284f1` | `git rev-list --left-right --count 966e0e3...97284f1` | `7ab2a80` | **81** | **1** (now carried) |
| Reconciled branch `966e0e3` vs durable-current `16a3d72` | `git rev-list --left-right --count 966e0e3...16a3d72` | `e5cdf81` | **33** | **3** (2 non-merge: `ced7487`, `d5b2d82` — both now carried) |
| Reconciled branch `966e0e3` vs upstream `5ea3b99` | `git rev-list --left-right --count 966e0e3...5ea3b99` | `46db1c4` | **296** | **15** |
| vs main checkout / main2 | `git rev-list --left-right --count 966e0e3...7ab2a80` | — | — | **0** each |

**Correction.** An earlier revision recorded `79 / 31 / 294`. Those figures were derived from
`583a05b` and then adjusted by hand, and the adjustment omitted two documentation commits
(`65af3c9`, `4e0a3d5`). Measured against `966e0e3` the counts are **81 / 33 / 296**. The older set is
not wrong about `583a05b` — `583a05b...5ea3b99` really is `292 / 15` — but it answers a different
question, so a count is only meaningful next to the revision it was measured from.

**How to read these numbers.**

- Git commit counts include documentation commits. `966e0e3` is four commits ahead of `583a05b`
  (`65af3c9` and `4e0a3d5` are documentation; `45f943f` and `966e0e3` are the carries), so every
  "ahead" figure measured from `966e0e3` is exactly 4 higher than the same figure from `583a05b`.
- **An adapted change can be present in behaviour while its original source commit remains
  ancestry-unique.** `ced7487` and `d5b2d82` still count as "behind" the reconciled branch because
  the carry created a new commit; it did not merge them.
- **Commit counts do not measure the size of the functional difference.** "296 vs 15" says nothing
  about how many files or behaviours differ — the per-commit ledger in `upstream-commit-ledger.md`
  does. 296 is the custom fork surface; 15 is the upstream-only set to adapt.

## Baseline validation (summary — details and evidence in `baseline-validation.md`)

- **The launcher-JDK blocker is cleared.** A compatible JDK 17 (Corretto 17.0.20.1) exists at
  `~/.jdks/corretto-17.0.20.1` and launches Gradle. **No wrapper upgrade and no toolchain resolver
  was added** — the build files are untouched.
- `./gradlew tasks` and `./gradlew compileJava` now **pass** at the candidate baseline (exit 0).
  `checkstyleMain` passes with warnings only.
- `spotlessCheck` **fails** at the candidate baseline on **16 pre-existing files** (4 listed +
  12 others). The reconciled branch has the **same 16**; the adaptation introduced no new
  violations.
- `./gradlew test` at the candidate baseline: **2250 tests, 110 failed, 13 skipped**. The
  reconciled branch reproduces the same failure classes — see below.
- `./gradlew replayTest`: **280 tests, 12 failed**.
- The `test` task additionally needs the SC-967 fixture path on Linux
  (`-Psc967VectorsPath=…`); without it the task fails before running any test. This is a
  documented override the build provides (`build.gradle.kts:7-12`), not a build change.

**Failure classification (candidate baseline).** Docker is not available on this host and there is
no local PostgreSQL, so the Testcontainers-backed suites cannot start:

| Signature | Count | Classification |
|---|---|---|
| Docker environment unavailable | 34 | Environmental (not introduced) |
| PostgreSQL connection refused (`localhost:5433`) | 10 | Environmental |
| ApplicationContext failure-threshold cascade from the above | 48 | Environmental (consequence) |
| Gameplay contract / provider-output assertions | 18 | **Pre-existing code failures** — see `baseline-validation.md` |

No failure is attributable to SC-1661; every one reproduces at the pristine candidate baseline.

## Remaining risks

1. **V33 constraint weakness** (deferred above). Data-integrity hardening; owner recorded.
2. **No Docker/PostgreSQL on this host**, so migration, concurrency and DB-integration evidence
   cannot be produced here. The failure set is identical before and after reconciliation, so it
   does not mask an introduced regression, but it also cannot *prove* the adapted launcher code
   under a real database. The focused launcher suites (175 tests, 0 failures) run without a
   database and do cover the adapted routing logic.
3. **18 pre-existing non-environmental test failures** — 16 in the gameplay/provider family, 2
   elsewhere (`SourceContractSchemaEqualityTest`, `Micro6TargetRelaunchServiceTest`). Unrelated to
   SC-1661. Tracked as **`SC-1661-D2` in `deferred-findings.md`** with the exact identities of all
   18, both reproduction logs, the environment, and an **unconfirmed** root cause (baseline defect
   versus environment sensitivity is not yet separated). **Owning story not yet established**; a
   dedicated defect ticket is required and does **not** exist yet.
4. **The Fedora adaptation is validated by compilation and unit tests only.** End-to-end remote
   launch against a real Fedora agent is owned by SC-1667.
5. **Felix/Shortcut handoff is not yet synced** — no MCP transport in this session.

## Rollback reference

- Reference point: reset the branch to `583a05b` to discard both carries. The consolidation
  worktree and `wip/chromascape-consolidation-20260907` are untouched.
- The two carries are independent and individually revertable:
  `git revert 45f943f` (attributes — zero source impact) and `git revert 966e0e3` (launcher
  routing; returns `isBuilderGoalRunnableMachine` to local-only).
- No existing worktree, branch, or uncommitted change was modified, moved, or pruned. All
  pre-existing ChromaScape worktrees retain their branches and dirty state.
- Worktree teardown: `git worktree remove ChromaScape-sc1661-reconciliation` (after committing or
  reverting the carries).

## Downstream ownership (corrected)

The earlier revision of this document mapped the dependent stories incorrectly. The authoritative
scopes are:

| Story | Actual scope |
|---|---|
| SC-1662 | Native RemoteInput build and library loading |
| SC-1663 | Process discovery and explicit target selection |
| SC-1664 | Input attachment and mouse/keyboard lifecycle |
| SC-1665 | Rendered-buffer capture and screen API compatibility |
| SC-1666 | Recording, debugger, and remote-launch integration |
| SC-1667 | Fedora startup and attachment diagnostics |
| SC-1668 | Host qualification and integration PR |

Sequence against this baseline:

| Order | Story | What it consumes from `966e0e3` |
|---|---|---|
| 1 | **SC-1661** | This story — baseline pinned; blocks everything below |
| 2 | SC-1662 | The build/carry surface: `build.gradle.kts` Java 17 toolchain, the `replayTest`/certification task set, and the native-library copy task that the RemoteInput build must join |
| 3 | SC-1663 | `WindowSelectionService` / `WindowController` / `WindowHandler` selection model and the process-based target model in `compatibility-map.md` |
| 4 | SC-1664 | The `Kinput`/`VirtualMouseUtils`/`VirtualKeyboardUtils` seam and single-attachment ownership |
| 5 | SC-1665 | `ScreenManager` capture/bounds plus the `toClientBounds`/`toClientCoords` conversion seam |
| 6 | SC-1666 | `WindowFrameCapture`, `FrameEvent`/`FrameListener`, and `RecordingService` readiness policy |
| 7 | SC-1667 | The carried `Micro6RemoteLauncherService`, `BuilderGoalLauncherControlService` routing, and `builder-goal.remote.*` configuration |
| 8 | SC-1668 | Host qualification of the whole set, and the integration PR |

## Revised estimates

Estimates are now expressed as ranges with their basis, rather than a single unsupported total.

| Story | Estimate | Basis |
|---|---|---|
| SC-1662 | **Medium–large** | The RemoteInput commit is 350 files / ~95k insertions including a vendored native tree and editor state; the build and library-loading work must be separated from the Java seam |
| SC-1663 | **Medium** | **Process discovery and explicit target selection.** It owns the `WindowHandler` deletion, the platform-neutral target identity, and the selection-semantics tests from `69ff913`. It does **not** own capture — the three `ScreenManager` rewrites are SC-1665's |
| SC-1664 | **Medium** | Bounded to the input seam, but it must land after SC-1662's abstraction exists |
| SC-1665 | **Medium–large** | Highest-risk capture boundary: three separate upstream rewrites of `ScreenManager` (`db17529`, `69ff913`, `cad0847`) must be reconciled per-hunk, plus the `toClient*` coordinate-conversion seam |
| SC-1666 | **Medium** | Recording/debugger/remote-launch wiring; depends on the capture and input seams |
| SC-1667 | **Small–medium** | The routing itself is already carried; remaining work is real-agent diagnostics and startup behaviour |
| SC-1668 | **Medium** | Qualification requires a Linux host with Docker/PostgreSQL, neither available here |
| SC-1661 remaining | **Small** | Only the Felix/Shortcut handoff and the PR remain; no source work is outstanding |

The uncertainty is concentrated in SC-1662/SC-1665 because the upstream native project has not been
reviewed for inclusion and the capture boundary is the largest single rewrite, and in SC-1668 because
its evidence environment is not present on this host. Per-story estimates should be re-cut once
SC-1662 audits the vendored tree.

## Acceptance status

| SC-1661 completion criterion | Status |
|---|---|
| Reconciled work on the dedicated branch | **Done** — `45f943f`, `966e0e3` on `codex/sc-1661-chromascape-upstream-reconciliation` |
| No relevant custom change merely "probable"/"candidate"/"needs review" | **Done** — 13/13 files and both custom commits dispositioned |
| Source equivalence claims have behavioural evidence | **Done** — per-file conditions, literals, bodies, schema constraints and test assertions reviewed; the Fedora question settled by a compile experiment |
| Downstream ownership consistent with Shortcut | **Done in the documents** — see the corrected table above, `compatibility-map.md` and `upstream-commit-ledger.md` |
| Baseline validation has actual results and no **additional** observed failures | **Done** — see `baseline-validation.md`; the failing identity sets are identical and the blocked (Docker/PostgreSQL) suites are stated as *not verified* rather than as passing |
| Final code baseline pinned | **Done** — `966e0e3` |
| Validation evidence is durable and independently inspectable | **Done** — tracked artifacts with per-run revision, command, result and SHA-256 in `evidence/index.md`; the reviewed `.log` files are unignored by a single scoped negation |
| Final-code `checkstyleMain` recorded | **Done** — exit 0, 3291 warnings / 563 files; the 11 added findings in carried files are recorded (`evidence/index.md`, row 13) |
| Deferred findings are accountable | **Partial** — `SC-1661-D1` (V33) and `SC-1661-D2` (18 failures) written in `deferred-findings.md` with scope, evidence, validation cases and the intended tracker record; the tracker tickets do **not** exist yet |
| Epic and dependent stories reference it | **Pending** — Felix/Shortcut MCP unavailable in this session; outbox record written |
| Required delivery evidence and review complete | **Partial** — evidence is tracked and indexed; PR not yet raised |
| Felix and Shortcut reflect the verified outcome | **Pending** — same transport limitation |
