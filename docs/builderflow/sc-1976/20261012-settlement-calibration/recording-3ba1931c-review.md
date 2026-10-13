# SC-1976 real combat recording review: recording_3ba1931c

Reviewed 2026-10-13 on the paired loopback runtime at port 18090. Recording URL: http://127.0.0.1:18090/debugger?recordingId=recording_3ba1931c. Session: `debug_b13ec8685873404e9b728b159e2f63c9`. This is real user-performed Giant spider combat. It is partial acceptance evidence; neither SC-1976 nor the shared SC-751 foundation is completed.

## Implemented plan and changed files

The complete implemented plan, source changes, previous local checks, risks and rollback remain in [README.md](README.md). This review changes documentation/evidence only. No gameplay automation, source/runtime changes, recording deletion, hosted Actions, restart or PR merge occurred.

## Acceptance results

- Finalized 92 distinct consecutive ticks, 27–118, with no missing/duplicate ticks or failure marker. Required inputs/tiles/hazards/phase, specialAttack/prayersActive, inventory/equipment/NPC/projectile/ground-object producers are present on all 92 rows. Empty arrays remain valid observed empty data, not missing fields.
- A native direct PostgreSQL read compared 11 captured JSON sections for all 92 ticks against served snapshots: zero field mismatches. Actual combat-row construction and published schema validation produced 92 rows with zero schema errors. All 35 NPC-interaction ticks resolve their captured target. Player position changes 26 times.
- All 92 capture clocks are MEASURED with one exact session/producer/epoch lineage. The previous clock-reply race does not recur. Terminal tick 118 has pendingCount 0; current transport has no queued/pending/incomplete/dropped batches or fail-closed state.
- 29 immutable raw callbacks yield 29 settled outcomes: 11 CONFIRMED, 15 AMBIGUOUS, 3 UNCONFIRMED. Confirmed actions: 2 INTERACT_NPC attacks, 3 WALK_TO movements, 6 INTERACT_WIDGET prayer toggles. Prayer state visibly/canonically changes for Protect from Melee, Superhuman Strength and Retribution. Two initial attacks remain TARGET_DISAPPEARED_OR_REPLACED; repeated movement clicks remain ambiguous and one walk expires. No raw/unsupported/ambiguous callback is promoted into a semantic label.
- Inventory is empty on all 92 ticks and there is no Eat event. Inventory producer presence is verified; actual inventoryEat behavior is unverified.
- SC-752's existing deterministic combat coverage adapter reports 92 distinct rows/ticks, action counts 2/3/6 and 81 ineligible rows (mostly no action, not 81 capture failures), with no producer/target deferred gaps. These counts do not certify measured-video eligibility or dataset readiness.
- MP4 fully decodes 1,008 physical frames over 55.633333 seconds, actual average 18.12 FPS rather than configured 30. SHA-256: `fb4760c2b77390dbdd4fc35411b8033b98542e3e26ac090c5f8565f41fbaea5c`. The timing sidecar is finalized, digest-bound, has 1,008 indexed frames and no index-level quality codes. Inspected frames at 8/32/48 seconds confirm Giant spider combat/movement/prayer UI.
- 85/92 tick mappings meet the 100 ms combined image-residual/clock-uncertainty threshold. Passing maximum 99.4880 ms, p95 87.2596 ms. Ticks 35, 42, 45, 51, 76, 89, 93 are explicitly UNAVAILABLE with FRAME_ALIGNMENT_UNCERTAINTY; their combined errors range 102.8276–349.7322 ms. Repeated images are 103–338 ms old at these examples; some have lower image age but a larger nearest-image residual. The index contains 282 repeated/stale-image frames, maximum image age 527.6773 ms. Healthy clocks do not remove intervals without a fresh image.
- Beginning/middle/end ticks 27/70/118 map to physical frames 9/432/997 within the limit. 77 per-tick frame ranges have both endpoints measured. Seven unavailable mappings also affect the preceding ranges, and terminal tick 118 has no next-tick endpoint: 15 ranges are nullable/unavailable. The schema explicitly permits these unknowns; empty producer-deferred counts do not make these ranges measured.
- Final start/end ticks, count and timestamps, and all served snapshots remain unchanged on a read more than 60 seconds after the first inspection. No service restart was performed, so restart stability is not claimed.

## Tests

Actually run for this review: recording/session/tick/sync/quality GET inspections; full native FFmpeg MP4 decode and three visual frame inspections; digest/physical-index/frame-age audit; native capture contract and combat-row schema validation for 92 real stored rows; deterministic coverage generation; read-only PostgreSQL comparison of 92 rows across 11 captured sections; repeated finalized-bounds and snapshot equality check.

No unit/build/hosted suites rerun because source was unchanged. Prior code regression results remain as recorded in README/local-checks.json. Four Docker debugger suites and production migration-chain certification remain unverified. Safe aggregate results are in recording-3ba1931c-results.json. Raw player/character identifiers, gameplay pixels/video, private payloads and service credentials are kept outside Git; no accepted sanitized golden fixture is claimed.

## Remaining work

Verify actual inventoryEat with food consumption; improve/qualify fresh-image capture timing so required action/tick windows satisfy the existing measured limit without relaxing it; compare a complete accepted recording with storage/projection/coverage; verify restart-stable finalized bounds; produce an accepted sanitized fixture with source/build/schema/media provenance. Full SC-751 Fishing and broader SC-667/SC-670/SC-712 delivery remain outside this shared combat subset. Both stories/PRs remain In Progress/draft; no review/done marker is warranted.

## Risks

The existing media must retain seven unavailable mapping ticks; it cannot become a fully calibrated fixture by changing the threshold or inferring missing images. Confirmed semantic state events are separate from video eligibility. Current authenticated health matches pushed Micro6 build `8717d3ee34`, source_mode disabled, JAR SHA-256 `2e5e6cfa606a97c9ad3b3f233ffa95b950fbaafba774e4f243fd2f1ec7ef3ddd`. The served recording session does not itself expose build/cache/revision provenance; that should not be invented for the accepted fixture. Historical matching runtime checks are evidence, not a new per-recording provenance field.

## Delivery record

Existing Felix tasks `task_1791760667867_s74wdsgf` (SC-1976) and `task_1791840092160_ekr5gv6a` (SC-751) remain `spec_ready/in_progress`. Their existing Implementation/Verification Evidence notes and task descriptions were updated through the canonical-source native MCP and read back successfully; the authoritative plans and incomplete acceptance checklists are preserved.

Official Shortcut MCP successfully posted idempotent review comments [SC-1976 activity 2057](https://app.shortcut.com/leeuwbot/story/1976#activity-2057) and [SC-751 activity 2058](https://app.shortcut.com/leeuwbot/story/751#activity-2058), using `[capture-review:recording_3ba1931c]`. Both stories remain In Progress (`500000008`). No review/done transition or completion marker was emitted.

## Rollback

No runtime/source change to roll back for this review. Revert only these documentation changes if needed. Preserve the finalized recording, media index, database rows, mixed user checkouts and runtime reservations. Never force push, merge PRs, automate login/gameplay or shut WSL down.
