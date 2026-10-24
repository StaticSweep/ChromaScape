# SC-274 / SC-276: verification and full remaining work

Date: 23 October 2026. **Not complete; no acceptance row closed.**

Fresh selected Java verification: **547 unique tests, 515 passed, 32 failed, zero skipped**
after a valid actual-pin native rerun replaced two initial skips. This is full accounting
of remaining gates plus scoped execution of available checks, not production certification.

Evidence root:
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261023\full-verification-20261023T200849Z`.

## Changed files and review scope

Created verification artifacts and an isolated compiler build snapshot; added this report
and a current-results pointer in the previous remaining plan. No product/test implementation,
shared configuration, credentials, trust, migrations, game session or tracker state changed.
No service restart, grant issue, capture or replay request was performed. Read-only requests
may create ordinary service audit/access logs. Felix/Shortcut remain explicitly skipped.

Compiler inputs are HEAD c791c6d47102bf5971497ab5ece5f48883b79b82 plus current tracked
changes and untracked src files, hashed in snapshot.json (1,677 files, 66 untracked source
files). Checkstyle configuration is hashed separately. Running 8090/8181 build outputs were
not rebuilt. Candidate comparison found 42 Git-archive line-ending-only differences and
zero content differences; those paths are not claimed raw-byte-exact working-tree copies.
Native inputs remain the dirty recovered candidate with recorded external dependencies.

Source review covered preflight/loader semantics and regression changes, worker constructor/
claim entry, fresh-JVM harness, provenance fixture wiring, native guard/closure tests and
handoff boundaries. It does not claim exhaustive line review of every pre-existing dirty
multi-repository file. Details are in evidence code-review-findings.md.

## Tests actually run

| Check | Fresh result | Scope/evidence |
|---|---|---|
| Broader compiler/manifest/native-contract/ownership/curation/aiplayer/source/lifecycle selection | 531: 509 pass, 20 fail, 2 native skips | compiler-results.json and compiler-junit/ |
| Actual-pin native bank/travel/Fishing and source schedule | 2/2 pass | native-qualified-junit/; 99.2/95.0 seconds harness time; authored/non-certifying |
| Provenance refusal | 3/3 pass | storage-provenance-junit/ |
| ImmutableArtifactStore | 13: 1 pass, 12 fail | storage-provenance-results.json |
| Java union, valid final cases | 547: 515 pass, 32 fail, zero skips | java-verification-union.json; not every repository test |
| Python renewal/evidence and review regressions | 14/14 pass | python-regressions.log |
| Native guards, default dependency installation | 22/24 pass | Linux-only installed esbuild; failed attempt retained |
| Native guards with documented Windows esbuild | 23/24 pass | Closure CLI child timed out at 45-second bound |
| Targeted closure CLI rerun | 1/1 pass | Earlier timeout retained; load/reproducibility remains open |
| Worker stdio handshake/refusal/exit | Passed | First handshake about 2.23 seconds; not full scenario budgets/throughput |
| Checkstyle main/test | Completed, 4,730 / 1,048 warnings | style-results.json; no mass formatting applied |
| Spotless Java check | Failed | Displayed sample plus 1,332 other files; raw log retained |

The first handoff attempt copied mutable build outputs containing golden fixture pins.
Tests rejected those inputs. That verification setup error and its XML are archived
separately, excluded from the valid Java union and not classified as a native regression.
The corrected run used archived actual-pin artifacts checked against their recorded hashes.

## Current red test paths

| Suite | Failures | Required work |
|---|---:|---|
| FishingActiveModelServiceTest | 16 | ARTIFACT_NOT_FOUND and restore/error-code mismatches: establish intended registered-artifact contract and fix fixture lineage and any real compatibility/error-mapping defect |
| FishingDatasetBuilderServiceTest | 3 | Inject valid run-context provenance into positive/validation fixtures; preserve explicit missing-provenance refusal |
| FishingArtifactRegistryIntegrationTest | 1 | Windows AccessDenied causes ARTIFACT_STORAGE_UNAVAILABLE |
| ImmutableArtifactStoreTest | 12 | Publication/storage failures and dependent assertions; inspect individual retained traces |

The concrete storage trace reaches ImmutableArtifactStore.publish line 390:
FileChannel.open on the parent directory for a durability force after hard-link creation.
Implement supported durable publication on the target platform or explicitly qualify a
supported deployment/platform contract. Do not remove durability checks merely to pass.
These are current failures; their origin is not proven just by calling them pre-existing.

## Remaining code-review findings

1. Fresh-JVM finally invokes first cleanup then second. If the first throws without a
   primary test failure, the second is skipped. Attempt both and aggregate errors; test
   failed-first-cleanup and second-spawn failure.
2. Cleanup restores the interrupt flag before waiting for forced termination. A live
   process wait can immediately interrupt again. Deliberately preserve/clear/restore the
   flag around bounded cleanup and test interruption/timeout paths.
3. Current successful curation assertions are present, but fixture repositories are mocked;
   compiler determinism is not real database persistence or genuine-input qualification.

The corrected preflight message describes final-minus-initial inventory total, not fish
catches. Its exactly-five implementation gate remains unchanged, and historical input
correctly remains NOT_READY. No capture data was changed.

## Fresh read-only service and prerequisite checks

| Surface | Observation | Remaining limit |
|---|---|---|
| 8090 / 8181 debugger | Both HTTP 200 | Health is not acceptance |
| Owned 8182 registry | Healthy; auth enforced; authorized read and 8181 discovery succeed with one stale/unselectable executor | Supported allocation, valid heartbeat/grant and actual catalog selection |
| 8180 source runtime | Connection refused; no observed listener | Ownership-verified isolated source runtime needed for renewed live integration; no automatic login |
| Historical preflight | Both HTTP 200/NOT_READY with corrected text; b83c net delta 3 | Genuine eligible input; historical accounting audit is already reconciled |
| Replay state | Nine terminal attempts, 5 failed/4 succeeded, zero active leases; two snapshots identical | No consumption observed in that window, not absolute future protection |
| Clock | Measured worst skew 1.662 seconds; five-second preflight passes; Windows Time stopped | Stable synchronization, owner and operational renewal qualification; source 8180 clock unavailable |
| Trust | Canonical sc810-dev-ed25519-611ab9dd95a518af and development micro6-sc810-local-ed25519 keys differ | Original-trust-approved exact-artifact bundle or explicit amendment |
| Packaging | Owned compiler mirror absent; node_modules/cache/collision junctions and external Windows esbuild | Portable reviewed source/dependency acquisition and self-contained package |

The earlier 48-second skew is historical, not the current observation. No host time/service
change was made by this verification, and current alignment does not prove a durable remedy.
The recorded narrow secret scan found no matches for its three selected current protected-store
values across 1,614 text files; it does not establish confidentiality of every historical
credential or excluded build artifact.

## Full remaining implementation sequence

1. **Clear the red artifact/model/provenance paths.** Establish supported durable publication;
   fix registered-model fixture lineage and any actual error-contract defect; supply valid
   provenance fixtures without weakening the gate. Rerun the four red suites, storage integration
   and affected callers. Keep all current failures as baseline evidence.
2. **Finish harness failure cleanup.** Always attempt both children, preserve interruption
   correctly and test timeout, failed-first-cleanup, second-spawn failure and interruption.
   Retain successful fresh-JVM manifest/checksum/curation comparisons.
3. **Make inputs and packages reproducible.** Resolve missing historical hashes for 27 compiler
   paths or document the unrecoverable boundary; review dirty/untracked inputs; replace junction/
   temp-binary assumptions; reconstruct an owned build/service location and pin final artifacts.
4. **Finish native/compiler acceptance.** Advertised production/headless method parity;
   provenance/curation/privacy/role/import/export; future-complete durable state on final inputs.
   Diagnose closure CLI timing under load without silently enlarging deadlines. Two authored
   handoffs are supporting evidence, not whole-method-surface or genuine certification.
5. **Qualify actual production boundaries.** OS-level egress, filesystem/process/thread controls,
   full scenario cancellation/cleanup, CPU/memory/time/run budgets, cold/warm and parallel
   scenario throughput against explicit requirements. Node guards/handshake timing do not substitute.
6. **Reconcile migrations from authority.** Obtain deployed history and matching artifact/commit,
   settle V47-V49/V53-V57/V58-V60 and local migration ownership, implement reviewed forward-compatible
   changes, then prove actual Flyway installation/upgrades, data preservation/refusal/recovery on
   disposable stores. SQL-only empty installs are not upgrade proof.
7. **Qualify clock and renewal operations.** Assign owners, establish supported sustained
   synchronization and remeasure all actors including restored source. Test renewal/retry/restart/
   revocation/expiry with TTL at most 120 seconds and durable fences. Current clock alignment
   does not waive the existing authorization gate for host changes.
8. **Obtain allocation, approved trust and genuine input.** Supported SC-800 issuer/lifecycle;
   actual machine/session/executor/artifact/catalog/schema; exact-artifact original-trust bundle
   or explicit amendment; immutable profile/context, verified stabilization and eligible capture
   with authorized user login where needed. Do not promote fixtures or invent identity.
9. **Package and qualify real Watch.** Paired worker/client, missing/mismatched dependency refusal,
   authenticated exact-origin lifecycle, one-actor ownership and observer/camera/disconnect
   noninterference. Obtain separate human Watch sign-off.
10. **Finish style, matrix and authorized delivery.** Resolve scoped style requirements without
    overwriting unrelated dirty work. Freeze final inputs and run remaining required affected/CI/
    differential/migration/security/performance checks. Prepare reviewed monitoring and rollback;
    obtain deployment/merge/publish authorization as applicable before shared rollout.

Local work continues while release authority, trust, supported allocation and human inputs
wait. Restore no stopped logged-in session and change no canonical trust implicitly.

## All 17 original acceptance rows

| Row | Supporting evidence / remaining gate |
|---|---|
| 274-1 | Fresh-JVM fixture equality passed; genuine final inputs/package/pin remain |
| 274-2 | Authored provenance/curation checks passed; genuine input provenance remains |
| 274-3 | Authored invalid/incomplete refusals passed; final-pin/genuine coverage remains |
| 274-4 | Authored Fishing handoff passed; genuine supported native causal execution remains |
| 274-5 | Provenance refusal 3/3 passed; full supported legacy/incomplete boundary remains |
| 274-6 | Authored privacy/role evidence retained; genuine immutable role/privacy proof remains |
| 274-7 | Bounded import contracts passed; final package qualification remains |
| 274-travel | Authored bank/travel/Fishing passed; exact genuine world/action evidence remains |
| 274-historical | Accounting/audit reconciled, NOT_READY confirmed; existing recording ineligible |
| 276-boundary | Actual recovered native handoff passed; production package qualification remains |
| 276-methods | Two authored routes passed; remaining advertised production/headless methods remain |
| 276-closure | Guard evidence plus CLI timeout; portable closure/load reproducibility remain |
| 276-state | Authored restore/checkpoint equality passed; final production state coverage remains |
| 276-safety | Node API guard evidence; actual OS/resource/cancellation enforcement remains |
| 276-live | Registry reachable/unselectable; supported allocation and real lifecycle remain |
| 276-client | Genuine paired-client/Watch/noninterference/human sign-off absent |
| 276-certification | Red tests, formatting failure and open production/external gates prevent closure |

## Unrun checks, risks and rollback

Not rerun: the entire repository/all-repository corpus, database-backed six-worker continuation,
migration upgrades, production OS sandbox/full limits, genuine source/capture and real Watch.
Retained prior evidence keeps its original scope. Private signing material, allocation
authority and human approval were not fabricated.

Verification build outputs live in the owned snapshot. No service/database rollback is needed.
Preserve logs, failed attempts, current user changes, grants/fence volumes and high-water
generations. Never restore exposed credentials/expired grants as rollback; never run
wsl --shutdown. No shared rollout or tracker transition is authorized by this report.

## Follow-up implementation - 23 October 2026

Local implementation of remaining sequence items 1 and 2 only. No tracker, service, trust,
migration, capture or shared configuration state changed. Felix was unreachable
(`http://127.0.0.1:4301/mcp` refused), so no task, note or Shortcut transition was claimed.

