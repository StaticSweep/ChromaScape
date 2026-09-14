# SC-1661 — Custom Change Reconciliation

Comparison target: **candidate** baseline `583a05b91687d156b1baa52dbda2b219604c7b39`
(`wip/chromascape-consolidation-20260907`), as pinned in `source-inventory.md`.
Main-checkout working tree observed at HEAD `7ab2a80` plus uncommitted changes.

This document was revised during reconciliation. The earlier version used an identifier-set test
to conclude that the dirty changes were "fully subsumed" by the baseline. **That conclusion was
withdrawn**: an identifier absent from the baseline only *locates* a difference. It cannot
establish that the baseline preserved, replaced, or dropped the behaviour. Every file below has
therefore been reviewed at the level of conditions, literals, method bodies, exceptions,
annotations, schema constraints and test assertions.

**Result: no relevant custom change is left "probable", "candidate" or "needs review".** All 13
residual files are adjudicated, both outstanding custom commits are resolved, and the untracked
inventory is complete.

## Method

For each file three artefacts were compared — main-checkout HEAD (`7ab2a80`), the main-checkout
working copy, and the candidate baseline (`583a05b`) — with `--ignore-cr-at-eol` so CRLF-only
churn does not register. Differences were classified as formatting-only (whitespace-collapsed
token streams identical) or substantive, and the baseline commits touching each file were read to
decide whether later code intentionally replaced the dirty version.

```
git -C <main>  diff --ignore-cr-at-eol 7ab2a80  -- <path>   # what the dirty edit did
git -C <main>  diff --ignore-cr-at-eol 583a05b -- <path>   # dirty vs candidate baseline
git -C <main>  log  --oneline 7ab2a80..583a05b -- <path>   # who replaced it later
```

**Sizes for orientation** (recomputed): main checkout 583 porcelain entries of which **87** are
substantive non-CRLF files; consolidation 1,420 entries and durable-current 1,414 entries, both
with an **empty** `git diff --ignore-cr-at-eol`. The line-ending finding stands: those two
worktrees carry zero substantive content change.

## Adjudication of the 13 residual files

All thirteen resolve to **Superseded**. In every case the candidate baseline holds a later, larger
or stricter implementation of the same work, and several of the dirty variants would *reduce*
validation or coverage if carried.

### 1. `TaskSourceManifestVerifier.java`

```
File:                             src/main/java/com/chromascape/web/aiplayer/routine/source/TaskSourceManifestVerifier.java
Source revision/worktree:         Main checkout working copy at 7ab2a80 + changes (461 lines)
Behavior introduced by the
  working change:                 Drops generated-mapping parsing and validation entirely
                                  (no parseGeneratedMappings, no generated_mappings index check,
                                  TaskSourceManifest built without generatedMappings); replaces
                                  the explicit missing-signature failure with reliance on schema
                                  validation; leaves four unused imports (ByteBuffer,
                                  CharacterCodingException, CodingErrorAction, StandardCharsets)
Equivalent baseline
  implementation:                 583a05b (598 lines). Same pipeline plus generated-mapping
                                  parsing/validation and a distinct SOURCE_SIGNATURE_INVALID for
                                  a missing signature
Material differences:             (a) generated mappings are NOT verified by the dirty version;
                                  (b) a missing signature loses its stable failure code;
                                  (c) the four charset imports are dead — no charset code exists
                                  in this class in either revision
Disposition:                      Superseded
Reason:                           The dirty variant is an earlier stage. Baseline commit 1cef30e
                                  ("Align source generated-mapping verification with the current
                                  Micro6 schema") deliberately added the generated-mapping gate;
                                  carrying the dirty file would silently remove it
Verification:                     Method-body and call-site inspection of both revisions; grep
                                  shows the four imports are imported and never used;
                                  ./gradlew compileJava on 583a05b exits 0
Resulting commit or follow-up:    None — no carry
```

