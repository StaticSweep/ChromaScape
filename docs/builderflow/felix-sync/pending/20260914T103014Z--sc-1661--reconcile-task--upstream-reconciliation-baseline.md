---
schema_version: 1
offline_record_id: "e34efafe-81d6-44c8-bd44-edc2e1f1266a"
sync_status: pending
project: 'C:\Dev\IdeaProjects\ChromaScape'
shortcut_story_id: 1661
shortcut_tag: "shortcut:sc-1661"
felix_task_id: null
operation: reconcile_task
target_note_id: null
title: "SC-1661 - Reconcile upstream changes and pin the integration base"
note_type: documentation
stable_tags:
  - shortcut:sc-1661
  - change:runtime
  - evidence:implementation
  - evidence:verification
  - offline-sync:e34efafe-81d6-44c8-bd44-edc2e1f1266a
parent_id: null
intended_entity_links:
  - entity_type: shortcut_story
    entity_id: "1661"
    link_strength: primary
created_at: "2026-09-14T10:30:14Z"
intended_felix_task_state: in_progress
intended_felix_spec_state: spec_ready
intended_shortcut_transition: started
intended_shortcut_idempotency_marker: "[felix-sync:<felix-task-id>:started]"
---

## Task resolution

No Felix MCP transport is available in the session that produced this record, so no task
could be searched. **Search for an existing task tagged `shortcut:sc-1661` under workflow
`shortcut_delivery` and canonical project `C:\Dev\IdeaProjects\ChromaScape` before doing
anything else. Do not create a duplicate.**

If no task exists, create it with exactly:

- workflow: `shortcut_delivery`
- project: `C:\Dev\IdeaProjects\ChromaScape`
- title: `SC-1661 - Reconcile upstream changes and pin the integration base`
- source: entity type `shortcut_story`, entity id `1661`, link strength `primary`
- stable tags: `shortcut:sc-1661`, `change:runtime`
- classification tag (**exactly one authoritative tag**): `change:runtime`

The source story is https://app.shortcut.com/leeuwbot/story/1661.

### Scope

Establish a trustworthy starting point for the Linux implementation of ChromaScape:
reconcile the custom baseline, produce an upstream integration ledger and compatibility map,
run baseline validation, and leave an isolated branch ready for SC-1662 onward.

In scope: the isolated SC-1661 worktree and branch; pinning of all source revisions; the
custom-change reconciliation; the upstream commit ledger; the compatibility map; baseline
validation attempts; and the durable artifacts under `docs/upstream-integration/sc-1661/`.

### Exclusions

