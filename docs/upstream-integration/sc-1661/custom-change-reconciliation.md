# SC-1661 — Custom Change Reconciliation

Comparison target: provisional baseline `583a05b91687d156b1baa52dbda2b219604c7b39`
(`wip/chromascape-consolidation-20260907`), as pinned in `source-inventory.md`.

## Headline finding: the dirty-entry counts are almost entirely line-ending noise

The previously recorded "578 changed/untracked entries in the main checkout and 1,420 in
consolidation" are **porcelain entry counts, not substantive code changes**. They were
decomposed as follows. The decisive test is `git diff --ignore-cr-at-eol`, which returns
files that still differ once carriage-returns at end-of-line are ignored.

| Worktree | Porcelain entries | Entries with `-uall` | Modified | Untracked files | **Substantive** (non-CR) |
|---|---|---|---|---|---|
| Main checkout | 578 | 2,321 | 511 | 1,809 | **87** |
| Consolidation | 1,420 | 1,421 | 1,419 | 2 | **0** |
| Durable-current | 1,414 | 1,414 | 1,414 | 0 | **0** |

### Consolidation and durable-current: zero substantive changes

Both worktrees report `1419 files changed, 302208 insertions(+), 302208 deletions(-)` —
perfectly symmetric, every file with equal insert and delete counts. Byte inspection
confirms the cause:

```
worktree : import java.nio.file.*;^M$        # CRLF
index    : import java.nio.file.*;$          # LF
```

and `git diff --ignore-cr-at-eol --stat` for that worktree is **empty**. There is no
`core.autocrlf`/`core.eol` setting in the worktrees; each checkout simply carries CRLF line
endings against an LF object store.

**Disposition: `Already included` / no action.** All 1,419 consolidation differences and all
1,414 durable-current differences are line-ending-only. Nothing is carried into the story
branch from either worktree as a result of these counts. This is exactly the case the story
plan warned about and must not be reported as substantive reconciliation work.

### Main checkout: 87 substantive files

`git diff --ignore-cr-at-eol --name-only` yields 87 files; the remaining 424 modified files
are CRLF-only. The 87 were then triaged three ways against main HEAD (`7ab2a80`) and the
baseline (`583a05b`):

| Classification | Count | Meaning |
|---|---|---|
| `ALREADY-IN-BASELINE` | 21 | Worktree content is byte-identical to the baseline; the edit is already represented by baseline commits |
| `NEW-UNCOMMITTED-WORK` | **0** | No file exists whose edit is absent from an unchanged baseline |
| `BOTH-CHANGED-REVIEW` | 64 | Baseline and worktree both moved; requires per-file adjudication |
| `deleted in worktree, absent from baseline` | 2 | Two SC-1199 outbox records moved out of `felix-sync/pending/` |

**No uncommitted edit in the main checkout is uniquely held.** Every one of the 87 either
already exists in the baseline or overlaps a baseline commit.

The 64 overlapping files were further split by which revision the worktree is closer to:

| Sub-classification | Count | Meaning |
|---|---|---|
| Superseded by baseline (`wt_vs_base < wt_vs_head`) | 24 | Baseline absorbed the edit; worktree is an earlier state |
| Divergent, review needed (`wt_vs_base > wt_vs_head`) | 40 | Baseline took a different path; content conflicts with the worktree edit |

All 64 have at least one baseline commit touching them since `7ab2a80`, which is consistent
with the baseline having been cut from the same edit sessions.

Representative evidence:

| File | lines wt↔baseline | lines wt↔HEAD | Reading |
|---|---|---|---|
| `web/config/SourceTrustProperties.java` | 2 | 62 | Baseline effectively equals the worktree edit — superseded |
| `web/replay/worker/ReplayWorker.java` | 15 | 33 | Baseline close to worktree — superseded |
| `web/debug/tickdebugger/dto/TickBatchMessage.java` | 427 | 44 | Baseline diverged substantially; worktree edit not reflected |

The 40 divergent files are dominated by the SC-810 source-provenance surface
(`routine/source/*`, `SourceManifest*`, `Source*Test`) and the `TickBatchMessage` /
`TickStreamIngestionService` durable tick-batch pair.

### Unresolved conflict requiring direction

