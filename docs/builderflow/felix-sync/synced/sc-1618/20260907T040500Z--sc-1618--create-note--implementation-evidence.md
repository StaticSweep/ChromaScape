---
schema_version: 1
offline_record_id: "offline-20260907T040500Z-sc1618-implementation-evidence-01"
sync_status: synced
project: "C:\\Dev\\IdeaProjects\\ChromaScape"
shortcut_story_id: 1618
shortcut_tag: "shortcut:sc-1618"
felix_task_id: "task_1788808364914_0z4klpcjk"
operation: create_note
target_note_id: "note_1788813777948_0983f1cb"
felix_note_id: "note_1788813777948_0983f1cb"
actual_felix_task_state: in_progress
actual_felix_spec_state: spec_ready
actual_shortcut_state_id: 500000008
actual_shortcut_state: "In Progress"
actual_shortcut_comment_ids: [1637, 1638]
synced_at: "2026-09-07T20:44:24.000Z"
title: "SC-1618 — Implementation Evidence — v2 packet pipeline and retry-safe diagnostics"
note_type: documentation
stable_tags: ["shortcut:sc-1618", "change:multi-repo", "evidence:implementation", "offline-sync:offline-20260907T040500Z-sc1618-implementation-evidence-01"]
parent_id: null
intended_entity_links:
  - entity_type: shortcut_story
    entity_id: "1618"
    entity_name: "DBG-PACKETS: Stream RSProx packets through Micro6 Tick Debugger to the ChromaScape packet viewer"
    link_strength: primary
task_selector:
  stable_tag: "shortcut:sc-1618"
  known_task_id: "task_1788808364914_0z4klpcjk"
created_at: "2026-09-07T04:05:00Z"
intended_felix_task_state: in_progress
intended_felix_spec_state: spec_ready
intended_shortcut_transition: "500000008 (In Progress) is already current; no new started transition is intended"
intended_shortcut_idempotency_marker: "[felix-sync:task_1788808364914_0z4klpcjk:started]"
---

## Task resolution

Resolve the existing Felix task for `shortcut:sc-1618` under canonical project
`C:\Dev\IdeaProjects\ChromaScape`; do not create a duplicate. Felix was unavailable after two
transport attempts with `SQLite package has not been found installed`, so this record is an offline
create-note operation. The task uses workflow `shortcut_delivery` and the single authoritative
classification tag `change:multi-repo`. Source: `https://app.shortcut.com/leeuwbot/story/1618`.

Scope implemented here is the SC-1618 v2 packet path across:

- `C:\Dev\IdeaProjects\rsprox`
- `C:\Dev\IdeaProjects\micro6`
- `C:\Dev\IdeaProjects\ChromaScape`

Exclusions remain packet injection/mutation, replay-to-game, raw unredacted packet logging,
rewriting the already-applied V39 migration, unrelated worktree changes, and claiming native or
live verification that did not run.

## Note payload

# Implementation Evidence — SC-1618

## Changed files

The following are the task-scoped files and additions. Existing unrelated dirty worktree changes
were preserved and are not included as SC-1618 evidence.

### RSProx (`C:\Dev\IdeaProjects\rsprox`)

- `proxy/src/main/kotlin/net/rsprox/proxy/packetstream/RsproxPacketEnvelope.kt`
- `proxy/src/main/kotlin/net/rsprox/proxy/packetstream/RsproxPacketStreamCodec.kt`
- `proxy/src/main/kotlin/net/rsprox/proxy/packetstream/PacketStreamBroadcaster.kt`
- `proxy/src/main/kotlin/net/rsprox/proxy/transcriber/LiveTranscriberSession.kt`
- `proxy/src/main/kotlin/net/rsprox/proxy/unix/UnixSocketConnection.kt`
- `proxy/src/test/kotlin/net/rsprox/proxy/packetstream/RsproxPacketStreamCodecTest.kt`

The v2 contract separates per-source observation `sequence` from real-packet
`packetSequence`, allocates identities at delivery insertion, retains identity through requeue,
keeps loss ranges source-scoped, merges failed GAP contents back into diagnostics, bounds queue
bytes/counts, and drains pending GAPs from the socket writer even when game traffic stops. The
legacy Kryo tunnel and explicit v1 down-conversion remain separate.

### Micro6 (`C:\Dev\IdeaProjects\micro6`)

