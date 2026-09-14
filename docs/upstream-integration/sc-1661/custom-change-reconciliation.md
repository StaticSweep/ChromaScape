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
| `NEW-UNCOMMITTED-WORK` | **0** | This test required `HEAD == baseline` and so never fired; it proves nothing on its own. See the identifier-level analysis below for the real residue (13 files) |
| `BOTH-CHANGED-REVIEW` | 64 | Baseline and worktree both moved; requires per-file adjudication |
| `deleted in worktree, absent from baseline` | 2 | Two SC-1199 outbox records moved out of `felix-sync/pending/` |

### Adjudication of the 64 both-changed files

Four progressively stricter tests were run. Each overturned the previous one's conclusion, so
the final result is materially narrower than the first passes suggested. **The first two
readings recorded in earlier drafts of this document were wrong and are corrected here.**

| Test | Method | Result | Why it is not decisive on its own |
|---|---|---|---|
| Raw line-count | `wt_vs_base` vs `wt_vs_head` | 24 superseded / 40 "divergent" | Cannot separate "the baseline rewrote more" from "the worktree holds unique work" |
| Three-way merge | `git merge-file` (worktree, HEAD, baseline) | 5 clean / **35 conflict** | A conflict is *expected* when the baseline committed a superset of the same edits — both sides touched the same regions |
| Whole-line set | lines added by the worktree that are absent from the baseline | 53 files | Formatting-sensitive: any leading-whitespace difference counts as "unique" |
| **Identifier set** | identifiers added by the worktree that are absent from the baseline | **13 files** | Identifiers survive reformatting — decisive |

The decisive cross-check, which exposes why the earlier tests over-reported:

- Baseline `TickStreamIngestionService` lines 496–556 are the **same** dialogue/spells/vars
  handling as worktree lines 234–294 — same section checks, same snapshot setters.
- Baseline `SourceRunBinding` carries **all** of `repositoryId`, `commitId`, `treeId`,
  `buildId`; the worktree only writes them as compact
  `@Column(...) private String x;` one-liners where the baseline uses separate annotated
  lines. Identical feature, different formatting.
- `TickSnapshot` is byte-comparable in both.

The worktree edits are therefore early, smaller, differently-formatted stages of work the
baseline later completed and committed. All 64 overlapping files have at least one baseline
commit touching them since `7ab2a80`, consistent with the baseline having been cut from the
same edit sessions.

Representative sizes — worktree's own edit versus the baseline's change, both measured against
`7ab2a80`. In every one of the 40 the baseline's change is far larger, which is the signature
of the worktree holding an earlier, smaller stage of the same work:

| File | Worktree edit | Baseline change | Identifier verdict |
|---|---|---|---|
| `.../routine/service/PatchCandidateWorkflowService.java` | 18 | 1001 | covered by baseline |
| `.../tickdebugger/service/TickStreamIngestionService.java` | 11 | 863 | covered by baseline |
| `.../tickdebugger/dto/TickBatchMessage.java` | 44 | 469 | covered by baseline |
| `.../routine/source/SourceRetentionService.java` | 5 | 79 | covered by baseline |

### Residual work needing direction (13 files, bounded)

Not everything is cleanly superseded. Exactly **13 of the 85** substantive modified files
introduce identifiers the baseline lacks. Only these require adjudication:

| File | Identifiers absent from baseline | Reading |
|---|---|---|
| `src/test/.../source/SourceRunOrchestrationIntegrationTest.java` | 106 | Different test-method names (e.g. `captureCompletionRollsBackSourceRunAndCaptureWritesBeforeRecordingUncertain`) and injected services (`CaptureOperationResultService`, `CaptureRecordingGateway`) |
| `src/main/.../source/TaskSourceManifestVerifier.java` | 16 | `ByteBuffer`, `StandardCharsets`, `CodingErrorAction`, `CharacterCodingException`, `charset`, `supportedSchema` — a charset-decoding **implementation** difference, not a missing feature |
| `docs/SC-810-SOURCE-PROVENANCE.md` | 10 | Prose |
| `docs/adr/SC-810-source-provenance-and-signing.md` | 9 | Prose |
| `src/test/.../source/TaskSourceManifestVerifierTest.java` | 3 | Test naming |
| `src/test/.../capability/XrspsStartupValidatorTest.java` | 3 | Test naming |
| `src/test/.../capability/XrspsStartupValidatorCapabilityContractTest.java` | 3 | Test naming |
| `src/main/.../replay/capability/CapabilityManifest.java` | 2 | Small |
| `src/test/.../source/SourceFetchTransactionSuspensionTest.java` | 2 | Test naming |
| `src/test/.../source/SourceManifestConcurrentPersistenceIntegrationTest.java` | 2 | Test naming |
| `src/main/.../source/SourceManifestContract.java` | 1 | Small |
| `src/main/.../accountbuilder/InMemoryAccountBuilderOrchestrationStore.java` | 1 | Small |
| `src/test/.../testsupport/IsolatedPostgresTestDatabase.java` | 1 | Small |

