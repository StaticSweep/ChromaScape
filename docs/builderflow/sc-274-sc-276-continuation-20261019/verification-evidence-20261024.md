# Verification evidence — SC-274/SC-276 achievable slice

Date: 24 October 2026. Verified commit `07725f0c` on
`codex/sc274-completion-20261015` (local, not pushed). Native recovery commit
`79382be38272da3d36f627c9932384692dd27873` (local branch, not pushed).

## Acceptance results

No original acceptance row is closed. Statuses below record what changed; all rows stay open.

| Row | Status | Evidence / blocker |
|---|---|---|
| 274-1 | Open | Durable publication contract implemented and unit-tested; genuine immutable inputs still absent |
| 274-2 | Open | Provenance not re-established for genuine inputs |
| 274-3 | Open | Source-linked refusal covered only by unit tests |
| 274-4 | Open | 3/17 integration cases pass; native Fishing execution blocked by worker qualification gate |
| 274-5 | Open | Incomplete/legacy refusal not exercised on genuine data |
| 274-6 | Open | Privacy and dataset role unchanged |
| 274-7 | Open | Import/export not exercised on final inputs |
| 274-travel | Open | Travel/jewelry cases remain client-gated |
| 274-historical | Open | Historical NOT_READY verdict retained unchanged |
| 276-boundary | Open | Production xRSPS boundary not re-qualified |
| 276-methods | Open | Inventory reconciled; Fishing method and `CAST_SPELL` contract gaps documented |
| 276-closure | Open | Windows worker cannot run Linux-targeted closure; closure CLI timeout untouched |
| 276-state | Open | Durable restart/continuation blocked by the same qualification gate |
| 276-safety | Open | Requirements documented; no OS-level enforcement implemented |
| 276-live | Open | No SC-800 allocation or authenticated lifecycle |
| 276-client | Open | No real client or human Watch |
| 276-certification | Open | WP6-WP9 inputs absent |

## Tests

| Check | Result |
|---|---|
| `artifactCompletionTest` (isolated checkout, JDK 17, offline) | 67 tests, 0 failures, 0 errors, 4 skipped (POSIX-only) |
| `replayTest --tests XrspsSessionRepositoryIntegrationTest` | 3 tests, 0 failures, disposable PostgreSQL |
| Windows STRICT admission refusal | PASS — no objects, refs or marker created |
| Windows development marker | PASS — `qualified:false`, publication works |
| POSIX STRICT positive suite | NOT RUN on Windows; skipped by design |
| `spotlessJavaCheck` (repository-wide) | FAILS on 1364 unrelated files (pre-existing) |
| Touched-file formatting | PASS under `--ignore-cr-at-eol` |
| Worker capability preflight | FAILS — `certifiable` hard-coded false |
| Post-commit clean-checkout build | PASS at `07725f0c` |

Artifacts: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261024\`
(`wp1-freeze-*`, `wp2-native-*`, `wp3-verify-*`, `wp4-db-*`, `wp4b-verify-*`,
`wp5-preflight-*`, `wp3-method-inventory-*`, `wp5-budget-requirements-*`).

## Risks

- **STRICT durability is only half-verified.** Windows proves refusal; the positive
  force-every-path contract is skipped there and has not yet run on Linux.
- **Mid-publication response is not transactional.** A directory-force failure after a
  move can leave the activation pointer replaced but not proven durable; the error is
  surfaced and retried idempotently rather than rolled back. Unreferenced blobs are
  retained by design and there is no GC yet.
- **Development roots are isolated by marker only.** A caller who deletes
  `durability.json` can make a development root look like a new strict root and trigger
  bootstrap. Strict bootstrap is safe only because it re-probes directory forcing; on
  POSIX that succeeds. This is documented but not tamper-evident.
- **Native source is local.** The recovered 105-file commit is not published; another
  host cannot reproduce the worker from the owning repository yet.
- **Windows dependency set is Linux-targeted**, so the worker lane cannot be exercised
  here at all.

## Rollback

- ChromaScape: revert `748f2d93` and `07725f0c` as follow-up commits; the plan commit
  `45700523` is documentation-only. No force-push.
- Native: keep `codex/sc274-native-recovered-20261024` and the verified bundle. Rollback
  is a branch checkout or a clone from the bundle; do not delete either anchor.
- No shared container, database, host service, clock setting or SC-666 Gradle hunk was
  modified. Disposable PostgreSQL containers were labelled, inspected and removed after
  use.
