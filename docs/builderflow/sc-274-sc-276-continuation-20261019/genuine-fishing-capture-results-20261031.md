# SC-274 genuine manual Fishing capture — 31 October 2026

The user-operated Windows capture gate now has real passing evidence. **SC-274 is not marked Done:** full causal manifest generation/execution and the other original acceptance requirements remain independently gated.

## Implemented plan

1. Preserve the original recording and all rejected attempts; do not repair missing observations by editing counters.
2. Implement a separate bounded client-observation path for manual HUMAN / FISHING_TRAINING / TROUT. Keep bot dispatch acknowledgement requirements intact.
3. Validate source/consumer contracts, actual inventory aggregates, observer continuity, pending intents, finalization and canonical evidence links. Import observations through the production canonical reader, without writing fake bot actions.
4. Test source, serialization, PostgreSQL persistence/recreated readers, refusals and compiler mapping. Build/sign clean client source with the already approved development signer.
5. Activate only the owned Windows service/client, with actual owned Launcher lease metadata, an immutable updated profile, operator-issued owned grants and an exact-artifact read-only catalog bridge.
6. Verify live source/clock/start-stop mechanics, then record the user's actual lure interaction and five raw trout without extra game/inventory actions.
7. Finalize and verify raw/canonical/resource/clock/media evidence. Freeze an unmodified held-out curation and verify production source conversion in fresh JVMs over the real stored rows.
8. Exercise live selected-curation preflight. Fix its transaction error path while retaining the unavailable simulator gate. Record the remaining original story gates without claiming completion.

## Changed files

Micro6 commits: `3f5db7a84dc96dd378f2f977deb351c4472d6bfe` adds the separate observer/outbox/plugin/message integration and tests; `132fde8c4e492ac4ff7876cd5e76d66b04623c8b` retains an exact pending lure witness for at most six ticks. The four-tick real delay and the six-tick boundary/expiry are covered. The running JAR was protected by building the correction in a new worktree.

ChromaScape commits `793597062a4cc87ecd2e1be78787b14d48a2f8a7` and `1414d0dc6926da8e37f6c5c7d8b30c61e31445e6` add the human frame contract, canonical aggregation/observations, bounded production reader, converter routing, manifest frame evidence and tests. The follow-up in this increment changes only `ScenarioManifestV2Compiler.preview` transaction treatment for expected `ReplayException` refusals and adds `ReplayV2PreflightTransactionIntegrationTest`.

Full file-level plans and intermediate findings: `human-fishing-capture-plan-20261031.md`, `human-fishing-capture-verification-20261031.md`, `human-fishing-live-delay-review-20261031.md`.

## Acceptance results

Actual finalized capture:

| Field | Verified result |
|---|---|
| Recording | `recording_af17d0dc` |
| Debug session | `df5691b4-3168-47f1-8afc-1806568293ee` |
| Run context | `d1590d33-c283-4e1f-8481-28d5d1a2e098` |
| Source commit | `132fde8c4e492ac4ff7876cd5e76d66b04623c8b` |
| Artifact | `sha256:5f3e4bb9cc889803fe2c9ec201a704d531d2fba40373b752f521bdf7b2e4bcec` |
| Signed bundle / snapshot | `srcbundle_75de9b0c2828381d410266f8` / `srcsnap_9c9888ef2097a8119f259649` |
| Profile | `1a67835e-3c32-4d95-94ab-46b537ff5687`, immutable version 3 |
| Source allocation / executor | `7b0d6566-71f2-4c9b-8cc4-5d21e423916e` / `12bca985-93ef-4482-b9c3-b077febae42a` |
| Tick range | 1417–1652, 236 contiguous raw and canonical ticks |
| Clock | 236/236 calibrated, no quality codes; reported maximum alignment error 37.735 ms |
| Resource ledger | 5 raw trout + 3 raw salmon; 8 feathers consumed; +460 Fishing XP |
| Interaction | 1 accepted CLIENT_EVENT lure transition, observed after 4 ticks |
| Coverage | Zero dropped/unknown frames; one observer; all callbacks accounted; terminal true, pending zero |
| Capture eligibility | `ELIGIBLE`; authorized training-frame sample returns the same pinned frame |
| Video | `VERIFIED`, measured-video-index/v1; all 4,060 indexed frames decode through EOF |
| Video identity | SHA-256 `4834fbb772b9ba6337114970f22b719f96d281fb53e0a3c8e1dd6c8a1cc1143d`; 141.367 s, 764×502 |

The capture was stopped normally after the target. The user may interact with the game/inventory afterwards; those later actions are outside this finalized recording.