Seven are test files, two are design documents, and four are one- or two-identifier deltas.
The probable disposition is *superseded* throughout, but whether the baseline's later rewrite
intentionally dropped the worktree's test coverage or its stricter charset-decoding variant is
a judgement call. It is recorded rather than resolved with a blanket "ours"/"theirs" choice.

This residue is small and does **not** block pinning the baseline.

## Uncommitted work inventory (required fields)

| Source worktree | File or change group | Staged / unstaged / untracked | Behaviour affected | Already represented elsewhere? | Disposition | Evidence / resulting commit |
|---|---|---|---|---|---|---|
| Main checkout | 424 CRLF-only modified files | unstaged | none (EOL only) | n/a | **Defer / drop** — no content change | `git diff --ignore-cr-at-eol` empty for these paths |
| Main checkout | 21 files identical to baseline | unstaged | SC-810 source provenance, replay capability | **Yes** — baseline `583a05b` | **Already included** | `diff --strip-trailing-cr` = identical |
| Main checkout | 51 files whose worktree edit is fully covered by the baseline at whole-line level (72 of 85 at identifier level) | unstaged | SC-810 source provenance, replay, orchestration | **Yes** — baseline commits since `7ab2a80` | **Superseded** | identifier-set test; see adjudication above |
| Main checkout | 13 files introducing identifiers the baseline lacks (7 tests, 2 docs, 4 small) | unstaged | Test coverage, source-manifest charset decoding | **Partial** | **Defer — bounded residue, needs direction** | `ident-missing.txt`; see residual section |
| Main checkout | `docs/builderflow/felix-sync/pending/*sc-1199*.md` (2) | unstaged deletion | Felix outbox bookkeeping | Yes — records now under `felix-sync/synced/sc-1199/` | **Already included** (deletion consistent with baseline) | absent from `583a05b`; synced copies present |
| Main checkout | `.gradle-home/` (1,734 files) | untracked | none — Gradle user-home cache | n/a | **Exclude — generated** | `caches/8.14.2/...*.bin`, `*.pom`, `*.marker`; not yet in `.gitignore` |
| Main checkout | 16 untracked files under `src/` | untracked | schema validation, replay actions, migrations, orchestration tests | **Yes** — all 16 exist in the baseline (5 byte-identical, 11 formatting/older variants) | **Already included — nothing to carry** | `git cat-file -e 583a05b:<path>` for all 16; see next section |
| Main checkout | 59 untracked files under `docs/` | untracked | Felix notes, felix-sync outbox records, SC-810 spec | No | **Carry selectively — bookkeeping, not code** | `felix-notes/`, `felix-sync/` |
| Consolidation | 1,419 modified files | unstaged | none (EOL only) | n/a | **Already included** | `git diff --ignore-cr-at-eol --stat` empty |
| Consolidation | 1 untracked entry (2 files) | untracked | n/a | n/a | **Defer — inspect before any copy** | counted, not copied |
| Durable-current | 1,414 modified files | unstaged | none (EOL only) | n/a | **Already included** | `git diff --ignore-cr-at-eol --stat` empty |
| Fedora worktree | none | — | — | — | clean worktree | `git status --porcelain` = 0 |

### Untracked source in the main checkout (16 files — all already in the baseline)

These present as new files in the main checkout, but **every one already exists in the
baseline**. Five are byte-identical; eleven differ only in formatting, or in the baseline
being the improved version:

| File | Verdict |
|---|---|
| `.../accountbuilder/AccountBuilderAuthorityVerifier.java` | byte-identical |
| `.../compiler/action/InteractInventoryItemAction.java` | byte-identical |
| `.../compiler/action/InteractWidgetAction.java` | byte-identical |
| `.../compiler/action/SelectDialogueOptionAction.java` | byte-identical |
| `.../accountbuilder/AccountBuilderPostgresTestFixtures.java` | byte-identical |
| `.../db/migration/V32__sc1431_recovery_correlation.sql` | baseline uses `DROP CONSTRAINT IF EXISTS`; worktree uses the non-idempotent `DROP CONSTRAINT`. **Baseline is safer** |
| `.../db/migration/V33__replay_snapshot_state_sections.sql` | identical statement content; diff is only CHECK-constraint line wrapping |
| the remaining 9 (schema validator, NPC-pin validator, 6 action classes, 3 orchestration tests, manifest test) | formatting-level differences only |

So **nothing from this group needs carrying** — the earlier draft's claim that these were
"genuinely new files with no committed representation" was wrong.

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

**Nothing is carried from this group.** The list is retained only to show the files were
individually examined, not copied wholesale, per the instruction not to copy entire dirty
trees.

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
| Main-checkout 13-file residual set | **Defer** | Bounded residue introducing identifiers the baseline lacks; recorded, not resolved by blanket "ours"/"theirs" |

Divergence figures after reconciliation of the above: the baseline remains **29 commits ahead
of durable-current with 2 non-merge commits unique to it** (`ced7487`, `d5b2d82`) and
**77 ahead of Fedora with 1 unique commit** (`97284f1`).

## Method note

Every figure above was recomputed in this story; none were inherited from the earlier checks.
The `git cherry` result disproves the assumption that the two Fedora commits are the same
change, and the `--ignore-cr-at-eol` decomposition disproves the assumption that the
578/1,420 dirty counts represent substantive work.