**Charset question, answered.** The plan asked whether the charset-decoding differences change
accepted input or failure behaviour. They do not change anything in this class: there is **no
charset-decoding implementation** in either revision — the dirty working copy merely retains four
imports left over from an abandoned refactor. The real strict-UTF-8 decoder (`strictUtf8` with
`CodingErrorAction.REPORT`, `ByteBuffer`) lives in `SourceManifestCanonicalJson`, and it is item 3
where the dirty change *does* alter behaviour.

### 2. `TaskSourceManifestVerifierTest.java`

```
File:                             src/test/java/com/chromascape/web/aiplayer/routine/source/TaskSourceManifestVerifierTest.java
Source revision/worktree:         Main checkout working copy at 7ab2a80 + changes
Behavior introduced by the
  working change:                 Test reorganisation; no new production behaviour
Equivalent baseline
  implementation:                 583a05b
Material differences:             The dirty file DELETES sharedCanonicalVectorsAreByteIdentical —
                                  the pinned SHA-256 of the shared canonical vectors, the
                                  strict-parse fixpoint assertions, the malformed-UTF-8 rejection,
                                  and the manifest-hash/signature production-path equality
                                  assertions — and also deletes
                                  preservesGeneratedMappingsBoundToAuthoredSource,
                                  rejectsMissingAndDuplicateGeneratedMappings and
                                  rejectsInvalidMappingReferencesAndMetadata
Disposition:                      Superseded
Reason:                           Carrying this file would REMOVE coverage the baseline has, not
                                  add it. The renamed/reworked tests do not preserve the same
                                  coverage
Verification:                     Test-name and assertion diff between the two revisions
Resulting commit or follow-up:    None — no carry
```

### 3. `SourceManifestContract.java`

```
File:                             src/main/java/com/chromascape/web/aiplayer/routine/source/SourceManifestContract.java
Source revision/worktree:         Main checkout working copy at 7ab2a80 + changes
Behavior introduced by the
  working change:                 (a) MAX_MANIFEST_BYTES 20_000_000 -> 5_000_000;
                                  (b) removes MAX_GENERATED_MAPPINGS;
                                  (c) routes canonicalHashBytes/canonicalSignatureBytes through
                                  the generic CanonicalJsonHasher instead of the dedicated
                                  SourceManifestCanonicalJson
Equivalent baseline
  implementation:                 583a05b, which keeps the 20 MB bound with its sizing rationale,
                                  keeps MAX_GENERATED_MAPPINGS, and keeps
                                  SourceManifestCanonicalJson for both canonical paths
Material differences:             (a) is a concrete REJECTION regression: the baseline's own
                                  comment records that a production Fishing closure manifest is
                                  ~15.3 MB, so a 5 MB bound rejects a valid producer document;
                                  (c) changes the bytes that are hashed and signed. The two
                                  canonicalisers are deliberately separate —
                                  SourceManifestCanonicalJson's javadoc states source identity
                                  "preserves string contents byte-for-byte, including line
                                  endings" while the generic hasher does not
Disposition:                      Superseded
Reason:                           Three independent regressions. The baseline bound and
                                  canonicaliser are both deliberate and load-bearing for SC-810
                                  signature verification
Verification:                     Constant and call-site comparison against both revisions;
                                  SourceManifestCanonicalJson present in 583a05b and used by
                                  TaskSourceManifestVerifier.hash()
Resulting commit or follow-up:    None — no carry
```

### 4. `SourceRunOrchestrationIntegrationTest.java`

