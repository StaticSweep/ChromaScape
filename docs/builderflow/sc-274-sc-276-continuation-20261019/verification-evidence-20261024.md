# Verification evidence — SC-274/SC-276 achievable slice

Date: 24 October 2026. Verified commit `e44d6506` on
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
| `artifactCompletionTest` (isolated checkout, JDK 17, offline) | 68 tests, 0 failures, 0 errors, 4 skipped (POSIX-only) |
| `replayTest --tests XrspsSessionRepositoryIntegrationTest` | 3 tests, 0 failures, disposable PostgreSQL |
| Windows STRICT admission refusal | PASS — no objects, refs or marker created |
| Windows development marker | PASS — `qualified:false`, publication works |
| Strict force-call coverage with an injected primitive | PASS — publication, activation, quarantine, reset and temp cleanup all invoked the force hook |
| POSIX STRICT positive suite | NOT RUN on Windows; skipped by design |
| `spotlessJavaCheck` (repository-wide) | FAILS on 1364 unrelated files (pre-existing) |
| Touched-file formatting | PASS under `--ignore-cr-at-eol` |
| Worker capability preflight | FAILS — `certifiable` hard-coded false |
| Post-commit clean-checkout build | PASS at `e44d6506` |

Artifacts: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261024\`
(`wp1-freeze-*`, `wp2-native-*`, `wp3-verify-*`, `wp4-db-*`, `wp4b-verify-*`,
`wp5-preflight-*`, `wp3-method-inventory-*`, `wp5-budget-requirements-*`).

## Risks

Subsequent source/admission review reproduced additional blockers, superseding blanket
claims of complete mode isolation and force coverage below:
[durability-review-findings-20261024.md](durability-review-findings-20261024.md).
The three findings were subsequently fixed (see the Resolution section of that file): admission
is serialized and revalidated, missing/null/inconsistent markers are refused, and every changed
parent is forced in order. Linux STRICT positives (33 executed, 0 failed) and the two-JVM
admission race passed on a container filesystem; XrspsSessionRepositoryIntegrationTest passed
3/3 on a disposable PostgreSQL. Remaining WP1 work is listed in that file; WP1 sign-off is still open.

- **Strict is now the default and fails closed at startup on Windows.** A Windows deployment
  must set `ARTIFACTS_DURABILITY_MODE=development` (or run the durable lane on POSIX); its
  root is then marked unqualified and can never be served by a strict store. This is a
  deliberate behavior change, not an outage workaround.
- **STRICT durability is verified for policy and call order, not power loss.** Windows proves
  refusal; the Linux STRICT positives ran against the real directory-force primitive on a container
  filesystem. No power-loss fault test has been run (needs an owned disposable VM).
- **Mid-publication response is not transactional.** A directory-force failure after a
  move can leave the activation pointer replaced but not proven durable; the error is
  surfaced and retried idempotently rather than rolled back. Unreferenced blobs are
  retained by design and there is no GC yet.
- **Marker tampering now fails closed.** A deleted, null or inconsistent `durability.json` on a
  populated root is refused; legacy roots can only be adopted as unqualified DEVELOPMENT roots.
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
