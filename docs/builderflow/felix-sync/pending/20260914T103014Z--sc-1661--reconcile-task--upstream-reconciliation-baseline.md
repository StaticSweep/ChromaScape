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

RemoteInput implementation (SC-1662–SC-1667); Linux process discovery; Linux capture; any
behaviour change to window selection, capture, frame delivery, input, or Builder Goal beyond
the carried Fedora launcher routing; copying entire dirty trees; `docs/builderflow/felix-notes/`
(Felix's generated mirror); machine-specific configuration (`.env.local`, `.gradle-home/`,
`.commandcode/`); unrelated worktree changes; and tracker completion claims before live gates pass.

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
- [x] Classify the both-changed main-checkout files and bound the residue to 13 files
- [x] **Adjudicate the 13-file residue per file** with behavioural evidence (all Superseded)
- [x] Locate the 16 untracked `src/` files in the candidate baseline and re-verify equivalence
      per file (5 identical / 2 whitespace-only / 9 substantive, each dispositioned)
- [x] Account for the untracked documentation and the two untracked consolidation files
- [x] Carry `d5b2d82` (replay fixture eol attributes) — commit `45f943f`
- [x] Adapt the Fedora `97284f1` / `ced7487` routing — commit `966e0e3`
- [x] Correct the downstream ownership to SC-1662–SC-1668 actual scopes
- [x] Make the compatibility decisions implementable (7 required decisions)
- [x] Resolve the launcher-JDK blocker and re-run baseline validation
- [ ] Raise the SC-1661 PR to the documented target (**not started**)
- [ ] Publish the baseline to the epic and SC-1662–SC-1668 (**blocked — no Felix/Shortcut MCP**)

### Test checklist

- [x] `./gradlew tasks` — exit 0
- [x] `./gradlew compileJava` — exit 0
- [x] `./gradlew compileTestJava` — exit 0
- [x] `./gradlew checkstyleMain` — exit 0 (3280 warnings, warning severity only)
- [x] `./gradlew spotlessCheck` — exit 1, **16 pre-existing files**; unchanged by this story
- [x] `./gradlew test` — exit 1, 2250 → 2255 tests, 110 failed, 13 skipped (identical failing set)
- [x] `./gradlew replayTest` — exit 1, 280 tests, 12 failed (identical failing set)
- [x] Focused launcher suites — exit 0, 175 tests, 0 failures
- [x] Confirm the 10 named test classes exist and now compile

## Note payload

# Implementation Evidence - SC-1661

## Changed files

Documentation **and two source carries**. The earlier revision of this record claimed
"documentation only"; that is no longer true because reconciliation produced two commits.

Carries:

- `45f943f` — `.gitattributes` (source `d5b2d82`)
- `966e0e3` — Fedora remote Builder Goal launcher routing (sources `97284f1`, `ced7487`):
  `.env.example`, `docs/FEDORA_REMOTE_MICRO6.md`,
  `Micro6RemoteLauncherController.java`, `Micro6RemoteLauncherService.java`,
  `BuilderGoalLauncherControlService.java`, `BuilderGoalRunnerService.java`,
  `Micro6OnlineClientRegistryService.java`, `Micro6TargetPreparationService.java`,
  `application.yml`, and five launcher test classes

Documents:

- `docs/upstream-integration/sc-1661/source-inventory.md`
- `docs/upstream-integration/sc-1661/custom-change-reconciliation.md`
- `docs/upstream-integration/sc-1661/upstream-commit-ledger.md`
- `docs/upstream-integration/sc-1661/compatibility-map.md`
- `docs/upstream-integration/sc-1661/baseline-validation.md`
- `docs/upstream-integration/sc-1661/integration-base.md`
- `docs/upstream-integration/sc-1661/evidence/**` (raw check logs)
- `docs/builderflow/felix-sync/pending/20260914T103014Z--sc-1661--reconcile-task--upstream-reconciliation-baseline.md`

Branch `codex/sc-1661-chromascape-upstream-reconciliation`, worktree
`IdeaProjects/ChromaScape-sc1661-reconciliation`, based on `583a05b`.
**Reconciled code baseline: `966e0e3`.**