```
File:                             src/test/java/com/chromascape/web/aiplayer/routine/source/SourceRunOrchestrationIntegrationTest.java
Source revision/worktree:         Main checkout working copy at 7ab2a80 + changes
Behavior introduced by the
  working change:                 Adds one new integration test,
                                  captureCompletionRollsBackSourceRunAndCaptureWritesBeforeRecordingUncertain,
                                  asserting that a phase-2 binding conflict rolls back the manifest
                                  insert and leaves the capture operation/record UNCERTAIN with the
                                  run still BOUND
Equivalent baseline
  implementation:                 CaptureSourceIntegrationTest.bindingConflictRollsBackManifestBindingAndCaptureWrite,
                                  plus runIdentityConflictRollsBackEveryPhaseTwoWrite,
                                  captureUpdateFailureRollsBackManifestBindingRunAndCapture and
                                  identityChangingBetweenFetchAndWriteFailsClosed, all built on the
                                  shared SC-810 fixture
Material differences:             Same behaviour and the same assertions (UNCERTAIN state and
                                  completion status, completed=false, manifest absent, conflicting
                                  binding hash preserved, stage BOUND). The dirty file also carries
                                  an older harness: ChromaScapeApplication + RANDOM_PORT instead of
                                  Sc810PostgresTestConfiguration, and no
                                  IsolatedPostgresTestDatabase registration
Disposition:                      Superseded
Reason:                           The baseline factored this exact coverage into a dedicated class
                                  with a proper fixture and isolated schema. Carrying the dirty copy
                                  would duplicate it under a weaker harness
Verification:                     Assertion-by-assertion comparison against the baseline test class
Resulting commit or follow-up:    None — no carry
```

### 5. `SourceFetchTransactionSuspensionTest.java`

```
File:                             src/test/java/com/chromascape/web/aiplayer/routine/source/SourceFetchTransactionSuspensionTest.java
Source revision/worktree:         Main checkout working copy at 7ab2a80 + changes
Behavior introduced by the
  working change:                 Same NOT_SUPPORTED transaction-suspension proof
Equivalent baseline
  implementation:                 583a05b
Material differences:             Harness and fidelity only. Baseline binds
                                  Sc810PostgresTestConfiguration with WebEnvironment.NONE,
                                  spring.flyway.target=21 and IsolatedPostgresTestDatabase, and
                                  builds CurrentSliceIdentity with a fully populated
                                  Source(repository_id, commit_id, tree_id, build_id,
                                  artifact_digest). The dirty version uses
                                  ChromaScapeApplication/RANDOM_PORT, no isolated schema, and an
                                  all-null Source — so it stops exercising the source-binding
                                  identity the suite exists to prove
Disposition:                      Superseded
Reason:                           The dirty version weakens the proof it carries
Verification:                     Constructor-argument and annotation comparison of both revisions
Resulting commit or follow-up:    None — no carry
```

### 6. `SourceManifestConcurrentPersistenceIntegrationTest.java`

```
File:                             src/test/java/com/chromascape/web/aiplayer/routine/source/SourceManifestConcurrentPersistenceIntegrationTest.java
Source revision/worktree:         Main checkout working copy at 7ab2a80 + changes
Behavior introduced by the
  working change:                 Same concurrency/immutability proof
Equivalent baseline
  implementation:                 583a05b
Material differences:             Older harness, no isolated schema, all-null Source identity; the
                                  dirty variant additionally seeds the conflicting manifest with
                                  verificationState/sourceProvenance = UNSIGNED where the baseline
                                  uses VERIFIED
Disposition:                      Superseded
Reason:                           The baseline's VERIFIED provenance matches the current
                                  verification model; the dirty UNSIGNED seeding is stale
Verification:                     Field-by-field comparison of the seeded conflict row and the
                                  harness annotations
Resulting commit or follow-up:    None — no carry
```

### 7. `CapabilityManifest.java`

```
File:                             src/main/java/com/chromascape/web/replay/capability/CapabilityManifest.java
Source revision/worktree:         Main checkout working copy at 7ab2a80 + changes
Behavior introduced by the
  working change:                 Makes viewerTraceSchema a record component; expands
                                  V1_SUPPORTED_ACTIONS to the 10-action vocabulary; adds
                                  SUPPORTED_SCENARIO_SCHEMA_VERSIONS=[1,2] and
                                  SUPPORTED_SINGLE_VERSION
Equivalent baseline
  implementation:                 583a05b carries all of the above AND retains the closed-vocabulary
                                  helpers isSupportedAction(String) and
                                  isValidSupportedActionSubset(List<String>)
Material differences:             The dirty variant removes both helpers. They are what stop an
                                  incomplete adapter claiming support merely because an action name
                                  is part of the wire contract
Disposition:                      Superseded
Reason:                           Baseline is the same feature plus stricter validation
Verification:                     Member diff; baseline commit 0693940
                                  ("feat-replay-harden-xrsps-playback-and-debugger") is the later
                                  hardening pass
Resulting commit or follow-up:    None — no carry
```

