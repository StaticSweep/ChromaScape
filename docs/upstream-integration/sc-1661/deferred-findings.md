# SC-1661 — Deferred Findings and Accountable Follow-ups

SC-1661 does not fix the two findings below. Each is recorded here with scope, evidence, an intended
tracker record, and its delivery impact, so that "deferred" means *owned and traceable*, not
*handed to a stream*.

**Tracker linkage status: outstanding.** No Felix or Shortcut MCP transport was available in the
session that produced this file, so **no ticket was created and none is claimed**. Each record below
carries the exact intended tracker operation. Until that ticket exists, the durable reference for
each finding is this document plus the named commit/artifact, and the tracker link is explicitly
missing rather than assumed.

Reference revisions: candidate baseline `583a05b91687d156b1baa52dbda2b219604c7b39`; reconciled code
baseline `966e0e3e47db71fe5d57629205efcb23cd11e026`; intended PR target
`integration/chromascape-consolidated-20260901`.

---

## SC-1661-D1 — `ck_tick_snapshots_replay_state_json_sizes` is logically weaker than intended

| Field | Value |
|---|---|
| Identifier | **SC-1661-D1** |
| Kind | Data-integrity / schema defect (pre-existing in the inherited baseline) |
| Affected constraint | `ck_tick_snapshots_replay_state_json_sizes` on `tick_snapshots` |
| Affected object | `src/main/resources/db/migration/V33__replay_snapshot_state_sections.sql` |
| Introduced by | The candidate baseline `583a05b`; not by SC-1661 |
| Fix state | **Not fixed** — explicitly deferred |

### The logical weakness

The baseline groups the predicate so that `AND` binds more tightly than `OR`, making the predicate
true whenever `dialogue IS NULL`, regardless of the size of `spells` and `vars`:

```sql
-- candidate baseline 583a05b  (weaker)
dialogue IS NULL OR octet_length(dialogue::text) <= 262144
AND (spells IS NULL OR octet_length(spells::text) <= 262144)
AND (vars   IS NULL OR octet_length(vars::text)   <= 262144)
```

Effective predicate: `dialogue IS NULL OR (dialogue <= 262144 AND spells <= 262144 AND vars <= 262144)`.
When `dialogue IS NULL`, the `spells` and `vars` bounds are **not enforced at all** — a row with a
multi-gigabyte `spells` or `vars` document passes. The migration's stated intent is to bound each
JSON section independently, so the baseline constraint is weaker than its own intent.

The untracked dirty variant (main-checkout working copy) groups all three independently and **must
not** be adopted in place:

```sql
-- dirty working copy  (stronger, intended)
(dialogue IS NULL OR octet_length(dialogue::text) <= 262144)
AND (spells IS NULL OR octet_length(spells::text) <= 262144)
AND (vars   IS NULL OR octet_length(vars::text)   <= 262144)
```

### Expected bounds

`dialogue`, `spells` and `vars` each bounded independently at `octet_length(...) <= 262144` when not
`NULL`.

### Required remedy — forward migration only

`V33` is already committed and Flyway-checksummed in the candidate baseline. Editing it in place
would invalidate applied checksums, so `V33` **must not be rewritten**. The remedy is a new forward
migration that drops and re-creates `ck_tick_snapshots_replay_state_json_sizes` with the independent
grouping, followed by a migration-completeness re-proof (the chain must still reach the baseline's
head and `Sc967FlywaySchemaTest` / the migration-completeness suite must pass).

### Database validation cases the owner must supply

1. `dialogue IS NULL` with `octet_length(spells::text) > 262144` → **rejected**.
2. `dialogue IS NULL` with `octet_length(vars::text) > 262144` → **rejected**.
3. `spells IS NULL` with `octet_length(dialogue::text) > 262144` → **rejected**.
4. All three `NULL` → **accepted**.
5. All three at exactly `262144` bytes → **accepted** (boundary, inclusive).
6. All three at `262145` bytes → **rejected** (boundary).
7. The forward migration applies on a database already at the baseline head, and the constraint is
   present with the independent grouping afterwards.

None of these can run on the SC-1661 host: no Docker and no PostgreSQL.

### Accountability and delivery impact

- **Owning story:** *not yet established* — no story in SC-1662–SC-1668 owns database migrations.
  The nearest by existing scope is **SC-1668** (host qualification and integration PR), which owns
  the integration PR and the migration-completeness re-proof, but a **dedicated schema-hardening
  follow-up ticket** is required and must be linked from SC-1661 and epic SC-1660.