`src/main/java/com/chromascape/web/debug/tickdebugger/dto/TickBatchMessage.java` cannot be
resolved from code and history alone:

- main worktree carries a 44-line uncommitted edit against `7ab2a80`;
- the baseline is 427 lines away from that worktree state and has 2 commits touching the
  file since `7ab2a80` (the SC-1199 durable tick-batch lineage);
- the worktree edit is therefore neither present in nor obviously superseded by the
  baseline's rewritten version.

Per the story instructions this specific conflict is recorded rather than silently resolved
with a blanket "ours"/"theirs" choice. The same adjudication is required, en bloc, for the 40
divergent files before the main checkout can be declared fully reconciled.

## Uncommitted work inventory (required fields)

| Source worktree | File or change group | Staged / unstaged / untracked | Behaviour affected | Already represented elsewhere? | Disposition | Evidence / resulting commit |
|---|---|---|---|---|---|---|
| Main checkout | 424 CRLF-only modified files | unstaged | none (EOL only) | n/a | **Defer / drop** — no content change | `git diff --ignore-cr-at-eol` empty for these paths |
| Main checkout | 21 files identical to baseline | unstaged | SC-810 source provenance, replay capability | **Yes** — baseline `583a05b` | **Already included** | `diff --strip-trailing-cr` = identical |
| Main checkout | 24 files closer to baseline than HEAD | unstaged | SC-810 / replay / orchestration | **Yes** — baseline commits since `7ab2a80` | **Superseded** (confirm per-file before closing) | `both64.txt` evidence, supersession metric |
| Main checkout | 40 divergent files (SC-810 source surface, `TickBatchMessage`, tick ingestion) | unstaged | Source-manifest trust, tick-batch DTO | **Partial / conflict** | **Defer — needs direction** | listed in `both64.txt`; see conflict above |
| Main checkout | `docs/builderflow/felix-sync/pending/*sc-1199*.md` (2) | unstaged deletion | Felix outbox bookkeeping | Yes — records now under `felix-sync/synced/sc-1199/` | **Already included** (deletion consistent with baseline) | absent from `583a05b`; synced copies present |
| Main checkout | `.gradle-home/` (1,734 files) | untracked | none — Gradle user-home cache | n/a | **Exclude — generated** | `caches/8.14.2/...*.bin`, `*.pom`, `*.marker`; not yet in `.gitignore` |
| Main checkout | 16 new source files under `src/` | untracked | see below | **No** | **Carry — deliberate** | see next table |
| Main checkout | 59 untracked files under `docs/` | untracked | Felix notes, felix-sync outbox records, SC-810 spec | No | **Carry selectively — bookkeeping, not code** | `felix-notes/`, `felix-sync/` |
| Consolidation | 1,419 modified files | unstaged | none (EOL only) | n/a | **Already included** | `git diff --ignore-cr-at-eol --stat` empty |
| Consolidation | 1 untracked entry (2 files) | untracked | n/a | n/a | **Defer — inspect before any copy** | counted, not copied |
| Durable-current | 1,414 modified files | unstaged | none (EOL only) | n/a | **Already included** | `git diff --ignore-cr-at-eol --stat` empty |
| Fedora worktree | none | — | — | — | clean worktree | `git status --porcelain` = 0 |

### Untracked new source in the main checkout (16 files, not in the baseline)

These are genuinely new files with no committed representation and are the only main-checkout
source additions eligible to be carried forward:

- `src/main/java/com/chromascape/web/aiplayer/routine/source/SourceManifestSchemaValidator.java`
- `src/main/java/com/chromascape/web/gameplay/orchestration/accountbuilder/AccountBuilderAuthorityVerifier.java`
- `src/main/java/com/chromascape/web/replay/catalog/ReplayNpcPinValidator.java`
- `src/main/java/com/chromascape/web/replay/compiler/action/CastSpellAction.java`
- `src/main/java/com/chromascape/web/replay/compiler/action/EquipItemAction.java`
- `src/main/java/com/chromascape/web/replay/compiler/action/InteractGroundItemAction.java`
- `src/main/java/com/chromascape/web/replay/compiler/action/InteractInventoryItemAction.java`
- `src/main/java/com/chromascape/web/replay/compiler/action/InteractWidgetAction.java`
- `src/main/java/com/chromascape/web/replay/compiler/action/SelectDialogueOptionAction.java`
- `src/main/resources/db/migration/V32__sc1431_recovery_correlation.sql`
- `src/main/resources/db/migration/V33__replay_snapshot_state_sections.sql`
- `src/test/java/com/chromascape/web/gameplay/orchestration/accountbuilder/AccountBuilderOrchestrationPostgresConcurrencyTest.java`
- `src/test/java/com/chromascape/web/gameplay/orchestration/accountbuilder/AccountBuilderOrchestrationRestartTest.java`
- `src/test/java/com/chromascape/web/gameplay/orchestration/accountbuilder/AccountBuilderOrchestrationRollbackTest.java`
- `src/test/java/com/chromascape/web/gameplay/orchestration/accountbuilder/AccountBuilderPostgresTestFixtures.java`
- `src/test/java/com/chromascape/web/replay/manifest/ScenarioManifestV2CompilerTest.java`