### 8. `XrspsStartupValidatorTest.java` · 9. `XrspsStartupValidatorCapabilityContractTest.java`

```
File:                             src/test/java/com/chromascape/web/replay/capability/XrspsStartupValidatorTest.java
                                  src/test/java/com/chromascape/web/replay/capability/XrspsStartupValidatorCapabilityContractTest.java
Source revision/worktree:         Main checkout working copy at 7ab2a80 + changes
Behavior introduced by the
  working change:                 Test-side rework of the capability contract
Equivalent baseline
  implementation:                 583a05b
Material differences:             The dirty contract test DELETES the duplicate-action and
                                  empty-action assertions (replacing them with a single
                                  "supportedActions must be exactly" expectation), drops
                                  viewerTraceSchema from the required-field list and from the valid
                                  document, and flips fidelity to SYNTHETIC_CONTRACT.
                                  XrspsStartupValidatorTest is reindented and otherwise expects the
                                  same behaviour
Disposition:                      Superseded
Reason:                           Carrying these would remove the duplicate/empty action rejection
                                  coverage that pairs with the helpers in item 7. The dirty fixtures
                                  also list action names (INVENTORY_ACTION, USE_ITEM_ON_NPC) that are
                                  not members of the v1 vocabulary, so they are internally
                                  inconsistent with the baseline contract
Verification:                     Assertion-level diff; action-name cross-check against
                                  CapabilityManifest.V1_SUPPORTED_ACTIONS
Resulting commit or follow-up:    None — no carry
```

### 10. `InMemoryAccountBuilderOrchestrationStore.java`

```
File:                             src/main/java/com/chromascape/web/gameplay/orchestration/accountbuilder/InMemoryAccountBuilderOrchestrationStore.java
Source revision/worktree:         Main checkout working copy at 7ab2a80 + changes
Behavior introduced by the
  working change:                 No behaviour change relative to the baseline
Equivalent baseline
  implementation:                 583a05b — identical logic (correlation map, redacted request
                                  snapshot, authority verifier hook)
Material differences:             Formatting only (line wrapping), plus one import
                                  (SnapshotBindings) present in the dirty copy and unused there
Disposition:                      Already included (equivalent implementation identified)
Reason:                           Whitespace-collapsed token streams diverge only by that import;
                                  every method body and literal matches
Verification:                     Whitespace-insensitive comparison; baseline commit 3fad0c4
Resulting commit or follow-up:    None — no carry
```

### 11. `IsolatedPostgresTestDatabase.java`

```
File:                             src/test/java/com/chromascape/testsupport/IsolatedPostgresTestDatabase.java
Source revision/worktree:         Main checkout working copy at 7ab2a80 + changes
Behavior introduced by the
  working change:                 Simplified helper: register(DynamicPropertyRegistry) only, head
                                  assertion "33", migration-16 rows expected 0
Equivalent baseline
  implementation:                 583a05b, which is a superset: register(registry, database),
                                  registerFlywayValidateOnly(registry), pooled-connection sizing,
                                  a schemaJdbcUrl() helper that handles an existing '?', head
                                  assertion "45", migration 16 expected exactly once, and
                                  assertFreshSchemaOwnsRecordingDebugContracts(...)
Disposition:                      Superseded
Reason:                           The dirty variant is a strict subset AND is stale: the baseline
                                  migration chain reaches V46 (V44/V45/V46 present), so an
                                  assertion of "33" is incompatible with the baseline's own schemas.
                                  Carrying it would delete the SC-810 fresh-schema ownership proof
Verification:                     Member-by-member comparison; git ls-tree 583a05b
                                  src/main/resources/db/migration shows V44/V45/V46
Resulting commit or follow-up:    None — no carry
```

### 12. `docs/SC-810-SOURCE-PROVENANCE.md` · 13. `docs/adr/SC-810-source-provenance-and-signing.md`