RemoteInput implementation (SC-1662–SC-1667); any behaviour change to window selection,
capture, frame delivery, input, or Builder Goal; copying entire dirty trees; forcing the
13-file residual set to a resolution rather than recording it; `docs/builderflow/felix-notes/`
(Felix's generated mirror); machine-specific configuration (`.env.local`, `.gradle-home/`);
unrelated worktree changes; and tracker completion claims before live gates pass.

### Acceptance criteria (Given / When / Then)

1. Given the consolidation commit `583a05b`, when the story worktree is created, then it is a
   separate worktree on `codex/sc-1661-chromascape-upstream-reconciliation` and all existing
   worktrees keep their branches and uncommitted changes.
2. Given six pinned source revisions, when they are re-fetched, then any moved revision is
   documented with its newly observed value and no previously collected evidence is silently
   replaced.
3. Given ~2,000 dirty porcelain entries across three worktrees, when they are decomposed,
   then staged/unstaged/untracked and line-ending-only versus substantive changes are
   separated and each change group has an explicit disposition.
4. Given each candidate custom branch, when compared to the baseline, then divergence is
   recomputed and each relevant change has exactly one recorded disposition
   (already included / merge / cherry-pick / adapt / defer).
5. Given the pinned upstream revision, when upstream-only commits are enumerated, then every
   one has a disposition, an owning story, and required verification.
6. Given the compatibility boundaries, when documented, then current callers, upstream
   differences, proposed adaptation, owning story, and regression tests are recorded.
7. Given the baseline, when checks are run, then each result is truthful: exact commit,
   command, exit code, counts, first causal failure, and whether it is pre-existing.
8. Given the story completes, then no user change is lost and rollback is documented.

### Implementation checklist

- [x] Verify `583a05b` still represents the intended consolidation work
- [x] Create the isolated worktree and branch from `583a05b`
- [x] Fetch origin and upstream remotes without touching existing worktrees
- [x] Record full SHAs, dates, tracking relationships and worktree locations
- [x] Decompose dirty trees into staged / unstaged / untracked
- [x] Separate line-ending-only from substantive changes
- [x] Identify generated files, logs and local configuration
- [x] Check for secrets before copying anything
- [x] Identify changes already represented by commits elsewhere
- [x] Recompute ancestry and patch equivalence for every candidate source
- [x] Recompute the upstream merge base and upstream-only commit list
- [x] Write `source-inventory.md`, `custom-change-reconciliation.md`,
      `upstream-commit-ledger.md`, `compatibility-map.md`, `baseline-validation.md`,
      `integration-base.md`
- [x] Adjudicate the both-changed main-checkout files (13-file residual set recorded)
- [x] Confirm the 16 untracked `src/` files (all already present in the baseline)
- [ ] Cherry-pick `d5b2d82` and adapt the Fedora `97284f1` change (**not started**)
- [ ] Install/provision a JDK 17 and re-run baseline validation (**blocked - environment**)

### Test checklist

- [ ] `./gradlew compileJava` — **blocked**, not passed
- [ ] `./gradlew test` — **blocked**, not run
- [ ] `./gradlew replayTest` — **blocked**, not run
- [ ] `./gradlew spotlessCheck checkstyleMain` — **blocked**, not run
- [x] Confirm the 10 named test classes exist in the baseline

## Note payload

# Implementation Evidence - SC-1661

## Changed files

Documentation only; no production or test source was modified by this story.

- `docs/upstream-integration/sc-1661/source-inventory.md`
- `docs/upstream-integration/sc-1661/custom-change-reconciliation.md`
- `docs/upstream-integration/sc-1661/upstream-commit-ledger.md`
- `docs/upstream-integration/sc-1661/compatibility-map.md`
- `docs/upstream-integration/sc-1661/baseline-validation.md`
- `docs/upstream-integration/sc-1661/integration-base.md`
- `docs/builderflow/felix-sync/pending/20260914T103014Z--sc-1661--reconcile-task--upstream-reconciliation-baseline.md`

Branch `codex/sc-1661-chromascape-upstream-reconciliation`, worktree
`IdeaProjects/ChromaScape-sc1661-reconciliation`, based on `583a05b`.

## Verified findings

1. **The dirty-entry counts were not substantive.** Main checkout: 578 porcelain entries,
   511 modified, of which only **87 substantive** (424 CRLF-only). Consolidation: 1,419
   modified, **0 substantive**. Durable-current: 1,414 modified, **0 substantive**. Both of the
   latter show `302208 insertions / 302208 deletions` and an empty
   `git diff --ignore-cr-at-eol`.
2. **The main checkout's dirty state is subsumed by the baseline.** Of the 87 substantive
   files: 21 byte-identical to the baseline, 72 of 85 fully covered at identifier level, and a
   bounded 13-file residue (7 tests, 2 docs, 4 small deltas) that introduces identifiers the
   baseline lacks. An earlier draft claimed "40 divergent files"; that was an artefact of a
   formatting-sensitive line-count metric and has been corrected.
3. **All 16 untracked `src/` files already exist in the baseline** — 5 byte-identical, 11
   formatting or older variants. The baseline's `V32` migration is the *safer* one
   (`DROP CONSTRAINT IF EXISTS`) and `V33` is identical in statement content. Nothing needed
   carrying, so the reconciled baseline is `583a05b` itself.
4. **The SC-1199 durable tick-batch lineage is already in the baseline** — `16a3d72` merges
   `origin/main` (fully contained) with `d5b2d82` at merge base `e5cdf81`.
5. **The Fedora launcher change exists twice under different SHAs** (`97284f1`, `ced7487`) with
   different patch-ids and one real expression difference
   (`state.launchAttemptId()` vs `state.launchAttemptId`). `git cherry` does **not** treat them
   as equivalent.
6. **The true upstream fork point is `46db1c4`**; the baseline is 292 commits ahead of it while
   only 15 commits are upstream-only.
7. **All 15 upstream commits are accounted for**: 2 merge, 8 adapt, 1 defer (`69ff913`
   RemoteInput → SC-1662–SC-1667), 4 already-satisfied/defer.
8. **No secrets found** in untracked source/docs. The single strong-pattern hit is a synthetic
   PEM header in `PatchCandidateWorkflowServiceTest.java:268`.

## Tests

**None executed.** All Gradle invocations are blocked by a pre-existing environmental
incompatibility:

```
java.lang.IllegalArgumentException: 25.0.2
    at org.jetbrains.kotlin.com.intellij.util.lang.JavaVersion.parse(JavaVersion.java:307)
```

Gradle 8.14.2 ships Kotlin 2.0.21, whose bundled IntelliJ `JavaVersion.parse` cannot parse the
JDK string `25.0.2`. `build.gradle.kts` is a Kotlin script, so this aborts before any task
runs — `./gradlew tasks` fails identically. Reproduced on a clean `583a05b` with zero story
changes, so it is **pre-existing, not introduced**.

Environment: OpenJDK 25.0.2 (Red Hat), Gradle 8.14.2, Linux 6.19.10-300.fc44. The build
declares `JavaLanguageVersion.of(17)`; no JDK 17 is installed.

Existence of all 10 named test classes was verified by source inspection.

## Remaining work

1. **Review the 13-file residual set** (7 tests, 2 docs, 4 small deltas) that introduces
   identifiers the baseline lacks. The probable disposition is superseded throughout, but
   whether the baseline's later rewrite intentionally dropped the worktree's test coverage or
   its stricter charset-decoding variant is a judgement call for the story owner.
2. **Provision a JDK 17** (or add a toolchain resolver, owned by SC-1666) and re-run
   `compileJava`, `test`, `replayTest`, `spotlessCheck checkstyleMain`. This is now the only
   item blocking baseline regression evidence.
3. **Cherry-pick `d5b2d82`; adapt Fedora `97284f1`.**
4. **Publish the pinned baseline `583a05b`** to the epic and SC-1662–SC-1668 (via Felix +
   Shortcut MCP, unavailable in this session — replay the outbox record first).

## Checklist reconciliation

`## Implementation checklist` items marked `[x]` are supported by the artifacts listed under
`## Changed files`. Items marked `[ ]` remain objectively unverified; none are checked
speculatively. `## Test checklist` items are all unchecked because no test executed.

## State reconciliation

1. Create or reuse the Felix task (see `## Task resolution`), tags `shortcut:sc-1661`,
   `change:runtime`.
2. Persist the plan; satisfy `planning -> spec_ready`.
3. Transition `spec_ready -> in_progress` — the reconciliation analysis is genuinely complete
   for the parts marked verified above, but items 1–5 of `## Remaining work` are open, so
   **`in_review` and `done` must not be claimed**.
4. Do not use `skip_validation`, spec waivers, or fabricated evidence.

## Shortcut reconciliation

- Intended transition after the Felix task exists: `started` (workflow state id `500000008`
  per `.felix/shortcut-sync.json`).
- Marker: `[felix-sync:<felix-task-id>:started]`. Search existing comments for the marker
  before posting; post at most once.
- Also update the epic and SC-1662–SC-1668 with the pinned revisions and the compatibility
  boundary ownership once the final baseline SHA exists.
- `blocked` and `cancelled` ids are `null` in the mapping, so those transitions are skipped.
- **No Shortcut call was made.** No credential or MCP transport is available in this session.
  Nothing may be recorded as synchronized until the MCP call actually succeeds.

## Sync result

Pending.