**Not yet carried.** These 16 files plus the 40 divergent files require the adjudication
decision recorded above before they are committed to the story branch. They were deliberately
*not* copied wholesale, per the instruction not to copy entire dirty trees.

## Secrets check

Untracked `src/` and `docs/` content plus the 87 substantive modified files were scanned for
credential patterns.

- **No credentials found.** The only strong-pattern hit is `PatchCandidateWorkflowServiceTest.java:268`,
  `String pem = "-----BEGIN PRIVATE KEY-----\n"` — a synthetic PEM header used to exercise
  signature verification, with no key material.
- All other matches were prose in design documents ("private key is read from a secret
  provider", "never include secret values", "metadata token cannot read content").
- `.env.local` exists in the main checkout (395 bytes, machine-specific). It is **not** in
  the changed set and must never be copied into the story branch or evidence.

## Custom branch reconciliation

### Fedora launcher routing — duplicate under two SHAs

`97284f1` (Fedora, `codex/fedora-remote-executor`) and `ced7487` (durable-current) share the
subject "Add remote Fedora Builder Goal launcher routing", touch the identical 14 files, and
both report `630 insertions(+), 5 deletions(-)`. However their blob hashes and **patch-ids
differ** (`8c733b0c…` vs `176ff28c…`), so `git cherry` correctly refuses to treat them as
patch-equivalent. A direct patch diff isolates the reasons:

- differing hunk context and line numbers (expected — different parents);
- a different `.env.example` neighbourhood;
- **one real code difference**:
  - Fedora `97284f1`: `java.util.Objects.equals(target.launchAttemptId(), state.launchAttemptId());`
  - durable-current `ced7487`: `java.util.Objects.equals(target.launchAttemptId(), state.launchAttemptId);`

That is a method invocation versus a field access — a genuine semantic difference, so this
must be an **Adapt**, not a blind cherry-pick. The Fedora `97284f1` is the published
`origin/codex/fedora-remote-executor` revision and is treated as the authoritative one.

| Change | Disposition | Reason |
|---|---|---|
| Fedora remote Builder Goal launcher routing (`97284f1`) | **Adapt** | Published on `origin`; duplicate `ced7487` differs in one expression. Preserve the Fedora form; reconcile against baseline's `.env.example` |
| `d5b2d82` "Normalize replay fixture attributes" | **Cherry-pick candidate** | One-line `.gitattributes` addition; independent and bounded |
| SC-1199 durable tick-batch lineage | **Already included** | Merge base `e5cdf81`; entire lineage is an ancestor of the baseline |
| `origin/main` / `origin/main2` | **Already included** | 0 target-only commits vs baseline |
| Consolidation CRLF noise | **Already included / drop** | EOL-only |
| Main-checkout CRLF noise | **Drop** | EOL-only |
| Main-checkout 40 divergent files | **Defer** | Conflict recorded; requires direction |

Divergence figures after reconciliation of the above: the baseline remains **29 commits ahead
of durable-current with 2 non-merge commits unique to it** (`ced7487`, `d5b2d82`) and
**77 ahead of Fedora with 1 unique commit** (`97284f1`).

## Method note

Every figure above was recomputed in this story; none were inherited from the earlier checks.
The `git cherry` result disproves the assumption that the two Fedora commits are the same
change, and the `--ignore-cr-at-eol` decomposition disproves the assumption that the
578/1,420 dirty counts represent substantive work.