```
File:                             docs/SC-810-SOURCE-PROVENANCE.md
                                  docs/adr/SC-810-source-provenance-and-signing.md
Source revision/worktree:         Main checkout working copy at 7ab2a80 + changes
Behavior introduced by the
  working change:                 Documentation, but it asserts the opposite contract: legacy
                                  micro6-source-manifest/v1 documents "remain readable",
                                  signatures are verified "when present", and development defaults
                                  keep signature verification off while legacy compatibility stays on
Equivalent baseline
  implementation:                 583a05b, which records the greenfield decision: no embedded
                                  resource and no unsigned/legacy fallback; only a signed
                                  task-source-manifest/v1 bundle is authoritative
Material differences:             Directly contradictory statements about the trust boundary
Disposition:                      Superseded
Reason:                           The dirty prose describes the pre-greenfield behaviour that commit
                                  c39be02 replaced. Adopting it would document a fallback the code
                                  deliberately no longer has
Verification:                     Prose comparison against the baseline and against
                                  SourceManifestContract.supportedSchema /
                                  TaskSourceManifestVerifier, which accept only the v1 schema
Resulting commit or follow-up:    None — no carry
```

## Untracked-file inventory (completed)

### Relevant untracked documentation, accounted individually

| Path | Nature | Disposition |
|---|---|---|
| `docs/SC-810-GREENFIELD-IMPLEMENTATION-SPEC.md` | Design spec cited **only** by the superseded draft SC-810 documents | **Defer to Felix bookkeeping** — the candidate baseline's SC-810 documents do not link it, so nothing on this branch depends on it. Not copied by hand |
| `docs/builderflow/felix-notes/**` (32 files) | Felix's generated mirror of its note store | **Preserve via Felix synchronisation only** — generated bookkeeping, not authored source |
| `docs/builderflow/felix-sync/pending/**` (14 records) | Felix outbox records for other stories (SC-1112, SC-1429, SC-1449, SC-1173, SC-1618, SC-728, SC-1199) | **Preserve; not committed into SC-1661.** Each belongs to its own story's synchronisation flow |
| `docs/builderflow/felix-sync/synced/` | Synced counterparts | Same — out of scope for this branch |
| `.commandcode/settings.json`, `.commandcode/taste/**` | Agent/tool configuration | **Exclude — machine/tool configuration** |

### The two untracked consolidation-worktree files

```
$ git -C ChromaScape-consolidation status --porcelain -uall | grep '^??'
?? .micro6/gameplay-v2-reservations/gameplay-v2-reservations.lock
?? .micro6/gameplay-v2-reservations/gameplay-v2-reservations.snapshot.jsonl
```

Both are **generated runtime state** of the gameplay-v2 reservation store (a lock file and an
append-only snapshot journal), not source. Disposition: **exclude — generated runtime state**.
This also confirms the earlier "1 untracked entry (2 files)" count.

### The 16 untracked `src/` files — equivalence claim re-verified

The earlier draft asserted all 16 "already exist in the baseline" and called them "5
byte-identical, 11 formatting or older variants". Existence is confirmed, but the second half of
that claim was **not tested** and is only partly true.

| Class | Count | Files |
|---|---|---|
| Byte-identical to baseline | **5** | `AccountBuilderAuthorityVerifier`, `InteractInventoryItemAction`, `InteractWidgetAction`, `SelectDialogueOptionAction`, `AccountBuilderPostgresTestFixtures` |
| Same tokens, different whitespace only | **2** | `CastSpellAction`, `EquipItemAction` |
| **Substantively different** | **9** | `SourceManifestSchemaValidator`, `ReplayNpcPinValidator`, `InteractGroundItemAction`, `V32__sc1431_recovery_correlation.sql`, `V33__replay_snapshot_state_sections.sql`, `AccountBuilderOrchestrationPostgresConcurrencyTest`, `AccountBuilderOrchestrationRestartTest`, `AccountBuilderOrchestrationRollbackTest`, `ScenarioManifestV2CompilerTest` |

Per-file dispositions for the 9:

