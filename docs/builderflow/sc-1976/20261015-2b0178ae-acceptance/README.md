# SC-1976 accepted combat recording and standalone schema fix

Source: recording_2b0178ae at http://127.0.0.1:18090/debugger?recordingId=recording_2b0178ae. Existing Felix task task_1791760667867_s74wdsgf; shared task task_1791840092160_ekr5gv6a. Existing PRs: ChromaScape #59 and Micro6 #162. Eating and hosted Actions are excluded. No authentication/gameplay automation or application restart occurred.

## Complete implemented plan

1. Publish combat-tick-row/v1 with captured/derived/deferred field provenance, semantic vocabulary mappings and diagnostic-only raw inputs.
2. Capture bounded special attack/prayer state, NPC/target context, collisions, hazards, phase, inventory/equipment and raw inputs in Micro6; retain negotiated packet/checksum behavior.
3. Persist/project additive fields and deterministic coverage, durable recording boundaries, measured clock/physical-video alignment and immutable client/server/build/revision/schema/media identity.
4. Attribute WALK to a validated selected scene tile and changed authoritative destination with actual progress inside the unchanged four-tick horizon. Reject unsupported/stale/competing and unproven historical labels without rewriting history.
5. Verify real combat, create an accepted sanitized golden fixture and regressions, and submit existing PRs for review. This recording closes the live movement/attack/prayer and fixture gap. The standalone combat schema now embeds the canonical strictly bounded optional movementIntent definition; a parity regression prevents drift.

Rules: C:\Dev\IdeaProjects\AGENTS.md and both canonical project AGENTS.md files. Earlier source/test/runtime history and rules are in [the complete preceding plan](../20261015-walk-attribution-fix/README.md). Full SC751 Fishing, food/Eat, model promotion, wider readiness policy, Actions, production deployment and PR merges remain excluded.

## Acceptance results

- FINALIZED:101 consecutive ticks509-609, no gaps/duplicates; every required combat field present. Production quality validation checks captured-state agreement for all101 snapshots. Read-only PostgreSQL comparison:101 rows x11 sections, zero mismatches;49 resolved NPC target ticks. Unavailable absolute NPC HP remains null, with health-bar ratio/scale retained.
-36 unique raw callbacks/settlements:22 confirmed,13 ambiguous,1 unconfirmed. Production label/coverage gates admit4 attacks,4 movements and14 prayer toggles. The79 other tick rows lack an eligible label; they are not missing data. Raw/ambiguous/unconfirmed evidence remains diagnostic.
- All4 movement settlements have selected-scene/changed-destination proof and actual progress. Two settle one tick later, inside the fixed horizon. Canvas operands remain raw diagnostics.
-101 measured endpoints:maximum37.324624ms, p95 25.833662ms, under100ms. All100 available next-tick ranges map. Terminal609 lacks a successor; its range stays nullable rather than extrapolated.
-1539 physical video frames fully decode; a private sampled image confirms Giant spider combat/prayer UI. Exact local media SHA256 matches saved VERIFIED provenance. Client429e32978b, packaged SHA256893c4f6942ac32ad466ba9c5e86fe436ee4ceca783a81743bbced16d41b4a72b, game/cache namespace241 and recorded server7f507c8f match the captured runtime.
- Full session/snapshots/provenance unchanged more than7minutes after Stop. No new restart; earlier recording/identity survival across owned18090 restarts is separate documented evidence. Original8090 and the in-game client are preserved.

## Changed files

- src/main/resources/contracts/replay/combat-tick-row-v1.schema.json: update embedded strict capture-quality definition. Unknown properties remain rejected; no migration.
- src/test/java/com/chromascape/web/aiplayer/combat/CombatRealCaptureContractTest.java: parity, actual ingestion/JSON/projection/schema/coverage, duplicate resistance and negative label/schema checks.
- src/test/resources/replay/contracts/combat-real-capture-2b0178ae-v1.json: eight actual selected snapshots, measured mappings and source/build/media hashes. Accepted combat regression examples; not held-out evaluation or readiness certification.
- This README, aggregate.json and preceding plan's current handoff. No new Micro6 source change.

