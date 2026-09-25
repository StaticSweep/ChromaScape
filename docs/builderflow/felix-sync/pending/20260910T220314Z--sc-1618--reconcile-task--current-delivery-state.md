---
schema_version: 1
offline_record_id: "720edb32-ccbc-4a27-999a-7df0cb640b85"
sync_status: pending
project: 'C:\Dev\IdeaProjects\ChromaScape'
shortcut_story_id: 1618
shortcut_tag: "shortcut:sc-1618"
felix_task_id: "task_1788808364914_0z4klpcjk"
operation: reconcile_task
target_note_id: null
title: "SC-1618 - reconcile current packet delivery state"
note_type: documentation
stable_tags:
  - shortcut:sc-1618
  - change:multi-repo
  - reconciliation
  - offline-sync:720edb32-ccbc-4a27-999a-7df0cb640b85
parent_id: null
intended_entity_links:
  - entity_type: shortcut_story
    entity_id: "1618"
    link_strength: primary
created_at: "2026-09-10T22:03:14Z"
intended_felix_task_state: in_progress
intended_felix_spec_state: in_progress
intended_shortcut_transition: null
intended_shortcut_idempotency_marker: null
---

## Task resolution

Resolve, never duplicate, Felix task task_1788808364914_0z4klpcjk by the stable
tag shortcut:sc-1618 in canonical project C:\Dev\IdeaProjects\ChromaScape.
The workflow is shortcut_delivery. The source is
https://app.shortcut.com/leeuwbot/story/1618. The sole classification tag is
change:multi-repo. Preserve existing task content and unrelated checklist
items. The known current task state is in_progress and the known current story
state is Shortcut In Progress, workflow_state_id 500000008.

The task covers the post-redaction RSProx -> Micro6 -> ChromaScape packet path:
bounded versioned local envelopes, asynchronous v1/v2 consumption, authoritative
session/tick binding, schema-v3 TICK_BATCH checksum/ACK/retry, transactional
packet persistence and replay, live debugger display, bounded diagnostics, and
safe text rendering.

Exclusions: packet injection/mutation/replay-to-game, public listeners, replacing
legacy Kryo/binary capture, ordinary packet-body logging, packet forwarding to
leeuwbotbackend, a fully shaded Micro6 client, unrelated debugger redesign,
editing applied Flyway migrations, modifying unrelated dirty/untracked files,
and claiming review/done without live verification.

Linked rules: all parent/repository AGENTS.md files; Windows paths for Felix and
Shortcut; native Windows Micro6/ChromaScape/RSProx builds and runtime; no
wsl --shutdown; exact-file staging; shared 1 MiB-or-smaller frame bounds; raw
sanitized bytes disabled by default; schema 2 packetless compatibility;
schema-v3 packet checksums; ACK only after commit; and durable Felix outbox
replay oldest-first.

## Note payload

The authoritative current evidence operations are:

1. Update existing note note_1788813777948_0983f1cb with
   12467fb2-6441-4d8b-9e54-fc2e3bed33a9 and the complete current Implementation
   Evidence payload in the sibling pending record
   20260910T220312Z--sc-1618--update-note--current-implementation-evidence.md.
2. Update existing note note_1788813793423_3e29f9cf with
   d5dca233-94a9-42c1-861d-ae9e424b7a2f and the complete current Verification
   Evidence payload in the sibling pending record
   20260910T220313Z--sc-1618--update-note--current-verification-evidence.md.

When replaying either update, fetch the target note first, preserve unrelated
content, search for the offline-sync tag before creating anything, and link the
result to task_1788808364914_0z4klpcjk and Shortcut story 1618. Do not create a
new evidence note unless the recorded target cannot be resolved and the replay
operation explicitly records that fallback.

## Given/When/Then acceptance criteria

1. Given RSProx emits v2 real observations and GAPs, when observations are
   partially written, requeued, and reconnected, then per-source observation
   identities remain unique/monotonic and source-scoped loss ranges are exact.
2. Given Micro6 receives v1 and v2 sources, when it assigns ticks, trims,
   retries, and checksums, then packet/capture identities remain unchanged and
   version-specific validation is enforced.
3. Given ChromaScape receives an identical retry, when the batch is already
   committed, then ACK is returned without duplicate packet rows.
4. Given an observation identity is reused with changed content, when ingestion
   validates it, then the transaction rolls back and returns a permanent
   identity error.
5. Given configured omissions and concurrent drops, when a batch is rejected
   before transport ownership, then the immutable diagnostic snapshot is
   restored exactly once and subsequent drops belong to the next batch.
6. Given a truncated replay interval, when any subinterval is recovered, then
   only that subinterval warning is removed and uncovered portions remain
   navigable.
7. Given v1 recordings or legacy Kryo clients, when v2 support is deployed, then
   historical data and legacy behavior remain usable.
8. Given hostile or oversized decoded text, when the debugger renders it, then
   it is escaped/bounded and the browser remains responsive.

All eight criteria remain open for tracker completion until the live/runtime
evidence is performed.

## Implementation checklist

All items remain unchecked in Felix during this outage:

- Replay this record idempotently after Felix recovery; resolve the existing
  shortcut:sc-1618 task.
- Persist architecture, ERD, API contract, scope, acceptance, implementation,
  and test artifacts; pass planning -> spec_ready without waivers.