## Verified findings

1. **The dirty-entry counts were not substantive.** Main checkout: 583 porcelain entries, of
   which only **87 substantive** (the rest CRLF-only). Consolidation: 1,420 entries,
   **0 substantive**. Durable-current: 1,414 entries, **0 substantive**. Both of the latter show
   an empty `git diff --ignore-cr-at-eol`.
2. **The main checkout's dirty state largely overlaps the candidate baseline, but equivalence had
   to be established per file.** Of the 87 substantive files, 21 are byte-identical and 72 of 85
   contain no identifier the baseline lacks. Identifier presence only *locates* a difference, so
   the 13 files that did introduce identifiers were reviewed individually. **All 13 are
   Superseded**, each with the replacement named; several dirty variants would have *reduced*
   validation or coverage if carried.
3. **The charset question is answered.** `TaskSourceManifestVerifier` carries four charset
   imports (`ByteBuffer`, `CharacterCodingException`, `CodingErrorAction`, `StandardCharsets`)
   that are **never used** — there is no charset-decoding code in that class in either revision.
   The real strict-UTF-8 decoder is `SourceManifestCanonicalJson.strictUtf8`, which the candidate
   baseline uses and the dirty `SourceManifestContract` would have bypassed by routing
   canonicalisation through the generic `CanonicalJsonHasher`.
4. **The 16 untracked `src/` files: 5 byte-identical, 2 whitespace-only, 9 substantively
   different.** The earlier "11 formatting or older variants" claim was untested and is corrected.
   Of the 9, `V32` is dispositioned Superseded (the dirty `DROP CONSTRAINT` is not idempotent)
   and **`V33` is an explicit deferral**: the candidate baseline's CHECK grouping is logically
   weaker (`dialogue IS NULL OR (…AND…)` leaves `spells`/`vars` unbounded), but V33 is already
   committed and Flyway-checksummed, so the remedy is a forward migration owned by the
   SC-1662–SC-1668 integration stream.
5. **The two outstanding custom commits are resolved.** `d5b2d82` was carried as `45f943f`
   (verified via `git check-attr`: exactly the five intended fixture paths, no blob change).
   The Fedora routing was adapted as `966e0e3`. The `97284f1` vs `ced7487` expression difference
   was settled **on the actual types**: `TargetClient` is a record, while `ClientState` is a plain
   class with a `String launchAttemptId` field, so the published `state.launchAttemptId()` **does
   not compile** — demonstrated with `error: cannot find symbol / symbol: method launchAttemptId()`.
   `ced7487`'s field access is correct.
6. **The SC-1199 durable tick-batch lineage is already in the baseline** — `16a3d72` merges
   `origin/main` (fully contained) with `d5b2d82` at merge base `e5cdf81`.
7. **The true upstream fork point is `46db1c4`**; the candidate baseline is 292 commits ahead of
   it while only 15 commits are upstream-only.
8. **All 15 upstream commits now have an actual implementation owner or an explicit deferral**:
   10 adapt/merge with owners drawn from the corrected scopes, 5 explicit deferrals
   (three version bumps, one README, one demo-script change). No commit is assigned to a story
   whose purpose differs from the change.
9. **No secrets found** in untracked source/docs. The single strong-pattern hit is a synthetic
   PEM header in `PatchCandidateWorkflowServiceTest.java:268`. The carried `.env.example` block
   adds blank placeholders only.

## Tests

**Executed.** The launcher-JDK blocker was cleared environmentally, with **no change to any build
file** — Gradle's Kotlin DSL needs a JDK it can parse, and a compatible JDK 17 (Corretto 17.0.20.1)
already existed on the host at `~/.jdks/corretto-17.0.20.1`. No wrapper upgrade, no toolchain
resolver.

