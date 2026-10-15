# SC-1976 combat review: recording_eeed6066

Reviewed 2026-10-13. Recording: http://127.0.0.1:18090/debugger?recordingId=recording_eeed6066. Session: `debug_9be3927adc564fdda74ac79cf7054a2b`. SC-1976 remains incomplete. Live eating is excluded by the user's explicit instruction; empty inventory is not a blocker or a claimed eating pass. The existing Eat implementation remains intact.

## Implemented plan and changed files

The complete implemented plan and earlier source/test evidence remain in [README.md](README.md). This inspection changes only README.md, this review and recording-eeed6066-results.json. It does not implement the previously identified frame-selection or per-recording provenance changes. Raw payloads, player identifiers, gameplay video/images and service credentials remain outside Git.

## Acceptance results

- FINALIZED, 102 distinct consecutive ticks 34–135, no missing/duplicate ticks or failure marker. All required input/environment/inventory/equipment/NPC/projectile/ground-object fields, specialAttack/prayersActive and captureQuality are present on all 102 rows. Actual combat-row schema validation has zero errors.
- Direct read-only PostgreSQL comparison of 102 rows across 11 captured JSON sections has zero mismatches. All 36 NPC-interaction ticks resolve their captured target. There are 28 observed player position changes.
- All 102 clocks are MEASURED with one session/producer/epoch lineage. Terminal tick 135 has pendingCount 0. Current client health is LOGGED_IN, connected/ready/ACTIVE, with zero queued/pending/incomplete/dropped batches and no fail-closed state.
- 26 immutable raw events have 26 settlements: 11 CONFIRMED, 11 AMBIGUOUS and 4 UNCONFIRMED. Confirmed actions are 2 attacks, 1 movement and 8 prayer toggles. Unsupported/competing intents, expired movement outcomes and a disappeared target remain diagnostic and ineligible.
- The deterministic combat coverage adapter produces 102 distinct rows and nine eligible row labels: 1 INTERACT_NPC and 8 INTERACT_WIDGET. Tick 118 contains both a confirmed movement and attack; the existing single-label ambiguity fence correctly excludes both from that row. The 93 ineligible rows are mostly rows without a label, not 93 capture failures. Empty producer-deferred counts do not establish video eligibility or accepted fixture status.
- Full native FFmpeg decode succeeds with no decoder errors: 1,159 physical frames over 62.766667 seconds, approximately 18.47 FPS. SHA-256: `a93d42a93b0fdc349489fca395e72ef0f483e9ad83f20eec9a42aad863cba3ac`. Finalized timing index digest/count/duration match the media; no index-level quality codes. Inspected images at 20/32/54 seconds show Giant spider combat, prayer UI and movement.
- Current service mapping accepts 91/102 ticks within the existing 100 ms combined image-residual/clock-uncertainty limit. Ticks 60, 88, 92, 94, 99, 101, 103, 109, 110, 123 and 134 remain UNAVAILABLE with FRAME_ALIGNMENT_UNCERTAINTY. Combined errors are 101.4340–187.3390 ms. Passing maximum is 97.8379 ms, p95 93.5685 ms. There are 80 measured frame ranges and 22 nullable/unavailable ranges, including the terminal tick with no next endpoint.
- The index contains 332 frames with nonzero image age; maximum age is 229.2007 ms. A separate read-only nearest-capture-time audit finds actual physical frames within 100 ms for all 102 ticks, including all 11 current failures. Maximum combined error is 98.6943 ms and selected indices are nondecreasing. This isolates a presentation-time frame-selection defect for this recording. It is an audit, not a service fix or accepted mapping. It neither relaxes the threshold nor invents missing images; the older recording still contains genuine intervals without a sufficiently close image.
- Beginning/middle/end ticks 34/84/135 currently map to physical frames 19/604/1137 within the limit. Finalized tick/time bounds and all snapshots are unchanged on a repeated read 98.5 seconds later. No ChromaScape restart was performed, so restart stability of this recording remains unverified. Restarting Micro6 before a recording does not prove stored-recording survival across a storage-service restart.

## Tests

Actually run: session/tick/sync/quality GET inspections; read-only PostgreSQL equality across 102 rows × 11 sections; production capture-quality validation, combat-row construction/schema validation and deterministic coverage; native FFmpeg full MP4 decode and three visual inspections; digest/index/frame-age and nearest-capture audits; repeated finalized-bounds and snapshot equality checks.

No source changed, so build/unit suites were not rerun. Hosted GitHub Actions remain skipped by explicit user instruction. Previous local regression results remain as documented, including the complete client run's four shared-vector setup failures followed by the successful affected-class rerun; no single clean 1,704-test invocation is claimed. Production migration-chain certification and four Docker debugger suites remain unverified.

## Remaining work

Implement ChromaScape frame selection using real capture timestamps and qualify fresh-image timing under the unchanged measured limits; transmit/persist/expose exact per-recording client/server build, client/cache revision, schema and media identities; add relevant regression coverage. Then verify actual service mapping and storage/projection/coverage after a ChromaScape restart, and publish an accepted sanitized fixture with truthful provenance. Retain the single-label ambiguity fence; the accepted fixture also needs an unambiguous movement row. Eating remains excluded. Full SC-751 Fishing delivery is outside this combat dependency.

## Risks

The current debugger still serves 11 unavailable mappings; the audit alone does not make them accepted. The served session exposes no per-recording build/cache/revision identity. Current health matches Micro6 build `8717d3ee34cc2de49df9e18952475fcd6f6eb769`, source mode disabled, artifact SHA-256 `2e5e6cfa606a97c9ad3b3f233ffa95b950fbaafba774e4f243fd2f1ec7ef3ddd`, but that must not be invented as stored recording provenance. No accepted golden fixture or story completion is claimed.

## Rollback

No runtime/source change to roll back. Revert only these documentation changes if necessary. Preserve the finalized recording, sidecar, database rows, mixed user worktrees and runtime reservations. No gameplay/authentication automation, recording-control mutation, restart, force push or PR merge was performed during this inspection.

## Delivery record

Existing Felix tasks `task_1791760667867_s74wdsgf` (SC-1976) and `task_1791840092160_ekr5gv6a` (SC-751) remain `spec_ready/in_progress`. Their existing Implementation/Verification Evidence notes and task descriptions were updated through the canonical-source native MCP and read back successfully. Incomplete acceptance/fixture checklists remain incomplete.

Official Shortcut MCP successfully posted idempotent recording-review comments [SC-1976 activity 2061](https://app.shortcut.com/leeuwbot/story/1976#activity-2061) and [SC-751 activity 2062](https://app.shortcut.com/leeuwbot/story/751#activity-2062), using `[capture-review:recording_eeed6066]`. SC-1976's current delivery description was refreshed with the actual capture, eating exclusion and remaining work. Both stories remain In Progress (`500000008`); no review/done transition or completion marker was emitted.