| File | Difference | Disposition |
|---|---|---|
| `SourceManifestSchemaValidator.java` | Javadoc spacing and line wrapping only at the token level | **Superseded** — baseline wording |
| `ReplayNpcPinValidator.java` | Formatting plus two imports (`HashSet`, `Set`) present in the dirty copy and unused there | **Superseded** — dead imports in the dirty copy |
| `InteractGroundItemAction.java` | Record-header line wrap only | **Already included** |
| `V32__sc1431_recovery_correlation.sql` | `DROP CONSTRAINT ab_binding_digest_check` vs baseline `DROP CONSTRAINT IF EXISTS ab_binding_digest_check` | **Superseded** — the dirty form is not idempotent and fails on a re-run |
| `V33__replay_snapshot_state_sections.sql` | **Real semantic difference in the CHECK grouping** — see below | **Explicitly deferred** (follow-up required) |
| `AccountBuilderOrchestrationPostgresConcurrencyTest.java` | `@SpringBootConfiguration TestApplication` + `@ActiveProfiles("test")` vs baseline's `TestBootstrap` / `sc1431-postgres-concurrency` profile gate and `spring.flyway.target=32` | **Superseded** — the baseline deliberately gates this test behind a profile |
| `AccountBuilderOrchestrationRestartTest.java` | No `flyway.target(32)`, head asserted "33", no `sc1431-jpa` profile, extra authority-secret argument | **Superseded** — baseline is the reworked, profile-gated variant |
| `AccountBuilderOrchestrationRollbackTest.java` | `JpaTestApplication` + `@ActiveProfiles("test")` vs baseline `JpaTestBootstrap` + `{"test","sc1431-jpa"}`, no flyway target | **Superseded** — same reason |
| `ScenarioManifestV2CompilerTest.java` | Worktree's `initialSnapshot()` omits `playerState.put("collisionRevision", "collision-v7")` that the baseline sets; remainder is formatting | **Superseded** — baseline fixture is the fuller one |

#### `V33` — a genuine, bounded finding

The two revisions group the CHECK predicate differently:

```sql
-- candidate baseline 583a05b  (weaker)
dialogue IS NULL OR octet_length(dialogue::text) <= 262144
AND (spells  IS NULL OR octet_length(spells::text)  <= 262144)
AND (vars    IS NULL OR octet_length(vars::text)    <= 262144)

-- dirty working copy  (stronger)
(dialogue IS NULL OR octet_length(dialogue::text) <= 262144)
AND (spells IS NULL OR octet_length(spells::text) <= 262144)
AND (vars   IS NULL OR octet_length(vars::text)   <= 262144)
```

`AND` binds tighter than `OR`, so the baseline predicate is `dialogue IS NULL OR (…AND…AND…)`.
When `dialogue IS NULL` the baseline accepts **any** size for `spells` and `vars`; the dirty form
bounds all three independently. The dirty grouping matches the migration's stated intent of
bounding each JSON section, so the baseline's constraint is weaker than intended.

```
File:                             src/main/resources/db/migration/V33__replay_snapshot_state_sections.sql
Source revision/worktree:         Main checkout working copy at 7ab2a80 + changes (untracked)
Behavior introduced by the
  working change:                 Bounds tick_snapshots dialogue/spells/vars independently
Equivalent baseline
  implementation:                 583a05b V33, same file and version, weaker grouping
Material differences:             Baseline predicate is true whenever dialogue IS NULL, regardless
                                  of spells/vars size
Disposition:                      Explicitly deferred — not carried by SC-1661
Reason:                           V33 is an already-committed, Flyway-checksummed migration in the
                                  candidate baseline. Editing it in place would invalidate applied
                                  checksums; the correct remedy is a new forward migration.
                                  Strengthening a JSON size bound is a data-integrity hardening, not
                                  a prerequisite for the SC-1662+ Linux/RemoteInput work, so it does
                                  not gate this baseline
Verification:                     Operator-precedence analysis of both predicates; git ls-tree
                                  583a05b src/main/resources/db/migration confirms V33 is committed;
                                  the file is untracked in the main checkout
Resulting commit or follow-up:    FOLLOW-UP REQUIRED — owner: the SC-1662–SC-1668 integration
                                  stream (dedicated schema follow-up, accountable there).
                                  Deliverable: a forward migration re-creating
                                  ck_tick_snapshots_replay_state_json_sizes with the independent
                                  grouping, plus a migration-completeness re-proof. Also recorded as
                                  a residual baseline risk in integration-base.md
```