- Repair and compile RSProx broadcaster/codec/transcriber/socket integration.
- Add RSProx v2 producer and GAP/requeue/socket-writer coverage.
- Add Micro6 v1/v2 identity validation and source lifecycle handling.
- Implement Micro6 batch-owned diagnostics, range aggregation, checksum, retry,
  and stale-session protection.
- Implement ChromaScape v1/v2 validation, next-unused migration, uniqueness,
  transactional persistence, and post-commit publication.
- Implement deterministic replay cursor/filter API and interval
  subtraction/recovery UI.
- Run authorized focused/native/live verification in the stated order.
- Create and link Implementation Evidence, then Verification Evidence only after
  their headings and evidence are complete.

## Test checklist

All items remain unchecked in Felix during this outage:

- RSProx v1/v2 codec golden round trips and malformed/oversized validation.
- RSProx broadcaster partial write, requeue identity, pending GAP flush, source
  isolation, bounds, and exact range tests.
- Micro6 v1/v2 bridge parsing, fragmented read/reconnect, stale socket, tick
  assignment, trim/retry/checksum, and source lifecycle tests.
- Micro6 diagnostics ownership, omissions/stripped bytes, concurrent drop, ACK
  ordering, terminal failure, malformed reset, and full plugin compile tests.
- ChromaScape DTO/checksum/ordering/session validation, clean upgrade migration,
  uniqueness conflict, rollback/ACK, cursor/filter/search, and post-commit
  WebSocket tests.
- Browser interval subtraction, middle/single-tick recovery, stale response,
  duplicate request, retry, pagination, escaping, and bounded rendering tests.
- Native live two-direction capture, restart/replay equality, clean reconnect,
  partial write, lost ACK, stale socket, overflow, and redaction checks.

## Conditional design artifacts

- Architecture flow: game traffic -> RSProx decrypt/redact/decode -> versioned
  length-prefixed local envelope -> Micro6 bounded buffer and tick/session
  binding -> schema-v3 TICK_BATCH -> ChromaScape transaction -> packet
  observations/live debugger/replay.
- Sequence artifact: RSProx observation(sequence, packetSequence) -> Micro6 tick
  assignment without identity rewrite -> ChromaScape validate/write/commit ->
  ACK PERSISTED -> post-commit broadcast -> cursor replay.
- ERD artifact: DEBUG_SESSION owns PACKET_OBSERVATION; identity is
  session_id/source_session_id/envelope_schema/observation_sequence.
- API artifact: schema-v3 packets, transport diagnostics, checksum, and bounded
  cursor replay filters are recorded in the existing plan/note.
- No new mockup is required; the existing debugger layout is extended.

## Checklist reconciliation

Reconcile exactly these Felix checklist groups, preserving unrelated items:

- Scope and Exclusions: preserve the scope and exclusions above; unchecked.
- Acceptance Criteria: preserve the eight Given/When/Then items above;
  unchecked.
- Implementation Checklist: use the ten exact items above; unchecked.
- Test Verification: use the seven exact test items above; unchecked.
- Regression Testing: run the native/live compatibility, reconnect, overflow,
  redaction, restart, and replay checks; unchecked.

The exact Shortcut story checklist also remains unchanged:

- 1619: RSProx post-redaction rsprox-packet-stream/v1 envelopes, bounds, gaps,
  cleanup, and legacy Kryo compatibility; unchecked.
- 1620: Micro6 schema-v3 TICK_BATCH, checksum, ACK/retry settlement,
  diagnostics, and thin overlay packaging; unchecked.
- 1621: Micro6 asynchronous packet client, bounded buffer, session lifecycle,
  and tick assignment; unchecked.
- 1622: ChromaScape schema-v3 validation, packet_observations
  migration/entity/repository, transactional persistence, and retry idempotency;
  unchecked.
- 1623: debugger Packets toggle/chip, live/replay grouping, filters,
  expandable rows, gap indicators, and bounded rendering; unchecked.
- 1624: authorized native suites plus live two-direction, lost-ACK, overflow,
  redaction, compatibility, restart, and replay checks; unchecked.
- 1625: paginated replay packet API and exclusion of packets from Go forwarding
  by default; unchecked.

No checked item is claimed by this offline record. Focused evidence is recorded
in the two sibling evidence records, but validation and live gates are still
required before checking items.

## State reconciliation

The recorded intended state order is planning -> spec_ready -> in_progress ->
in_review -> done. The existing task is already in_progress. Do not repeat the
started transition. After Felix recovery, first replay the pending evidence
updates and reconcile the plan/checklists; transition to in_review only if the
implementation gate validates. Transition to done only if Verification Evidence
and every acceptance/test item validate. No state transition is intended now.

## Shortcut reconciliation

Read C:\Dev\IdeaProjects\ChromaScape\.felix\shortcut-sync.json before any
Shortcut mutation. Mapping is planned 500000007, started 500000008, review
500000009, done 500000010; blocked/cancelled are null. Current story state is
500000008. Existing activity IDs 1637 and 1638 contain
[felix-sync:task_1788808364914_0z4klpcjk:planned] and
[felix-sync:task_1788808364914_0z4klpcjk:started].

Do not emit another planned/started marker. Emit the review marker only after a
successful Felix in_review transition and the done marker only after a
successful Felix done transition. This record has no intended Shortcut
transition or comment.

## Sync result

Pending. Felix MCP was unavailable, so no note update, task checklist change,
Felix state transition, Shortcut transition, or completion marker succeeded.
Replay oldest-first and record actual Felix note/task and Shortcut response IDs,
timestamps, and conflicts here. Never mark this record synced based on the
source commits alone.