The normal curation API created and published **HELD_OUT** curation `bf83bfe1-636d-4728-99e0-516038198e1b`, version 1, checksum `sha256:4e5cc4baacbf99286d1338a0ed04fbcd32a32bbc70e135ff91260c031986830e`. It retains the full recorded range and adds no invented action labels, state overrides or termination policy.

The production `HumanFishingActionReader` was run in two fresh JVMs over the real persisted data, through bound read-only JPA repository adapters and PostgreSQL connections enforcing `default_transaction_read_only=on`. No authored rows were used. PIDs 62428 and 100692 produced byte-identical conversion output, one action, no gaps:

- Conversion checksum: `sha256:032f07d5aae9373b0b55fa49b9449faf7d4d8c1c9d56e561e7a53e4e5e242dce`.
- Canonical output digest: `sha256:9e030b18827f40010762f8187c7142713ac4c3e9b644200f764eb4598a3ee329`.
- Source is `MICRO6_CLIENT_OBSERVATION`, not a bot dispatch. The older failed attempt still returns `CAPTURE_INCOMPLETE` in both JVMs.

This proves captured conversion determinism, **not a generated runnable manifest or native fidelity**. Live capabilities still report causal simulation false, certifiable false, fidelity SYNTHETIC_CONTRACT. The first two selected-curation preflights returned HTTP 500 because a caught expected compiler refusal poisoned the surrounding transaction. The new narrowly scoped transaction fix and its real PostgreSQL/Spring regression tests pass; live replacement/recheck results must be recorded separately after they actually run.

## Tests

- Micro6: 111 selected tests, zero failures/errors/skips.
- ChromaScape recording/provenance: 199 tests, zero failures/errors/skips, including actual disposable PostgreSQL cases.
- Selected conversion/compiler/preflight: 69 tests, zero failures/errors/skips. The two new real-transaction tests verify an unavailable worker returns BLOCKED without an unexpected rollback and unexpected persistence exceptions still propagate.
- Live exact-artifact source verification: signature and all 34 files, real verifier 15/15; readiness 14/14; owned bridge 2/2.
- Final capture: 20/20 explicit checks; all 4,060 video frames decoded and matched the measured physical index.
- Real stored production conversion: two fresh JVMs, identical bytes/checksum, read-only database, no authored fixtures.

No full repository/native/JS/Watch certification suite or power-loss test was run in this increment. Earlier rejected captures are preserved and remain ineligible; the first too-short window and later inventory/movement inputs are not hidden or retroactively upgraded.

## Remaining work

1. Finish live verification of the structured preflight transaction fix; the expected outcome is a truthful simulator blocker, not a simulated pass.
2. Provide a supported, qualified runtime on the appropriate target (Windows service currently has no causal capability; Linux safety/qualification remains SC-2201). Only then generate the full manifest twice from this published curation, compare bytes/checksum, run bounded native import and assess world/cache/source gaps.
3. Independently satisfy original provider-origin first input, bank/travel/world and privacy/role acceptance where still missing. This user-operated HUMAN recording does not substitute for those requirements or independently qualify game fidelity.
4. Reconcile every original SC-274 acceptance row and workflow gates. No Done transition is justified by this capture alone.

## Risks

HUMAN origin is operator-declared. A witnessed client lure transition is not hardware-origin attestation, owned bot dispatch or oracle fidelity. The development signer remains a scoped approved development signer; production/original-trust adoption is not claimed. The owned registry uses operator-issued grants rather than SC-800 production automatic issuance. Windows service artifacts use explicit development durability, not POSIX/power-loss qualification. Host time/renewal operational ownership, migration authority and other original release gates are not resolved by a clean capture.

## Rollback

Keep all finalized recordings, private raw evidence, signed bundles, checksums, profile versions, curation and fence volumes. The preflight-only fix can revert to the preceding owned service snapshot while keeping the compatible six-tick human decoder; it would restore the known HTTP-500 defect. Stop new capture admission before any broader feature rollback, and retain a compatible reader or frozen exports for already sealed frames. Never rewrite quality, promote rejected attempts, reset fences, restore retired secrets or overwrite a running client JAR.

## Durable records and tracker status

Evidence root: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261031\human-fishing-coverage`; final records under `delay-correction`, transaction follow-up under `preflight-transaction-fix`. Raw snapshots, media metadata and converter logs remain in the protected private root; public reports contain checks/hashes and safe identities.

The existing Felix continuation plan was updated successfully earlier this session. Original task `task_1789960012362_td2qnusp` still cannot be resolved; canonical project task searches found no replacement. No duplicate task or task-state success is claimed. Shortcut was read as In Review and has not been advanced to Done. Existing pending records remain pending their missing task reconciliation.