## Outstanding custom commits — both resolved

### Fedora remote Builder Goal launcher routing — `97284f1` / `ced7487` → **carried/adapted** (`966e0e3`)

Both commits carry the subject "Add remote Fedora Builder Goal launcher routing", touch the same
14 files, and report `630 insertions(+), 5 deletions(-)`, but their patch-ids differ
(`8c733b0c…` vs `176ff28c…`). The differences are hunk context, an `.env.example` neighbourhood
inherited from different parents, and **one real expression** in `Micro6OnlineClientRegistryService`:

```java
public boolean belongsToMachine(TargetClient target, String machineUuid) {
  ClientState state = clients.get(target.clientId());
  return state != null && machineUuid.equals(state.machineUuid)
      && java.util.Objects.equals(target.launchAttemptId(), state.launchAttemptId());  // 97284f1
//    && java.util.Objects.equals(target.launchAttemptId(), state.launchAttemptId);    // ced7487
}
```

**Resolved on the actual types, not on publication status.**

- `TargetClient` is a `record` (`Micro6OnlineClientRegistryService.java:542`) — `target.launchAttemptId()` is correct.
- `ClientState` is a plain `private static class` (`:764`) whose `launchAttemptId` is a **field**
  (`private String launchAttemptId;`, `:786`), assigned as `state.launchAttemptId = …` (`:70`) and
  read as a field elsewhere in the same file (`:248`, `:370`, `:461`).

The published `97284f1` therefore does not compile. Demonstrated directly, not inferred:

```
$ ./gradlew compileJava        # with state.launchAttemptId() as published
.../Micro6OnlineClientRegistryService.java:82: error: cannot find symbol
  symbol:   method launchAttemptId()
1 error
```

`ced7487`'s field access is correct. This is exactly the case the plan anticipated: the published
branch is not the authority.

```
Change:                           Fedora remote Builder Goal launcher routing
Source revision/worktree:         97284f1 (parent 7ab2a80) and ced7487 (parent e5cdf81)
Behavior introduced by the
  working change:                 A pinned remote machine that advertises builder-goal-launch-v2
                                  becomes runnable; machine listing includes the remote agent;
                                  Micro6TargetPreparationService delegates to the remote launcher
                                  for non-local machines; dispatch-ready clients are filtered by
                                  machine; launch metadata records remote_micro6_agent,
                                  launcher_endpoint, agent_version, agent_capabilities
Equivalent baseline
  implementation:                 None. The baseline is local-only: isBuilderGoalRunnableMachine
                                  ended in `isLocalMachine(machine)`, and there is no
                                  Micro6RemoteLauncherService, controller or FEDORA_REMOTE_MICRO6.md
Material differences:             Entire feature absent from the baseline
Disposition:                      Carry/adapt into SC-1661 — DONE
Reason:                           The behaviour is genuinely missing and is consumed by SC-1667
                                  (Fedora startup and attachment diagnostics). It could not be a
                                  blind cherry-pick: the patch parent is 7ab2a80 while the baseline
                                  has since reworked the same four services, .env.example was
                                  rewritten for SC-810, and 97284f1 does not compile
Verification:                     ./gradlew compileJava compileTestJava exit 0; focused launcher
                                  suites 175 tests, 0 failures, 0 skipped
                                  (BuilderGoalLauncherControlServiceTest, BuilderGoalRunnerServiceTest,
                                  Micro6OnlineClientRegistryServiceTest,
                                  Micro6TargetPreparationServiceTest, Micro6RemoteLauncherServiceTest,
                                  Micro6ExecutorReadinessEvaluatorTest); spotlessCheck unchanged at
                                  the baseline's 16 pre-existing violations
Resulting commit or follow-up:    966e0e3 — "feat(sc-1661): adapt Fedora remote Builder Goal
                                  launcher routing" (source SHAs 97284f1 and ced7487 cited in the
                                  commit message)
```