| Command | Candidate baseline `4e0a3d5` | Reconciled `966e0e3` |
|---|---|---|
| `./gradlew tasks` | exit 0 | exit 0 |
| `./gradlew compileJava` | exit 0 | exit 0 (with `compileTestJava`) |
| `./gradlew checkstyleMain` | exit 0 (3280 warnings) | — |
| `./gradlew spotlessCheck` | exit 1 — 16 files | exit 1 — the **same 16** files |
| `./gradlew test` | exit 1 — 2250 / 110 failed / 13 skipped | exit 1 — 2255 / 110 / 13 |
| `./gradlew replayTest` | exit 1 — 280 / 12 failed | exit 1 — 280 / 12 |
| focused launcher suites | — | exit 0 — **175 / 0 failures** |

**No regression was introduced.** The strongest comparison is the failing *set*, not the counts:
the sorted `FAILED` test names are **identical** before and after for both `test` and `replayTest`
(`diff` returns empty). The reconciled branch adds 5 tests, and all 5 pass.

Failure classification (identical on both revisions): 34 Docker-unavailable, 10 PostgreSQL-refused
(`localhost:5433`), 48 ApplicationContext cascade from those, and 18 pre-existing gameplay
contract/provider assertion failures unrelated to SC-1661. Docker and PostgreSQL are unavailable on
this host, so database/migration/concurrency evidence cannot be produced here; that limitation is
recorded rather than hidden.

`./gradlew test` additionally needs the SC-967 fixture path on Linux
(`-Psc967VectorsPath=…`); without it the task fails before running any test. That is a documented
override the build itself provides, not a build change.

## Remaining work

1. **Raise the SC-1661 PR** from `codex/sc-1661-chromascape-upstream-reconciliation` to
   `integration/chromascape-consolidated-20260901`, reviewing the entire diff including the two
   carries, and explaining baseline selection, carried changes, exclusions, conflict resolutions,
   validation and rollback.
2. **Publish the pinned baseline `966e0e3`** to the epic and SC-1662–SC-1668 with the corrected
   ownership table (Felix + Shortcut MCP are unavailable in this session — replay the outbox
   record first).
3. **Hand the `V33` constraint finding onward** to the SC-1662–SC-1668 integration stream as the
   accountable owner of a forward migration.
4. **Decide the 18 pre-existing gameplay/provider test failures.** They are not SC-1661's and were
   left untouched; the integration stream must classify them.

## Checklist reconciliation

`## Implementation checklist` items marked `[x]` are supported by the artifacts listed under
`## Changed files` and by the commits named there. The two open items are the PR and the tracker
publication, both of which require capabilities absent from this session (GitHub push/review and
Felix/Shortcut MCP). No item is checked speculatively.

## State reconciliation

1. Create or reuse the Felix task (see `## Task resolution`), tags `shortcut:sc-1661`,
   `change:runtime`.
2. Persist the plan; satisfy `planning -> spec_ready`.
3. Transition `spec_ready -> in_progress`. Implementation and verification evidence are now
   complete, but **`done` must not be claimed**: the tracker has not been updated, and the PR is
   not raised. `in_review` is also premature until the PR exists.
4. Do not use `skip_validation`, spec waivers, or fabricated evidence.

## Shortcut reconciliation

- Intended transition after the Felix task exists: `started` (workflow state id `500000008`
  per `.felix/shortcut-sync.json`).
- Marker: `[felix-sync:<felix-task-id>:started]`. Search existing comments for the marker
  before posting; post at most once.
- **Baseline to publish: reconciled code baseline `966e0e3`** (candidate content baseline
  `583a05b`; upstream `5ea3b99`; merge base `46db1c4`).
- Update the epic and SC-1662–SC-1668 baseline references with the **corrected** scopes:
  SC-1662 native RemoteInput build and library loading; SC-1663 process discovery and explicit
  target selection; SC-1664 input attachment and mouse/keyboard lifecycle; SC-1665 rendered-buffer
  capture and screen API compatibility; SC-1666 recording, debugger, and remote-launch integration;
  SC-1667 Fedora startup and attachment diagnostics; SC-1668 host qualification and integration PR.
- Record SC-1661's branch, the two carry commits, and the review link on the story.
- `blocked` and `cancelled` ids are `null` in the mapping, so those transitions are skipped.
- **No Shortcut call was made.** No credential or MCP transport is available in this session.
  Nothing may be recorded as synchronized until the MCP call actually succeeds.

## Sync result

Pending.