- `runelite-client/src/main/java/net/runelite/client/plugins/debugrecorder/RsproxPacketEnvelope.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/debugrecorder/RsproxPacketStreamClient.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/debugrecorder/RsproxPacketBuffer.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/debugrecorder/TickBatchMessage.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/debugrecorder/TickBatchCanonicalChecksum.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/debugrecorder/DebugRecorderPlugin.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/debugrecorder/ChromaScapeWebSocketClient.java`
- `runelite-client/src/test/java/net/runelite/client/plugins/debugrecorder/RsproxPacketBridgeTest.java`
- `runelite-client/src/test/java/net/runelite/client/plugins/debugrecorder/TickBatchMessageTest.java`
- `runelite-client/src/test/java/net/runelite/client/plugins/debugrecorder/TickBatchCanonicalChecksumTest.java`
- `runelite-client/src/test/java/net/runelite/client/plugins/debugrecorder/DebugRecorderPluginTest.java`
- `runelite-client/src/test/java/net/runelite/client/plugins/debugrecorder/ChromaScapeWebSocketClientTest.java`

The producer/consumer identity fields and v1/v2 validation are explicit. Retry snapshots are
created from the serialized batch, own configured omissions and stripped-byte diagnostics, restore
pre-transport rejection exactly once, and leave accepted retries to the WebSocket owner. Source
ordering, checksum identity, stale-session protection, and terminal failure handling are covered
by focused tests. The later native focused dependency-graph run passed 120 tests; normal build and
capture-contract validation remain separate gates.

### ChromaScape (`C:\Dev\IdeaProjects\ChromaScape`)

- `src/main/java/com/chromascape/web/debug/tickdebugger/dto/TickBatchMessage.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/entity/PacketObservation.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/entity/PacketObservationRepository.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/entity/ReplayTickBatchReceipt.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/entity/ReplayTickBatchReceiptRepository.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/service/TickStreamIngestionService.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/service/TickBatchCanonicalChecksumService.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/websocket/TickStreamWebSocketHandler.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/controller/TickReplayController.java`
- `src/main/resources/db/migration/V43__sc1618_packet_observation_identity_v2.sql`
- `src/main/resources/static/js/debugger.js`
- `src/main/resources/static/css/debugger.css`
- `docs/TICK_MESSAGE_FORMAT.md`
- focused DTO, ingestion, checksum, WebSocket, durability, replay, and browser tests under
  `src/test/java/com/chromascape/web/debug/tickdebugger` and `src/test/js/debugger-replay-core.test.js`

V39 remains untouched. V43 backfills existing rows as v1, adds schema/observation identity, makes
capture identity nullable for v2 GAPs, and defines source/session/schema-aware uniqueness. V44
adds the v2 real-versus-GAP and identity constraints without modifying V39 or V43. Ingestion and
replay preserve both identities, delayed v2 GAPs do not invalidate ordinary ordering, cursors
match their deterministic query order, and recovery subtracts only the interval actually loaded.

## Tests

- RSProx focused packet-stream run in the earlier implementation pass: `:proxy:test
  --tests 'net.rsprox.proxy.packetstream.*'`, 14 tests passed. A later rerun reached Gradle but
  failed before test execution while scanning DrvFs build directories with `Cannot allocate memory`.
- ChromaScape focused Java run in the earlier implementation pass: Java 17 with
  `-Psc967VectorsPath=/mnt/c/Dev/IdeaProjects/contracts/sc967/ed25519-vectors.json`, 122 tests
  passed across DTO, ingestion, checksum, WebSocket, replay, and durability coverage. A later
  Java 17 rerun stopped in `processResources` because DrvFs could not read
  `src/main/resources/db`; this was an environment failure before the tests ran.
- Browser recovery core: `node --test src/test/js/debugger-replay-core.test.js`, 7 passed.
- Scoped `git diff --check` passed for the new Micro6 and ChromaScape task changes in the earlier
  verification pass.

## Current verification update (2026-09-07)

The following results were produced against the current worktree and no SC-1618 source files
changed after the corresponding successful runs:

- RSProx focused packet-stream task: :proxy:test --tests net.rsprox.proxy.packetstream.*;
  14/14 passed. Report: rsprox\proxy\build\test-results\test\TEST-net.rsprox.proxy.packetstream.RsproxPacketStreamCodecTest.xml.
- Micro6 native focused full dependency-graph/test run: 120/120 passed across
  RsproxPacketBridgeTest (24), DebugRecorderPluginTest (27), TickBatchMessageTest (12),
  TickBatchCanonicalChecksumTest (10), and ChromaScapeWebSocketClientTest (47).
- ChromaScape focused Java/Testcontainers run: 128/128 passed. This includes the disposable
  PostgreSQL migration/constraint tests (2) and durable batch/ACK tests (10), plus DTO,
  ingestion, WebSocket, replay, and cursor/filter coverage. Reports are under
  ChromaScape\build\test-results\test.