Adaptation rules applied: the baseline's newer credential handling, session identity and readiness
behaviour are preserved; 13 of the 14 files applied with offsets and no conflicts; `.env.example`
re-applied by hand; `state.launchAttemptId` field access used.

### Replay fixture attributes — `d5b2d82` → **carried** (`45f943f`)

```
Change:                           d5b2d82 "Normalize replay fixture attributes"
Source revision/worktree:         integration/sc1199-fedora-durable-20260909 (parent ced7487)
Behavior introduced by the
  working change:                 Adds two lines to .gitattributes:
                                    /src/test/resources/tick-batch-*.json text eol=lf
                                    /src/test/resources/recording-action-checksum-vectors.json text eol=lf
Equivalent baseline
  implementation:                 None — the candidate baseline's .gitattributes omits both lines
Material differences:             The named fixtures DO exist in the baseline
                                  (tick-batch-canonical-vectors.json, tick-batch-fishing-golden.json,
                                  tick-batch-fixture-manifest.json, tick-batch-v2-golden.json,
                                  recording-action-checksum-vectors.json) but have no eol attribute
Disposition:                      Carry — DONE
Reason:                           Two-line, path-scoped addition covering files that exist in the
                                  baseline. These are hash/checksum fixtures, so pinning LF is the
                                  correct semantics
Verification:                     git check-attr text eol returns text=set, eol=lf for exactly the
                                  five intended paths and `unspecified` for controls
                                  (src/main/resources/application.yml). All five blobs are already
                                  stored LF, so no blob changes and no repository-wide rewrite occurs;
                                  git diff --stat -- src/test/resources/ is empty
Resulting commit or follow-up:    45f943f — "chore(sc-1661): carry replay fixture eol attributes
                                  from d5b2d82"
```

## Already-included and out-of-scope items

| Change | Disposition | Reason |
|---|---|---|
| Main checkout `7ab2a80` | Already included | 0 commits behind the candidate baseline |
| Published consolidated `471262b` | Already included | 0 commits behind |
| SC-1199 durable tick-batch lineage `16a3d72` | Already included | Merge base `e5cdf81`; lineage is an ancestor |
| `origin/main` (`eb18d2d`), `origin/main2` (`8189e4d`) | Already included | 0 target-only commits |
| CRLF-only churn (main 424, consolidation 1,419, durable-current 1,414) | Drop | `git diff --ignore-cr-at-eol` empty |
| `.gradle-home/` (1,734 files) | Exclude | Generated Gradle user-home cache |
| `.commandcode/**` | Exclude | Machine/tool configuration |
| `.micro6/` in consolidation | Exclude | Generated runtime state (lock + journal) |

## Secrets check

Untracked `src/` and `docs/` content plus the substantive modified files were scanned for
credential patterns.

- **No credentials found.** The only strong-pattern hit is
  `PatchCandidateWorkflowServiceTest.java:268`, `String pem = "-----BEGIN PRIVATE KEY-----\n"` — a
  synthetic PEM header exercising signature verification, with no key material.
- All other matches are prose in design documents.
- `.env.local` exists in the main checkout (395 bytes, machine-specific). It is **not** in the
  changed set and must never be copied into the story branch or evidence.
- The carried `.env.example` block adds only **blank** credential placeholders plus a documented
  example endpoint; no secret values.

## Method note

Every figure above was recomputed in this story; none were inherited. `git cherry` disproves the
assumption that `97284f1` and `ced7487` are the same change; the `--ignore-cr-at-eol` decomposition
disproves the assumption that the porcelain counts represent substantive work; the
whitespace-collapsing pass disproves the assumption that all 16 untracked files are
formatting-or-equivalent variants; and a compile experiment disproves the assumption that the
published Fedora revision is the correct one. The identifier-set test that an earlier draft treated
as a conclusion is retained only as a *locator* of the files that needed real review.