### Durable publication and model error contract

- `ImmutableArtifactStore.publish` now delegates the post-link directory flush to `forceDirectory`.
  POSIX still forces the parent directory and fails closed. Windows cannot open a directory handle
  through `java.nio` (`AccessDeniedException`), so it relies on NTFS journaling of the link after
  the object channel was already forced; no durability check was removed on any other platform.
- `FishingDatasetBuilderService.loadModel` maps a missing registry reference to the stable
  `MODEL_NOT_FOUND` contract. Corruption, quarantine and storage-unavailable statuses keep their
  storage status instead of being flattened into a not-found result.

### Fixtures and fresh-JVM harness

- `FishingActiveModelServiceTest` now publishes each fixture model into the immutable registry that
  production loading reads, while keeping the compatibility alias that listing still scans.
- `FishingDatasetBuilderServiceTest` injects the mandatory run context and canonical frames.
- The fresh-JVM harness now reaps every child even when an earlier cleanup fails, clears and
  restores the caller's interrupt flag around bounded cleanup, and adds tests for a failed first
  cleanup, a second-spawn failure and interruption. The determinism body is parameterized by the
  child factory so spawn failure is exercised without weakening the primary assertions.

### Fresh local results

Commands run from the worktree with `.\gradlew.bat` on Windows, JDK 17 test runtime:

| Command / suite | Result |
|---|---|
| `artifactCompletionTest` (ImmutableArtifactStore 13, registry integration 1, related 47) | 61/61 passed |
| `test --tests ...FishingActiveModelServiceTest` | 19/19 passed (16 previously red) |
| `test --tests ...FishingDatasetBuilderServiceTest` | 4/4 passed (3 previously red) |
| `replayTest --tests ...ScenarioManifestFreshJvmDeterminismTest` | 6/6 passed (3 added) |
| `checkstyleMain checkstyleTest` | Build successful; warnings only (4,732 main) |
| `spotlessJavaCheck` | Still fails on 1,363 pre-existing files; the five touched files are google-java-format clean and not listed |

The 32 red Java cases in the prior section now pass. No acceptance row is closed: packaging,
genuine inputs, allocation, trust, migrations, clock/renewal, native parity, OS limits, Watch
and human sign-off remain open exactly as listed above.