- Browser recovery core: node --test src/test/js/debugger-replay-core.test.js; 7/7 passed.
- Full existing ChromaScape JavaScript suite: node --test src/test/js/*.test.js; 227/227 passed.
  The debugger page wiring regression is included in that run.
- Read-only HTTP inspection of http://localhost:8090/debugger returned 200 and exposed the
  debugger CSS/scripts and Packets control. JavaScript syntax checks passed.
- Scoped git diff --check passed for the task-owned RSProx, Micro6, and ChromaScape paths.

Current source fingerprints associated with these results:

RSProx HEAD 0d678a1ea029d21ec988efe751a3096fd68e3605
  proxy/src/main/kotlin/net/rsprox/proxy/packetstream/RsproxPacketEnvelope.kt
    1A77FA8247A6D78F7DBDCDAD05472B4509F20D4AD456FC667FFC800B9CF84D91
  proxy/src/main/kotlin/net/rsprox/proxy/packetstream/RsproxPacketStreamCodec.kt
    3316DE6F046274BFCBDA1D690A7347937B66405DB5BB4391FF35FE8B97023E23
  proxy/src/main/kotlin/net/rsprox/proxy/packetstream/PacketStreamBroadcaster.kt
    AEBABA2EC7F40E83556D728FB593CDE391482A9CD0E0218232C227EC5A6D7804
  proxy/src/main/kotlin/net/rsprox/proxy/transcriber/LiveTranscriberSession.kt
    7E27944072CAA02417A12F5F27DF5D17C7438A645B013C1AD208F03FDD2140CD
  proxy/src/main/kotlin/net/rsprox/proxy/unix/UnixSocketConnection.kt
    E35FA36655788153FD6AD3B6A278ECB56AB662687598FD290653B7B4343B4353
  proxy/src/test/kotlin/net/rsprox/proxy/packetstream/RsproxPacketStreamCodecTest.kt
    00BF92D9EA166A43EEEB15F6440982A244FBF87C8B1283CAF28CD7B32BB01BA1

Micro6 HEAD a78f237f81d3ca2c1655dce71d35512749c9a72a
  runelite-client/src/main/java/net/runelite/client/plugins/debugrecorder/RsproxPacketEnvelope.java
    11A97D50C851CBF22F03090F4488E9E5A154D3A00829CED433682AA561E1D036
  runelite-client/src/main/java/net/runelite/client/plugins/debugrecorder/RsproxPacketStreamClient.java
    C38C12666475C223CE9D64B6916059E0B2E69326F95B1FC3FD77BABDFE74C7BE
  runelite-client/src/main/java/net/runelite/client/plugins/debugrecorder/RsproxPacketBuffer.java
    6E6084B45368B7270FEAA210ED8874AB0442355F3F844316836D4C10D391793B
  runelite-client/src/main/java/net/runelite/client/plugins/debugrecorder/DebugRecorderPlugin.java
    DB99682BE687142F37C393C10DBF09E638282A2BEE5D1D350AD45043DA8E7B05
  runelite-client/src/main/java/net/runelite/client/plugins/debugrecorder/TickBatchMessage.java
    4300953032DBD244C3276E27CA4F2536BD574320A02BA63F39A0122AD5CB403B
  runelite-client/src/main/java/net/runelite/client/plugins/debugrecorder/TickBatchCanonicalChecksum.java
    A00A435CEB049BFE6DFE1D4309CF4B5073769767DE0FF6E9609D1353DC054F90
  runelite-client/src/main/java/net/runelite/client/plugins/debugrecorder/ChromaScapeWebSocketClient.java
    C9550A7DB5EF0030F6126CDF7462BAE0E65C114D8E02A73547A88021ACA304CD
  runelite-client/src/test/java/net/runelite/client/plugins/debugrecorder/RsproxPacketBridgeTest.java
    01ADF426C767783F54BC886845DF963810E7D4493630F7B43148B8A527CDDFD8
  runelite-client/src/test/java/net/runelite/client/plugins/debugrecorder/DebugRecorderPluginTest.java
    558C3B1EB2C0A01C879347E8DEBD77D63132C1001ECDF3FFF9992DF65816A8F6
  runelite-client/src/test/java/net/runelite/client/plugins/debugrecorder/TickBatchMessageTest.java
    3C23BA7AA55376D2730DC329F6590BFAB6C75A43D2EEA96868541A3C2C8E4F5F
  runelite-client/src/test/java/net/runelite/client/plugins/debugrecorder/TickBatchCanonicalChecksumTest.java
    F3FF70B53777C500B052EC2091B56E94CE16960A4002CA857BB71FEA4CBB2784
  runelite-client/src/test/java/net/runelite/client/plugins/debugrecorder/ChromaScapeWebSocketClientTest.java
    8E191B7FB25160C803F907E728137317855F1EBA852E93C57771EF6AE5876F63

ChromaScape HEAD 471262bfeefa0c1d0a91a367227a3fd78586814e
  src/main/java/com/chromascape/web/debug/tickdebugger/dto/TickBatchMessage.java
    4D35EDF2AD81D7BE28B7CA705BBF76D97082E4722B536E097B20A933F44B7D51
  src/main/java/com/chromascape/web/debug/tickdebugger/entity/PacketObservation.java
    18EC1128801676C0D610A0B7F6956992B7EBD5D9C3856DDDBBC9E08A2CCA1C4B
  src/main/java/com/chromascape/web/debug/tickdebugger/entity/PacketObservationRepository.java
    C73829DE67C8AD4211AAB20F41937CF480050314B24F199321413ECCC166EF53
  src/main/java/com/chromascape/web/debug/tickdebugger/entity/ReplayTickBatchReceipt.java
    B80B98BBB9DAB1D6E97DED1511F648F14E63CC780D01DD24DCC3FC1AC0289F46
  src/main/java/com/chromascape/web/debug/tickdebugger/entity/ReplayTickBatchReceiptRepository.java
    6856A58D1D4D3FE96DCFA752CE808F6E89A4AA7C4D6A4EC8D10CB43DECF01B9D
  src/main/java/com/chromascape/web/debug/tickdebugger/service/TickStreamIngestionService.java
    308EBCC1A10F9A7C6E34B96C9581E1461BB72DC041E82453476A345517F243A2
  src/main/java/com/chromascape/web/debug/tickdebugger/service/TickBatchCanonicalChecksumService.java
    D1ECFFF89D77B4DE569AFDA97DAD64D8B69F182AB2586D526340A2C96B85BC4A
  src/main/java/com/chromascape/web/debug/tickdebugger/websocket/TickStreamWebSocketHandler.java
    556DBEA340B294EFB1EF106DEE870356B809C186EE5F8D3756DE713FB3126A3A
  src/main/java/com/chromascape/web/debug/tickdebugger/controller/TickReplayController.java
    DC146F5033C604552BDFD2BB28FD21935766DF6DE9D1E2D4BAC70A0E8F3AAE7F
  src/main/resources/db/migration/V43__sc1618_packet_observation_identity_v2.sql
    1A76F7823285BCD39F6DAB7C9E79EBFC9A84386191C815CACBF3B0146C200003
  src/main/resources/db/migration/V44__sc1618_packet_observation_identity_constraints.sql
    A6CE303806F24B373984DCC0399DF21F23EE7E86CE6870178249225779083F66
  src/main/resources/static/js/debugger.js
    3C9597F84EB62295F164ED2D5C36F0A71F1C1B86AD73DBB5556B9D50FF89941B
  src/main/resources/static/js/debugger-replay-core.js
    6FDF62183DE322A388C4AC0E5011304C20598E57B1DEE80ACF20DA614614537A
  src/main/resources/static/css/debugger.css
    54E35CA202380FC198694A36DBC104F7F439DAF461E2F0E9B65B0CBA3E1657F8
  docs/TICK_MESSAGE_FORMAT.md
    E4A22AFAD69775BF27F3B4853BBE9DEFA147AC6D1579285AEBAE6C8E2CEA84CC
  src/test/java/com/chromascape/web/debug/tickdebugger/dto/TickBatchPacketsTest.java
    301716B912E3F20211B1E5CD460287A8C33ED25ADF51EDE80EBE08023D2FB0EB
  src/test/java/com/chromascape/web/debug/tickdebugger/dto/TickBatchMessageTest.java
    97874441E2E78CDDEF6BAB11C4CD50BF4E68733FE71E934954ECFAA9096528D7
  src/test/java/com/chromascape/web/debug/tickdebugger/service/TickStreamIngestionServiceTest.java
    B2AD46B6FE8047750026D729C5C595FC576F7599904F7A6410DBD1AFF38BEBDA
  src/test/java/com/chromascape/web/debug/tickdebugger/websocket/TickStreamWebSocketHandlerTest.java
    A1A5439D46953FA0531348EBE71223028142E38A7C7AE1EE03BD5A00555E1E20
  src/test/java/com/chromascape/web/debug/tickdebugger/controller/TickReplayControllerTest.java
    B52B4DF052AFF9EBA391935120427D6BD153587D238FEBD1630743E1B135DF59
  src/test/java/com/chromascape/web/debug/tickdebugger/integration/TickBatchDurabilityIntegrationTest.java
    FDD2886AAA574BC346E3A7333326CA1DDC2E2C2AF3E0D64AB1C77484D8833D0F
  src/test/java/com/chromascape/web/debug/tickdebugger/integration/PacketObservationMigrationIntegrationTest.java
    7AB3289AC6E76144184E5811177FEFA2B36F8525CE43DB183EE8317A499F04BA
  src/test/js/debugger-replay-core.test.js
    B6A7CE543241EF92C0E6A4AA638A6BA98ACBE1AF84A157B4B30165BC3CE8477D

## Historical remaining work at offline-record creation

- Native Windows Micro6 compile and focused tests remain unverified. The authorized command was
  attempted, but `cmd.exe` could not start through WSL because `UtilAcceptVsock:271:
  accept4 failed 110`. No WSL Micro6 fallback was used.
- PostgreSQL migration/uniqueness/retry/cursor/search verification and live RSProx-to-Micro6-to-
  ChromaScape integration remain unrun.
- Clean reconnect, partial-write, lost-ACK, stale-socket, queue-overflow, restart/replay equality,
  and normal Windows build checks remain required before review/done.
- Felix task resolution, note creation, checklist reconciliation, state transitions, and Shortcut
  synchronization must be replayed idempotently after Felix recovers.

## Remaining work

- Micro6 focused native tests passed, but normal :client:build and :client:verifyCaptureContract
  are blocked by the unrelated SC-968 identity mismatch:
  1.0.0-sc1452-983d8efa4276 != 1.0.0-sc968-7c846c927520.
- ChromaScape normal build remains blocked by Spotless violations in unrelated pre-existing
  worktree files. No spotlessApply was run.
- The in-app browser runtime failed to initialize twice with stdout_eof and kernel exit code -1;
  actual interactive page/screenshot verification is still unrun.
- The live RSProx -> Micro6 -> ChromaScape acceptance pass remains unrun because runtime launch
  authorization was not established. This leaves clean reconnect, stale-socket, partial-write,
  lost-ACK, queue-overflow, >10,000-packet continuation, and restart/replay identity comparison
  open. The required live database-backed pagination/search no-omission exercise is also open.
- The disposable PostgreSQL migration and durability subset passed 128/128; it does not replace
  the live/restart and full browser/runtime gates above.

## Checklist reconciliation

For the existing SC-1618 task, reconcile the following exact checklist groups without overwriting
unrelated items. Mark only evidence-backed items checked after Felix validation:

- `Scope and Exclusions`: preserve the multi-repository scope and exclusions in the plan note.
- `Acceptance Criteria`: retain the eight Given/When/Then criteria in the authoritative plan.
- `Implementation Checklist`: RSProx producer, Micro6 v1/v2 and diagnostics ownership, ChromaScape
  persistence/replay, range aggregation, and browser recovery are implemented in the scoped files;
  native/live gates and Felix evidence linking remain unchecked.
- `Test Verification`: focused RSProx/Chroma/browser evidence exists; native Micro6, PostgreSQL,
  and live integration evidence remains unchecked.
- `Regression Testing`: no full normal-Windows/live regression claim is made.

## State reconciliation

After Felix recovers, resolve the existing task by `shortcut:sc-1618`, replay this note using the
`offline-sync:offline-20260907T040500Z-sc1618-implementation-evidence-01` tag, validate the existing
plan, and transition `planning -> spec_ready -> in_progress` only after the required artifacts pass
validation. Do not transition to `in_review` until the native and live checks are complete and this
note is linked as Implementation Evidence.

## Historical Shortcut reconciliation at offline-record creation

Shortcut remains `To Do` (`500000007`). Do not post the started marker until the Felix
`in_progress` transition succeeds. Intended marker: `[felix-sync:<felix-task-id>:started]`.
Check for that marker before posting; no Shortcut mutation was attempted by this offline record.

## Current Shortcut reconciliation

Shortcut story SC-1618 is currently In Progress (500000008). Existing comments 1637 and 1638
contain the planned and started markers for Felix task
task_1788808364914_0z4klpcjk. No additional started comment or state mutation is needed.

## Sync result

Synced. Felix task task_1788808364914_0z4klpcjk was resolved without creating a duplicate. The
Implementation Evidence note was created as note_1788813777948_0983f1cb at
2026-09-07T20:42:57.000Z, updated with the corrected current evidence at
2026-09-07T20:44:24.000Z, and linked to the task. Shortcut was already In Progress
(500000008); existing comments 1637 and 1638 contain the planned and started idempotency
markers, so no duplicate comment or state mutation was issued.