Sanitization preserves raw canvas operands/hashes, lineage, real mutations, collection sizes, flags, scene coordinates and measured durations/uncertainty. It consistently pseudonymizes session/producer/event IDs; translates world coordinates and bases together to3200/3200; shifts clocks together to2000-01-01; removes unused player/profile/skill state; and redacts ancillary player/widget/quest strings and NPC interactingPlayerName. Actual ancillary numeric/boolean state remains. No events, positions, proof or timing are invented. Six eligible examples remain (1attack/4movement/1prayer); two diagnostic rows remain ineligible. No account names, original absolute positions, screenshots, pixels or secrets are committed. Full101-row validation is private; the fixture explicitly samples eight rows.

## Tests

-27 focused native Chroma tests, zero failures/errors/skips: CaptureQualityContractTest7, CombatTickRowServiceTest10, CombatRecordingCoverageTest4, CombatTickRowIngestionRoundTripTest3, CombatRealCaptureContractTest3. Corretto21/Gradle8.14.2/canonical SC967 vectors.
- Before-fix regression: parity and actual tick556 movement schema fail. An initial fixture omitted strict ancillary sections; these were restored from sanitized actual source. Final tests pass after schema synchronization, including the final privacy-redacted fixture.
- Direct current production validation/captured-state agreement/row/schema/coverage over101 actual snapshots:pass; four schema errors before,zero after.101x11 actual PostgreSQL comparison, media hashing/full FFmpeg decode/private visual check, authenticated health and delayed API equality pass.
- Previous Micro1725/capture contract/package checks retain unchanged429e32978b scope; not rerun. Earlier Chroma408/overlapping23 identity-PostgreSQL checks retain their stated older-source scope. Broad formatter whitespace-only changes in unrelated files were restored before publication.
- Four Docker debugger suites, production migration-chain certification and restart of this particular recording remain unrun. Hosted Actions skipped by explicit user instruction. No disabled checkstyle pass is claimed.

## Remaining work

All SC1976 implementation and capture items now have evidence for the review gate. Review and merge the paired PRs together; this handoff performs no merge or production rollout. No further gameplay recording is needed for the accepted movement/attack/prayer subset. Full SC751 Fishing remains outside this shared combat delivery.

## Risks

Movement remains conservative for unavailable/replaced/stale routes or lag beyond four ticks. One spider encounter does not establish combat diversity or dataset readiness. Absolute NPC HP and richer boss phases remain explicitly limited. Recorded identity belongs to the captured runtime and must not be replaced by the later schema-only commit. Terminal range nullability is intentional.

## Rollback

Revert the focused standalone-schema/test/fixture commit, retaining immutable recordings, additive columns and movement safety gates. Preserve original8090, saved accounts, routing and reservation files. Reverting restores movement-row validation errors; do not strip proof or admit unproven legacy labels as compensation.

## Tracker handoff

Actual Felix update and independent read confirm task_1791760667867_s74wdsgf passed its real review gate and is spec_ready/in_review; all implementation/acceptance/test items are checked with evidence. Six existing plans/Implementation Evidence/Verification Evidence notes were updated and read back. No waiver or skip_validation. Official Shortcut MCP updated SC1976 to In Review500000009; comment2072 contains exactly one [felix-sync:task_1791760667867_s74wdsgf:review] marker, verified by read-back. Shared task_1791840092160_ekr5gv6a remains spec_ready/in_progress and Shortcut SC751 stays In Progress500000008, with combat evidence comment2073 and fullFishing acceptance open. Both existing PR descriptions were rewritten and ChromaPR59/MicroPR162 successfully marked ready for review; independent reads confirm OPEN/isDraft=false. Source/schema/fixture commit86c0b2f0 pushed to PR59; Micro source429e32978b unchanged. Final tracker documentation is an evidence-only follow-up. No PR merge, production rollout or Done transition.