- **Individual assignment:** not established; do not infer one.
- **Intended tracker operation:** create a defect linked to `shortcut:sc-1661` with the constraint
  name, the two predicates, the seven validation cases above, the forward-migration requirement, and
  this document as the evidence reference.
- **Does it block this baseline handoff?** **No.** It is a data-integrity hardening of an
  already-shipped constraint; it does not change behaviour that the SC-1662–SC-1668 Linux/RemoteInput
  work depends on. It is recorded as a residual baseline risk (see `integration-base.md`).

---

## SC-1661-D2 — 18 pre-existing non-environmental test failures in the inherited baseline

| Field | Value |
|---|---|
| Identifier | **SC-1661-D2** |
| Kind | Pre-existing test failures (classification incomplete) |
| Reproduction | `candidate-test.log` (C6) and `reconciled-test.log` (R4) — **identical 110-identity failing set on both** |
| Fix state | **Not fixed** — unrelated to SC-1661 |

### Exact failing identities

The 18 non-environmental failures are listed in full in
`evidence/non-environmental-failure-identities.txt`. They decompose as:

- **Gameplay / provider-output family (16)** — `GameplayContractConformanceTest` (4),
  `ProviderOutputNormalizerTest` (5), `BootstrapGameplayOrchestratorTest` (3),
  `BootstrapGameplayOperatorServiceTest` (2), `GameplayProviderRuntimeTest` (1),
  `HumanGameplayAuthorityServiceTest` (1).
- **Other assertion/behaviour failures outside that family (2)** —
  `SourceContractSchemaEqualityTest > canonicalSchemaIsByteIdenticalAcrossRepositories()` and
  `Micro6TargetRelaunchServiceTest > integrated_interruptedHealthRequestTerminatesOwnedTreeAndPreservesInterruptStatus()`.

Representative root causes: `GameplayContractValidator$GameplayContractViolation`
(`$: null found, object expected`), `org.opentest4j.AssertionFailedError` on
`canonicalResourcesHaveFrozenByteDigests` (expected digest `c55ad78a…`, observed `ff9ad17f…`),
`ProviderRuntimeException`, and `ClassCastException`.

**Correction to earlier wording.** Earlier documents described all 18 as "gameplay contract /
provider-output assertions". Only 16 belong to that family; 2 do not. The count 18 is reproducible;
the label was imprecise and is corrected here.

### Reproduction evidence

| Revision | Command | Artifact |
|---|---|---|
| `4e0a3d5…` (content `583a05b`) | `./gradlew test -Psc967VectorsPath=… --console=plain` | `evidence/test-failures-candidate.txt` (110 identities) |
| `966e0e3…` | same | `evidence/test-failures-reconciled.txt` (110 identities) |

The two identity sets are byte-identical (`diff` empty), so the 18 occur with **no SC-1661 change
present**. They are therefore not introduced by this story.

### Environment and configuration

Assessed on Linux 6.19.10-300.fc44.x86_64, Corretto 17.0.20.1, Gradle 8.14.2, **without Docker and
without a local PostgreSQL** (the other 92 failures are environmental for that reason). The 18 listed
here fail with assertion/behaviour causes, so the missing infrastructure is not their proximate
cause — but this host also cannot run their database-backed counterparts, which limits how far the
root cause can be confirmed.

### Root cause

**Not confirmed.** Two competing explanations remain open, and the evidence available here does not
separate them:

1. genuine pre-existing defects in the inherited gameplay/provider code and fixtures, or
2. environment-sensitive failures (locale, provider tool availability, or fixture drift) that would
   pass on a fully provisioned host.

**These failures must not be described as definitively a code defect until the owning story
reproduces them on a host with the required infrastructure.**

### Accountability and delivery impact

- **Owning story:** *not yet established* — no SC-1662–SC-1668 story owns the gameplay/provider area.
  A **dedicated defect record** is required and must be linked from SC-1661 and epic SC-1660.
- **Individual assignment:** not established.
- **Intended tracker operation:** create a defect linked to `shortcut:sc-1661` listing the 18
  identities, both reproduction logs, the environment, and the open classification question.
- **Delivery impact on SC-1661:** **none.** They are pre-existing, unchanged by the carries, and
  outside the story's scope. They are recorded as residual baseline risk so the integration baseline
  is not presented as greener than it is. Fixing them is explicitly **not** part of SC-1661 and does
  not gate its completion.
