package com.chromascape.web.debug.tickdebugger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.debug.tickdebugger.entity.PacketObservation;
import com.chromascape.web.debug.tickdebugger.entity.PacketObservationRepository;
import com.chromascape.web.debug.tickdebugger.entity.ReplayTickBatchReceipt;
import com.chromascape.web.debug.tickdebugger.entity.ReplayTickBatchReceiptRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickOverlayRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.debug.tickdebugger.entity.TimelineMarkerRepository;
import com.chromascape.web.integration.leeuwbot.config.LeeuwBotConfig;
import com.chromascape.web.integration.leeuwbot.service.LeeuwBotBridgeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Tests for {@link TickStreamIngestionService} covering questHelper persistence and tolerance. */
@ExtendWith(MockitoExtension.class)
class TickStreamIngestionServiceTest {

  @Mock private DebugSessionService sessionService;
  @Mock private DebugSessionRepository sessionRepository;
  @Mock private TickSnapshotRepository snapshotRepository;
  @Mock private TickLogRepository logRepository;
  @Mock private TickOverlayRepository overlayRepository;
  @Mock private TimelineMarkerRepository markerRepository;
  @Mock private LeeuwBotBridgeService leeuwBotBridgeService;
  @Mock private LeeuwBotConfig leeuwBotConfig;
  @Mock private ReplayTickBatchReceiptRepository batchReceiptRepository;
  @Mock private PacketObservationRepository packetObservationRepository;

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  private TickStreamIngestionService service;

  @BeforeEach
  void setUp() {
    service =
        new TickStreamIngestionService(
            sessionService,
            snapshotRepository,
            logRepository,
            overlayRepository,
            markerRepository,
            objectMapper,
            leeuwBotBridgeService,
            leeuwBotConfig);
  }

  private TickBatchMessage strictV2Batch(String sessionId, long tick) {
    TickBatchMessage batch = new TickBatchMessage();
    batch.setSchemaVersion(2);
    batch.setSessionId(sessionId);
    batch.setTick(tick);
    batch.setTimestamp("2025-06-15T12:00:00Z");

    Map<String, Object> playerState = new HashMap<>();
    playerState.put("x", 3200);
    playerState.put("z", 3200);
    batch.setPlayerState(playerState);

    batch.setInventory(List.of());
    batch.setEquipment(List.of());
    batch.setThreats(List.of());
    batch.setProjectiles(List.of());
    batch.setCooldowns(new HashMap<>());
    batch.setLogs(new ArrayList<>());
    batch.setOverlays(new ArrayList<>());
    batch.setNpcs(List.of());
    batch.setGameObjects(List.of());
    batch.setGroundObjects(List.of());
    batch.setGroundItems(List.of());
    batch.setPlayers(List.of());
    batch.setWidgets(List.of());

    Map<String, Object> quest = new HashMap<>();
    quest.put("active", false);
    quest.put("changed", false);
    batch.setQuest(quest);

    return batch;
  }

  private TickStreamIngestionService receiptService() {
    return new TickStreamIngestionService(
        sessionService,
        snapshotRepository,
        logRepository,
        overlayRepository,
        markerRepository,
        objectMapper,
        null,
        null,
        null,
        batchReceiptRepository);
  }

  @Test
  void closedSessionsRejectNewEvidenceBeforeAnyWrites() {
    for (var state :
        List.of(
            com.chromascape.web.debug.tickdebugger.entity.DebugSessionLifecycleState.FINALIZED,
            com.chromascape.web.debug.tickdebugger.entity.DebugSessionLifecycleState.FAILED)) {
      String sessionId = "closed-" + state;
      DebugSession session =
          new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 8L);
      session.setLifecycleState(state);
      // Also cover legacy terminal rows that lack an end time.
      session.setEndTick(322L);
      when(sessionService.getSessionForIngestion(sessionId)).thenReturn(Optional.of(session));
      TickBatchMessage batch = strictV2Batch(sessionId, 323);
      batch.setBatchId(UUID.randomUUID().toString());
      applyBatchChecksum(batch);
      assertThat(
              assertThrows(
                  IllegalArgumentException.class, () -> receiptService().processTickBatch(batch)))
          .hasMessageContaining("BATCH_SESSION_CLOSED");
      assertEquals(322L, session.getEndTick());
    }
    verify(batchReceiptRepository, never())
        .insertIfAbsent(any(), any(), anyLong(), any(), any(), any());
    org.mockito.Mockito.verifyNoInteractions(
        snapshotRepository, logRepository, overlayRepository, markerRepository);
    verify(sessionService, never()).updateSessionTickBounds(any(), anyLong());
  }

  @Test
  void exactCommittedRetryRemainsAcknowledgedAfterFinalization() {
    String sessionId = "closed-retry";
    DebugSession session =
        new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 8L);
    session.setEndTime(LocalDateTime.now());
    session.setLifecycleState(
        com.chromascape.web.debug.tickdebugger.entity.DebugSessionLifecycleState.FINALIZED);
    when(sessionService.getSessionForIngestion(sessionId)).thenReturn(Optional.of(session));
    TickBatchMessage batch = strictV2Batch(sessionId, 322);
    batch.setBatchId(UUID.randomUUID().toString());
    applyBatchChecksum(batch);
    when(batchReceiptRepository.findBySessionIdAndBatchId(
            sessionId, UUID.fromString(batch.getBatchId())))
        .thenReturn(
            Optional.of(receipt(sessionId, batch.getBatchId(), 322, batch.getPayloadChecksum())));
    assertEquals("PERSISTED", receiptService().processTickBatch(batch).status());
    verify(batchReceiptRepository, never())
        .insertIfAbsent(any(), any(), anyLong(), any(), any(), any());
    org.mockito.Mockito.verifyNoInteractions(
        snapshotRepository, logRepository, overlayRepository, markerRepository);
  }

  private void applyBatchChecksum(TickBatchMessage batch) {
    TickBatchCanonicalChecksumService checksumService =
        new TickBatchCanonicalChecksumService(objectMapper);
    JsonNode document = objectMapper.valueToTree(batch);
    removeNullFields((ObjectNode) document);
    ((ObjectNode) document).remove("payloadChecksum");
    batch.setPayloadChecksum(checksumService.checksum(document));
  }

  private void removeNullFields(ObjectNode object) {
    List<String> nullFields = new ArrayList<>();
    object
        .fields()
        .forEachRemaining(
            entry -> {
              if (entry.getValue() == null || entry.getValue().isNull()) {
                nullFields.add(entry.getKey());
              } else if (entry.getValue().isObject()) {
                removeNullFields((ObjectNode) entry.getValue());
              } else if (entry.getValue().isArray()) {
                entry
                    .getValue()
                    .forEach(
                        child -> {
                          if (child.isObject()) {
                            removeNullFields((ObjectNode) child);
                          }
                        });
              }
            });
    nullFields.forEach(object::remove);
  }

  private ReplayTickBatchReceipt receipt(
      String sessionId, String batchId, long tick, String checksum) {
    ReplayTickBatchReceipt receipt = new ReplayTickBatchReceipt();
    receipt.setSessionId(sessionId);
    receipt.setBatchId(UUID.fromString(batchId));
    receipt.setTick(tick);
    receipt.setPayloadChecksum(checksum);
    receipt.setSchemaName("tick-batch/v2");
    receipt.setPersistedAt(LocalDateTime.now());
    return receipt;
  }

  @Test
  @DisplayName("receipt boundary returns PERSISTED after a successful commit")
  void receiptBoundary_returnsPersistedAfterCommit() {
    String sessionId = "receipt-success";
    String batchId = "00000000-0000-4000-8000-000000000101";
    when(sessionService.getSessionForIngestion(sessionId))
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));
    when(batchReceiptRepository.insertIfAbsent(
            eq(sessionId),
            eq(UUID.fromString(batchId)),
            eq(100L),
            any(),
            eq("tick-batch/v2"),
            any()))
        .thenReturn(1);

    TickBatchMessage batch = strictV2Batch(sessionId, 100);
    batch.setBatchId(batchId);
    applyBatchChecksum(batch);

    TickStreamIngestionService.TickBatchAcknowledgment acknowledgment =
        receiptService().processTickBatch(batch);

    assertEquals("PERSISTED", acknowledgment.status());
    assertEquals(batchId, acknowledgment.batchId());
    assertEquals(batch.getPayloadChecksum(), acknowledgment.payloadChecksum());
    verify(batchReceiptRepository)
        .insertIfAbsent(
            eq(sessionId),
            eq(UUID.fromString(batchId)),
            eq(100L),
            eq(batch.getPayloadChecksum()),
            eq("tick-batch/v2"),
            any());
  }

  @Test
  @DisplayName("an exact duplicate returns the existing durable acknowledgment")
  void receiptBoundary_exactDuplicateReturnsExistingAcknowledgment() {
    String sessionId = "receipt-duplicate";
    String batchId = "00000000-0000-4000-8000-000000000102";
    String checksum = "sha256:0000000000000000000000000000000000000000000000000000000000000000";
    when(sessionService.getSessionForIngestion(sessionId))
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));
    when(batchReceiptRepository.findBySessionIdAndBatchId(sessionId, UUID.fromString(batchId)))
        .thenReturn(Optional.of(receipt(sessionId, batchId, 100, checksum)));

    TickBatchMessage batch = strictV2Batch(sessionId, 100);
    batch.setBatchId(batchId);
    batch.setPayloadChecksum(checksum);
    // The duplicate must still be a valid wire payload before receipt lookup.
    applyBatchChecksum(batch);
    ReplayTickBatchReceipt existing = receipt(sessionId, batchId, 100, batch.getPayloadChecksum());
    when(batchReceiptRepository.findBySessionIdAndBatchId(sessionId, UUID.fromString(batchId)))
        .thenReturn(Optional.of(existing));

    TickStreamIngestionService.TickBatchAcknowledgment acknowledgment =
        receiptService().processTickBatch(batch);

    assertEquals(batchId, acknowledgment.batchId());
    assertEquals(batch.getPayloadChecksum(), acknowledgment.payloadChecksum());
    verify(batchReceiptRepository, never())
        .insertIfAbsent(any(), any(), anyLong(), any(), any(), any());
    verify(snapshotRepository, never()).save(any());
  }

  @Test
  @DisplayName("same batch ID with a different checksum is rejected")
  void receiptBoundary_conflictingDuplicateIsRejected() {
    String sessionId = "receipt-conflict";
    String batchId = "00000000-0000-4000-8000-000000000103";
    when(sessionService.getSessionForIngestion(sessionId))
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));
    TickBatchMessage batch = strictV2Batch(sessionId, 100);
    batch.setBatchId(batchId);
    applyBatchChecksum(batch);
    when(batchReceiptRepository.findBySessionIdAndBatchId(sessionId, UUID.fromString(batchId)))
        .thenReturn(
            Optional.of(
                receipt(
                    sessionId,
                    batchId,
                    100,
                    "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")));

    assertThrows(IllegalArgumentException.class, () -> receiptService().processTickBatch(batch));
    verify(snapshotRepository, never()).save(any());
  }

  private TickBatchMessage.TickPacketEntry packetEntry(
      String sourceSessionId, long sequence, long tick, String protocolName) {
    return new TickBatchMessage.TickPacketEntry(
        "rsprox-packet/v1",
        sourceSessionId,
        sequence,
        "2026-09-04T18:12:34.567Z",
        4L,
        "SERVER_TO_CLIENT",
        81,
        protocolName,
        137,
        "NpcInfo",
        Map.of(),
        null,
        "DECODED",
        null,
        false,
        tick);
  }

  private TickBatchMessage.TickPacketEntry v2PacketEntry(
      String sourceSessionId,
      long observationSequence,
      Long packetSequence,
      long tick,
      String protocolName,
      String status,
      boolean synthetic,
      Map<String, Object> decoded) {
    return v2PacketEntry(
        sourceSessionId,
        observationSequence,
        packetSequence,
        tick,
        protocolName,
        status,
        synthetic,
        decoded,
        "2026-09-04T18:12:34.567Z",
        null);
  }

  private TickBatchMessage.TickPacketEntry v2PacketEntry(
      String sourceSessionId,
      long observationSequence,
      Long packetSequence,
      long tick,
      String protocolName,
      String status,
      boolean synthetic,
      Map<String, Object> decoded,
      String capturedAt,
      String sanitizedPayloadBase64) {
    return new TickBatchMessage.TickPacketEntry(
        "rsprox-packet/v2",
        sourceSessionId,
        observationSequence,
        packetSequence,
        capturedAt,
        4L,
        "SERVER_TO_CLIENT",
        "GAP".equals(status) ? 0 : 81,
        protocolName,
        "GAP".equals(status) ? 0 : 137,
        "GAP".equals(status) ? "StreamGap" : "NpcInfo",
        decoded,
        sanitizedPayloadBase64,
        status,
        null,
        synthetic,
        tick);
  }

  private TickBatchMessage packetBatch(
      String sessionId, String batchId, long tick, TickBatchMessage.TickPacketEntry... packets) {
    TickBatchMessage batch = strictV2Batch(sessionId, tick);
    batch.setSchemaVersion(3);
    batch.setBatchId(batchId);
    batch.setPackets(List.of(packets));
    applyBatchChecksum(batch);
    return batch;
  }

  @Test
  @DisplayName("Go forwarding keeps tick fields but excludes packet observations")
  void goForwardPayloadExcludesPackets() throws Exception {
    TickBatchMessage batch = strictV2Batch("forward-session", 100L);
    batch.setSchemaVersion(3);
    batch.setPackets(
        List.of(
            v2PacketEntry(
                "source-a",
                7L,
                4812L,
                100L,
                "NPC_INFO_LARGE",
                "DECODED",
                false,
                Map.of("hostileText", "<script>alert(1)</script>"))));

    JsonNode goPayload = objectMapper.readTree(service.goForwardPayload(batch));

    assertThat(goPayload.path("sessionId").asText()).isEqualTo("forward-session");
    assertThat(goPayload.path("tick").asLong()).isEqualTo(100L);
    assertThat(goPayload.path("playerState").path("x").asInt()).isEqualTo(3200);
    assertThat(goPayload.has("packets")).isFalse();
    assertThat(goPayload.toString()).doesNotContain("NPC_INFO_LARGE", "<script>");
    assertThat(batch.getPackets()).hasSize(1);
  }

  private void stubPacketSession(String sessionId) {
    lenient()
        .when(sessionService.getSessionForIngestion(sessionId))
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));
    org.springframework.test.util.ReflectionTestUtils.setField(
        service, "packetRepository", packetObservationRepository);
    lenient()
        .when(batchReceiptRepository.findBySessionIdAndBatchId(eq(sessionId), any(UUID.class)))
        .thenReturn(Optional.empty());
    lenient()
        .when(batchReceiptRepository.findBySessionIdAndTick(eq(sessionId), anyLong()))
        .thenReturn(Optional.empty());
    lenient()
        .when(
            batchReceiptRepository.insertIfAbsent(
                eq(sessionId), any(UUID.class), anyLong(), any(), eq("tick-batch/v2"), any()))
        .thenReturn(1);
  }

  @Test
  @DisplayName("schema 2 rejects non-empty packet lists")
  void schema2PacketsAreRejected() {
    String sessionId = "packet-schema-2";
    stubPacketSession(sessionId);
    TickBatchMessage batch = strictV2Batch(sessionId, 100L);
    batch.setBatchId("00000000-0000-4000-8000-000000000200");
    batch.setPackets(List.of(packetEntry("source-a", 1L, 100L, "PLAYER_INFO")));
    applyBatchChecksum(batch);

    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> service.processTickBatch(batch));
    assertEquals("SCHEMA_V2_PACKETS_UNSUPPORTED", failure.getMessage());
    verify(packetObservationRepository, never()).saveAndFlush(any(PacketObservation.class));
  }

  @Test
  @DisplayName("future schema versions are rejected even with an empty packet list")
  void futureSchemaVersionIsRejected() {
    String sessionId = "packet-future-schema";
    stubPacketSession(sessionId);
    TickBatchMessage batch = strictV2Batch(sessionId, 100L);
    batch.setSchemaVersion(5);
    batch.setBatchId("00000000-0000-4000-8000-000000000212");
    applyBatchChecksum(batch);

    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> service.processTickBatch(batch));
    assertEquals("Unsupported schemaVersion; expected 2, 3, or 4", failure.getMessage());
  }

  @Test
  @DisplayName("packet timestamp syntax is validated before persistence")
  void packetTimestampSyntaxIsValidated() {
    String sessionId = "packet-invalid-timestamp";
    stubPacketSession(sessionId);
    TickBatchMessage batch =
        packetBatch(
            sessionId,
            "00000000-0000-4000-8000-000000000213",
            100L,
            v2PacketEntry(
                "source-a",
                1L,
                4812L,
                100L,
                "NPC_INFO_LARGE",
                "DECODED",
                false,
                Map.of(),
                "not-an-instant",
                null));

    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> service.processTickBatch(batch));
    assertEquals("PACKET_TIMESTAMP_INVALID", failure.getMessage());
    verify(packetObservationRepository, never()).saveAndFlush(any(PacketObservation.class));
  }

  @Test
  @DisplayName("packet raw payload must be valid Base64")
  void packetRawPayloadSyntaxIsValidated() {
    String sessionId = "packet-invalid-raw";
    stubPacketSession(sessionId);
    TickBatchMessage batch =
        packetBatch(
            sessionId,
            "00000000-0000-4000-8000-000000000214",
            100L,
            v2PacketEntry(
                "source-a",
                1L,
                4812L,
                100L,
                "NPC_INFO_LARGE",
                "DECODED",
                false,
                Map.of(),
                "2026-09-04T18:12:34.567Z",
                "not-base64!"));

    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> service.processTickBatch(batch));
    assertEquals("PACKET_PAYLOAD_INVALID", failure.getMessage());
  }

  @Test
  @DisplayName("decoded packet key counts are bounded")
  void decodedPacketKeyCountIsBounded() {
    String sessionId = "packet-key-limit";
    stubPacketSession(sessionId);
    Map<String, Object> decoded = new LinkedHashMap<>();
    for (int index = 0; index < 2050; index++) {
      decoded.put("key-" + index, index);
    }
    TickBatchMessage batch =
        packetBatch(
            sessionId,
            "00000000-0000-4000-8000-000000000215",
            100L,
            v2PacketEntry(
                "source-a", 1L, 4812L, 100L, "NPC_INFO_LARGE", "DECODED", false, decoded));

    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> service.processTickBatch(batch));
    assertEquals("PACKET_DECODED_TOO_MANY_KEYS", failure.getMessage());
  }

  @Test
  @DisplayName("decoded raw bytes count toward the batch packet-byte ceiling")
  void decodedRawBytesCountTowardBatchLimit() {
    String sessionId = "packet-byte-limit";
    stubPacketSession(sessionId);
    String raw = java.util.Base64.getEncoder().encodeToString(new byte[60_000]);
    List<TickBatchMessage.TickPacketEntry> packets = new ArrayList<>();
    for (int index = 0; index < 9; index++) {
      packets.add(
          v2PacketEntry(
              "source-a",
              index,
              4812L + index,
              100L,
              "NPC_INFO_LARGE",
              "DECODED",
              false,
              Map.of(),
              "2026-09-04T18:12:34.567Z",
              raw));
    }
    TickBatchMessage batch = strictV2Batch(sessionId, 100L);
    batch.setSchemaVersion(3);
    batch.setBatchId("00000000-0000-4000-8000-000000000216");
    batch.setPackets(packets);
    applyBatchChecksum(batch);

    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> service.processTickBatch(batch));
    assertEquals("PACKET_BATCH_BYTES_EXCEEDED", failure.getMessage());
  }

  @Test
  @DisplayName("packet retry with altered content fails as an identity conflict")
  void packets_reusedSequenceWithAlteredContentConflicts() {
    String sessionId = "packet-conflict";
    String batchId = "00000000-0000-4000-8000-000000000201";
    stubPacketSession(sessionId);

    PacketObservation stored = new PacketObservation(sessionId, 100L, "proxy-session", 4812L);
    stored.setDirection("SERVER_TO_CLIENT");
    stored.setOpcode(81);
    stored.setProtocolName("NPC_INFO_LARGE");
    stored.setEncodedSize(137);
    stored.setDecodedType("NpcInfo");
    stored.setDecodedPayload(objectMapper.valueToTree(Map.of()));
    stored.setDecodeStatus("DECODED");
    stored.setSynthetic(false);
    when(packetObservationRepository
            .findBySessionIdAndSourceSessionIdAndPacketSchemaAndPacketSequence(
                sessionId, "proxy-session", "rsprox-packet/v1", 4812L))
        .thenReturn(Optional.of(stored));

    TickBatchMessage batch =
        packetBatch(
            sessionId, batchId, 100, packetEntry("proxy-session", 4812L, 100L, "PLAYER_INFO"));

    assertThrows(IllegalArgumentException.class, () -> service.processTickBatch(batch));
  }

  @Test
  @DisplayName("packet retry with altered tick fails as an identity conflict")
  void packets_reusedSequenceWithAlteredTickConflicts() {
    String sessionId = "packet-tick-conflict";
    String batchId = "00000000-0000-4000-8000-000000000202";
    stubPacketSession(sessionId);

    PacketObservation stored = new PacketObservation(sessionId, 100L, "proxy-session", 4813L);
    stored.setDirection("SERVER_TO_CLIENT");
    stored.setOpcode(81);
    stored.setProtocolName("NPC_INFO_LARGE");
    stored.setEncodedSize(137);
    stored.setDecodedType("NpcInfo");
    stored.setDecodedPayload(objectMapper.valueToTree(Map.of()));
    stored.setDecodeStatus("DECODED");
    stored.setSynthetic(false);
    when(packetObservationRepository
            .findBySessionIdAndSourceSessionIdAndPacketSchemaAndPacketSequence(
                sessionId, "proxy-session", "rsprox-packet/v1", 4813L))
        .thenReturn(Optional.of(stored));

    TickBatchMessage batch =
        packetBatch(
            sessionId, batchId, 100, packetEntry("proxy-session", 4813L, 101L, "NPC_INFO_LARGE"));

    assertThrows(IllegalArgumentException.class, () -> service.processTickBatch(batch));
  }

  @Test
  @DisplayName("packet retry with identical content is idempotent")
  void packets_identicalRetryIsIdempotent() {
    String sessionId = "packet-idempotent";
    String batchId = "00000000-0000-4000-8000-000000000203";
    stubPacketSession(sessionId);

    PacketObservation stored = new PacketObservation(sessionId, 100L, "proxy-session", 4814L);
    stored.setCapturedAt(
        java.time.LocalDateTime.ofInstant(
            java.time.Instant.parse("2026-09-04T18:12:34.567Z"), java.time.ZoneId.systemDefault()));
    stored.setCaptureDeltaMillis(4L);
    stored.setDirection("SERVER_TO_CLIENT");
    stored.setOpcode(81);
    stored.setProtocolName("NPC_INFO_LARGE");
    stored.setEncodedSize(137);
    stored.setDecodedType("NpcInfo");
    stored.setDecodedPayload(objectMapper.valueToTree(Map.of()));
    stored.setDecodeStatus("DECODED");
    stored.setSynthetic(false);
    when(packetObservationRepository
            .findBySessionIdAndSourceSessionIdAndPacketSchemaAndPacketSequence(
                sessionId, "proxy-session", "rsprox-packet/v1", 4814L))
        .thenReturn(Optional.of(stored));

    TickBatchMessage batch =
        packetBatch(
            sessionId, batchId, 100, packetEntry("proxy-session", 4814L, 100L, "NPC_INFO_LARGE"));

    assertDoesNotThrow(() -> service.processTickBatch(batch));
    verify(packetObservationRepository, never()).save(any());
  }

  @Test
  @DisplayName("interleaved sources reject a duplicate hidden between other sources")
  void packets_interleavedDuplicateIsRejected() {
    String sessionId = "packet-interleaved";
    String batchId = "00000000-0000-4000-8000-000000000204";
    stubPacketSession(sessionId);

    TickBatchMessage batch =
        packetBatch(
            sessionId,
            batchId,
            100,
            packetEntry("source-a", 10L, 100L, "PLAYER_INFO"),
            packetEntry("source-b", 10L, 100L, "PLAYER_INFO"),
            packetEntry("source-b", 11L, 100L, "PLAYER_INFO"),
            packetEntry("source-a", 10L, 100L, "PLAYER_INFO"));

    assertThrows(IllegalArgumentException.class, () -> service.processTickBatch(batch));
  }

  @Test
  @DisplayName("interleaved sources accept independent low sequences")
  void packets_interleavedIndependentSequencesAreAccepted() {
    String sessionId = "packet-interleaved-ok";
    String batchId = "00000000-0000-4000-8000-000000000205";
    stubPacketSession(sessionId);

    TickBatchMessage batch =
        packetBatch(
            sessionId,
            batchId,
            100,
            packetEntry("source-a", 900L, 100L, "PLAYER_INFO"),
            packetEntry("source-b", 1L, 100L, "NPC_INFO_LARGE"),
            packetEntry("source-a", 901L, 100L, "PLAYER_INFO"),
            packetEntry("source-b", 2L, 100L, "NPC_INFO_LARGE"));

    assertDoesNotThrow(() -> service.processTickBatch(batch));
  }

  @Test
  @DisplayName("v2 real observations and GAPs preserve separate identities")
  void packets_v2RealAndGapAreAccepted() {
    String sessionId = "packet-v2-gap";
    String batchId = "00000000-0000-4000-8000-000000000206";
    stubPacketSession(sessionId);

    Map<String, Object> gapDetails =
        Map.of(
            "droppedPacketCount", 2L,
            "firstDroppedPacketSequence", 4813L,
            "lastDroppedPacketSequence", 4814L,
            "droppedPacketRanges",
                List.of(
                    Map.of("first", 4813L, "last", 4813L, "count", 1L),
                    Map.of("first", 4814L, "last", 4814L, "count", 1L)));
    TickBatchMessage batch =
        packetBatch(
            sessionId,
            batchId,
            100,
            v2PacketEntry(
                "source-a", 7L, 4812L, 100L, "NPC_INFO_LARGE", "DECODED", false, Map.of()),
            v2PacketEntry("source-a", 8L, null, 100L, "STREAM_GAP", "GAP", true, gapDetails),
            v2PacketEntry(
                "source-a", 9L, 4815L, 100L, "NPC_INFO_LARGE", "DECODED", false, Map.of()));

    assertDoesNotThrow(() -> service.processTickBatch(batch));
    verify(packetObservationRepository, times(3)).save(any(PacketObservation.class));
    verify(packetObservationRepository).flush();
  }

  @Test
  @DisplayName("delayed v2 GAP markers do not fail ordinary observation ordering")
  void packets_v2DelayedGapIsAccepted() {
    String sessionId = "packet-v2-delayed-gap";
    String batchId = "00000000-0000-4000-8000-000000000207";
    stubPacketSession(sessionId);

    TickBatchMessage batch =
        packetBatch(
            sessionId,
            batchId,
            100,
            v2PacketEntry("source-a", 0L, 100L, 100L, "PLAYER_INFO", "DECODED", false, Map.of()),
            v2PacketEntry("source-a", 2L, 102L, 100L, "NPC_INFO_LARGE", "DECODED", false, Map.of()),
            v2PacketEntry(
                "source-a",
                1L,
                null,
                100L,
                "STREAM_GAP",
                "GAP",
                true,
                Map.of(
                    "droppedPacketCount", 1L,
                    "firstDroppedPacketSequence", 101L,
                    "lastDroppedPacketSequence", 101L)));

    assertDoesNotThrow(() -> service.processTickBatch(batch));
  }

  @Test
  @DisplayName("v2 GAPs require a null capture identity and synthetic marker")
  void packets_v2GapIdentityIsValidated() {
    String sessionId = "packet-v2-invalid-gap";
    String batchId = "00000000-0000-4000-8000-000000000208";
    stubPacketSession(sessionId);

    TickBatchMessage batch =
        packetBatch(
            sessionId,
            batchId,
            100,
            v2PacketEntry("source-a", 0L, 101L, 100L, "STREAM_GAP", "GAP", true, Map.of()));

    assertThrows(IllegalArgumentException.class, () -> service.processTickBatch(batch));
    verify(packetObservationRepository, never()).save(any(PacketObservation.class));
  }

  @Test
  @DisplayName("v2 identity conflicts are permanent while exact retries are idempotent")
  void packets_v2IdentityConflictAndRetryAreHandled() {
    String sessionId = "packet-v2-identity";
    stubPacketSession(sessionId);

    PacketObservation stored =
        new PacketObservation(sessionId, 100L, "source-a", "rsprox-packet/v2", 7L, 4812L);
    stored.setCapturedAt(
        java.time.LocalDateTime.ofInstant(
            java.time.Instant.parse("2026-09-04T18:12:34.567Z"), java.time.ZoneId.systemDefault()));
    stored.setCaptureDeltaMillis(4L);
    stored.setDirection("SERVER_TO_CLIENT");
    stored.setOpcode(81);
    stored.setProtocolName("NPC_INFO_LARGE");
    stored.setEncodedSize(137);
    stored.setDecodedType("NpcInfo");
    stored.setDecodedPayload(objectMapper.valueToTree(Map.of()));
    stored.setDecodeStatus("DECODED");
    stored.setSynthetic(false);

    when(packetObservationRepository
            .findBySessionIdAndSourceSessionIdAndPacketSchemaAndObservationSequence(
                sessionId, "source-a", "rsprox-packet/v2", 7L))
        .thenReturn(Optional.of(stored));

    TickBatchMessage identical =
        packetBatch(
            sessionId,
            "00000000-0000-4000-8000-000000000209",
            100,
            v2PacketEntry(
                "source-a", 7L, 4812L, 100L, "NPC_INFO_LARGE", "DECODED", false, Map.of()));
    assertDoesNotThrow(() -> service.processTickBatch(identical));
    verify(packetObservationRepository, never()).saveAndFlush(any(PacketObservation.class));

    TickBatchMessage changed =
        packetBatch(
            sessionId,
            "00000000-0000-4000-8000-000000000210",
            100,
            v2PacketEntry(
                "source-a", 7L, 4813L, 100L, "NPC_INFO_LARGE", "DECODED", false, Map.of()));
    assertThrows(IllegalArgumentException.class, () -> service.processTickBatch(changed));
  }

  @Test
  @DisplayName(
      "packet tick validation rejects long overflow instead of accepting a wrapped distance")
  void packets_tickDistanceOverflowIsRejected() {
    String sessionId = "packet-tick-overflow";
    stubPacketSession(sessionId);

    TickBatchMessage batch =
        packetBatch(
            sessionId,
            "00000000-0000-4000-8000-000000000211",
            0L,
            packetEntry("source-a", 0L, Long.MIN_VALUE, "NPC_INFO_LARGE"));

    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> service.processTickBatch(batch));
    assertEquals("PACKET_TICK_OUT_OF_RANGE", failure.getMessage());
  }

  @Test
  @DisplayName("same tick with a different batch ID is rejected")
  void receiptBoundary_conflictingTickIsRejected() {
    String sessionId = "receipt-tick-conflict";
    String batchId = "00000000-0000-4000-8000-000000000104";
    TickBatchMessage batch = strictV2Batch(sessionId, 100);
    batch.setBatchId(batchId);
    applyBatchChecksum(batch);
    ReplayTickBatchReceipt winner =
        receipt(sessionId, "00000000-0000-4000-8000-000000000105", 100, batch.getPayloadChecksum());
    when(sessionService.getSessionForIngestion(sessionId))
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));
    when(batchReceiptRepository.findBySessionIdAndBatchId(sessionId, UUID.fromString(batchId)))
        .thenReturn(Optional.empty());
    when(batchReceiptRepository.findBySessionIdAndTick(sessionId, 100L))
        .thenReturn(Optional.of(winner));

    assertThrows(IllegalArgumentException.class, () -> receiptService().processTickBatch(batch));
    verify(snapshotRepository, never()).save(any());
  }

  @Test
  @DisplayName("snapshot failure aborts the receipt and produces no successful result")
  void receiptBoundary_snapshotFailureRollsBackAndDoesNotAcknowledge() {
    String sessionId = "receipt-rollback";
    String batchId = "00000000-0000-4000-8000-000000000106";
    when(sessionService.getSessionForIngestion(sessionId))
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));
    when(batchReceiptRepository.insertIfAbsent(any(), any(), anyLong(), any(), any(), any()))
        .thenReturn(1);
    doThrow(new IllegalStateException("snapshot failure")).when(snapshotRepository).save(any());
    TickBatchMessage batch = strictV2Batch(sessionId, 100);
    batch.setBatchId(batchId);
    applyBatchChecksum(batch);

    assertThrows(IllegalStateException.class, () -> receiptService().processTickBatch(batch));
    verify(batchReceiptRepository).insertIfAbsent(any(), any(), anyLong(), any(), any(), any());
  }

  @Test
  @DisplayName("processTickBatch persists questHelper when present")
  void processTickBatch_persistsQuestHelper() {
    String sessionId = "s1";
    when(sessionService.getSessionForIngestion(sessionId))
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

    TickBatchMessage batch = strictV2Batch(sessionId, 100);

    Map<String, Object> questHelper = new HashMap<>();
    questHelper.put("schemaVersion", 1);
    questHelper.put("source", "micro6.questhelper");
    questHelper.put("collectionMode", "questhelper");
    Map<String, Object> activeQuest = new HashMap<>();
    activeQuest.put("questKey", "cooks_assistant");
    activeQuest.put("questName", "Cook's Assistant");
    activeQuest.put("questState", "IN_PROGRESS");
    questHelper.put("activeQuest", activeQuest);
    questHelper.put("currentStep", new HashMap<>());
    questHelper.put("steps", List.of());
    questHelper.put("requirements", new HashMap<>());
    questHelper.put("dialogue", new HashMap<>());
    questHelper.put("targets", List.of());
    questHelper.put("overlays", List.of());
    questHelper.put("varbits", new HashMap<>());
    questHelper.put("events", List.of());
    Map<String, Object> debug = new HashMap<>();
    debug.put("collectionMode", "questhelper");
    questHelper.put("debug", debug);
    batch.setQuestHelper(questHelper);

    service.processTickBatch(batch);

    ArgumentCaptor<TickSnapshot> captor = ArgumentCaptor.forClass(TickSnapshot.class);
    verify(snapshotRepository).save(captor.capture());
    TickSnapshot saved = captor.getValue();

    assertThat(saved.getQuestHelper()).isNotNull();
    JsonNode qh = saved.getQuestHelper();
    assertThat(qh.path("collectionMode").asText()).isEqualTo("questhelper");
    assertThat(qh.path("activeQuest").path("questKey").asText()).isEqualTo("cooks_assistant");
  }

  @Test
  @DisplayName("processTickBatch allows missing questHelper")
  void processTickBatch_allowsMissingQuestHelper() {
    String sessionId = "s2";
    when(sessionService.getSessionForIngestion(sessionId))
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

    TickBatchMessage batch = strictV2Batch(sessionId, 200);
    // questHelper intentionally omitted (left null)

    assertDoesNotThrow(() -> service.processTickBatch(batch));

    ArgumentCaptor<TickSnapshot> captor = ArgumentCaptor.forClass(TickSnapshot.class);
    verify(snapshotRepository).save(captor.capture());
    TickSnapshot saved = captor.getValue();

    assertThat(saved.getQuestHelper()).isNull();
  }

  @Test
  @DisplayName("Strict v2 accepts nested playerState.position.x/y/plane")
  void strictV2_acceptsNestedPosition() {
    String sessionId = "s3";
    when(sessionService.getSessionForIngestion(sessionId))
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

    TickBatchMessage batch = new TickBatchMessage();
    batch.setSchemaVersion(2);
    batch.setSessionId(sessionId);
    batch.setTick(300);
    batch.setTimestamp("2025-06-20T14:00:00Z");

    Map<String, Object> playerState = new LinkedHashMap<>();
    Map<String, Object> position = new LinkedHashMap<>();
    position.put("x", 3200);
    position.put("y", 3200);
    position.put("plane", 0);
    playerState.put("position", position);
    batch.setPlayerState(playerState);

    batch.setInventory(List.of());
    batch.setEquipment(List.of());
    batch.setThreats(List.of());
    batch.setProjectiles(List.of());
    batch.setCooldowns(new HashMap<>());
    batch.setLogs(new ArrayList<>());
    batch.setOverlays(new ArrayList<>());
    batch.setNpcs(List.of());
    batch.setGameObjects(List.of());
    batch.setGroundObjects(List.of());
    batch.setGroundItems(List.of());
    batch.setPlayers(List.of());
    batch.setWidgets(List.of());

    Map<String, Object> quest = new HashMap<>();
    quest.put("active", false);
    batch.setQuest(quest);

    service.processTickBatch(batch);

    ArgumentCaptor<TickSnapshot> captor = ArgumentCaptor.forClass(TickSnapshot.class);
    verify(snapshotRepository).save(captor.capture());
    TickSnapshot saved = captor.getValue();

    assertThat(saved.getPlayerState().path("position").path("x").asInt()).isEqualTo(3200);
    // Normalization should have added flat aliases
    assertThat(saved.getPlayerState().has("x")).isTrue();
    assertThat(saved.getPlayerState().has("z")).isTrue();
  }

  @Test
  @DisplayName("Strict v2 accepts old flat x/z/plane")
  void strictV2_acceptsOldFlatPosition() {
    String sessionId = "s4";
    when(sessionService.getSessionForIngestion(sessionId))
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

    TickBatchMessage batch = strictV2Batch(sessionId, 400);
    // strictV2Batch already sets flat x/z

    service.processTickBatch(batch);

    ArgumentCaptor<TickSnapshot> captor = ArgumentCaptor.forClass(TickSnapshot.class);
    verify(snapshotRepository).save(captor.capture());
    TickSnapshot saved = captor.getValue();

    // Old flat payload should be stored with synthesized nested position
    assertThat(saved.getPlayerState().has("position")).isTrue();
    assertThat(saved.getPlayerState().path("position").path("x").asInt()).isEqualTo(3200);
  }

  @Test
  @DisplayName("Old flat HP/prayer/energy/animation are stored with synthesized nested fields")
  void oldFlatPayload_synthesizesNestedFields() {
    String sessionId = "s5";
    when(sessionService.getSessionForIngestion(sessionId))
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

    TickBatchMessage batch = strictV2Batch(sessionId, 500);
    Map<String, Object> playerState = new HashMap<>();
    playerState.put("x", 3200);
    playerState.put("z", 3200);
    playerState.put("currentHp", 80);
    playerState.put("maxHp", 99);
    playerState.put("currentPrayer", 60);
    playerState.put("maxPrayer", 70);
    playerState.put("runEnergy", 50);
    playerState.put("animationId", 808);
    batch.setPlayerState(playerState);

    service.processTickBatch(batch);

    ArgumentCaptor<TickSnapshot> captor = ArgumentCaptor.forClass(TickSnapshot.class);
    verify(snapshotRepository).save(captor.capture());
    TickSnapshot saved = captor.getValue();

    JsonNode ps = saved.getPlayerState();
    assertThat(ps.path("hp").path("current").asInt()).isEqualTo(80);
    assertThat(ps.path("hp").path("max").asInt()).isEqualTo(99);
    assertThat(ps.path("prayer").path("current").asInt()).isEqualTo(60);
    assertThat(ps.path("prayer").path("max").asInt()).isEqualTo(70);
    assertThat(ps.path("energy").asInt()).isEqualTo(50);
    assertThat(ps.path("animation").asInt()).isEqualTo(808);
  }

  @Test
  @DisplayName("Strict v2 rejects nested position without x")
  void strictV2_rejectsNestedPositionWithoutX() {
    String sessionId = "s7";

    TickBatchMessage batch = new TickBatchMessage();
    batch.setSchemaVersion(2);
    batch.setSessionId(sessionId);
    batch.setTick(700);
    batch.setTimestamp("2025-06-21T10:00:00Z");

    Map<String, Object> playerState = new LinkedHashMap<>();
    Map<String, Object> position = new LinkedHashMap<>();
    position.put("y", 3200);
    position.put("plane", 0);
    playerState.put("position", position);
    batch.setPlayerState(playerState);

    batch.setInventory(List.of());
    batch.setEquipment(List.of());
    batch.setThreats(List.of());
    batch.setProjectiles(List.of());
    batch.setCooldowns(new HashMap<>());
    batch.setLogs(new ArrayList<>());
    batch.setOverlays(new ArrayList<>());
    batch.setNpcs(List.of());
    batch.setGameObjects(List.of());
    batch.setGroundObjects(List.of());
    batch.setGroundItems(List.of());
    batch.setPlayers(List.of());
    batch.setWidgets(List.of());

    Map<String, Object> quest = new HashMap<>();
    quest.put("active", false);
    batch.setQuest(quest);

    assertThrows(IllegalArgumentException.class, () -> service.processTickBatch(batch));

    verify(snapshotRepository, never()).save(any(TickSnapshot.class));
  }

  @Test
  @DisplayName("Strict v2 rejects nested position without y or z")
  void strictV2_rejectsNestedPositionWithoutYOrZ() {
    String sessionId = "s8";

    TickBatchMessage batch = new TickBatchMessage();
    batch.setSchemaVersion(2);
    batch.setSessionId(sessionId);
    batch.setTick(800);
    batch.setTimestamp("2025-06-21T10:00:00Z");

    Map<String, Object> playerState = new LinkedHashMap<>();
    Map<String, Object> position = new LinkedHashMap<>();
    position.put("x", 3200);
    position.put("plane", 0);
    playerState.put("position", position);
    batch.setPlayerState(playerState);

    batch.setInventory(List.of());
    batch.setEquipment(List.of());
    batch.setThreats(List.of());
    batch.setProjectiles(List.of());
    batch.setCooldowns(new HashMap<>());
    batch.setLogs(new ArrayList<>());
    batch.setOverlays(new ArrayList<>());
    batch.setNpcs(List.of());
    batch.setGameObjects(List.of());
    batch.setGroundObjects(List.of());
    batch.setGroundItems(List.of());
    batch.setPlayers(List.of());
    batch.setWidgets(List.of());

    Map<String, Object> quest = new HashMap<>();
    quest.put("active", false);
    batch.setQuest(quest);

    assertThrows(IllegalArgumentException.class, () -> service.processTickBatch(batch));

    verify(snapshotRepository, never()).save(any(TickSnapshot.class));
  }

  @Test
  @DisplayName("Strict v2 accepts nested position with x and z")
  void strictV2_acceptsNestedPositionWithXAndZ() {
    String sessionId = "s9";
    when(sessionService.getSessionForIngestion(sessionId))
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

    TickBatchMessage batch = new TickBatchMessage();
    batch.setSchemaVersion(2);
    batch.setSessionId(sessionId);
    batch.setTick(900);
    batch.setTimestamp("2025-06-21T10:00:00Z");

    Map<String, Object> playerState = new LinkedHashMap<>();
    Map<String, Object> position = new LinkedHashMap<>();
    position.put("x", 3200);
    position.put("z", 3200);
    position.put("plane", 0);
    playerState.put("position", position);
    batch.setPlayerState(playerState);

    batch.setInventory(List.of());
    batch.setEquipment(List.of());
    batch.setThreats(List.of());
    batch.setProjectiles(List.of());
    batch.setCooldowns(new HashMap<>());
    batch.setLogs(new ArrayList<>());
    batch.setOverlays(new ArrayList<>());
    batch.setNpcs(List.of());
    batch.setGameObjects(List.of());
    batch.setGroundObjects(List.of());
    batch.setGroundItems(List.of());
    batch.setPlayers(List.of());
    batch.setWidgets(List.of());

    Map<String, Object> quest = new HashMap<>();
    quest.put("active", false);
    batch.setQuest(quest);

    service.processTickBatch(batch);

    ArgumentCaptor<TickSnapshot> captor = ArgumentCaptor.forClass(TickSnapshot.class);
    verify(snapshotRepository).save(captor.capture());
    TickSnapshot saved = captor.getValue();

    // Normalized position.y should be derived from z
    assertThat(saved.getPlayerState().path("position").path("y").asInt()).isEqualTo(3200);
    assertThat(saved.getPlayerState().path("position").path("z").asInt()).isEqualTo(3200);
    // Flat aliases should be present
    assertThat(saved.getPlayerState().path("x").asInt()).isEqualTo(3200);
    assertThat(saved.getPlayerState().path("z").asInt()).isEqualTo(3200);
  }

  @Test
  @DisplayName("Strict v2 accepts flat x/worldY/plane")
  void strictV2_acceptsFlatXWorldY() {
    String sessionId = "s10";
    when(sessionService.getSessionForIngestion(sessionId))
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

    TickBatchMessage batch = new TickBatchMessage();
    batch.setSchemaVersion(2);
    batch.setSessionId(sessionId);
    batch.setTick(1000);
    batch.setTimestamp("2025-06-21T10:00:00Z");

    Map<String, Object> playerState = new LinkedHashMap<>();
    playerState.put("x", 3200);
    playerState.put("worldY", 3200);
    playerState.put("plane", 0);
    batch.setPlayerState(playerState);

    batch.setInventory(List.of());
    batch.setEquipment(List.of());
    batch.setThreats(List.of());
    batch.setProjectiles(List.of());
    batch.setCooldowns(new HashMap<>());
    batch.setLogs(new ArrayList<>());
    batch.setOverlays(new ArrayList<>());
    batch.setNpcs(List.of());
    batch.setGameObjects(List.of());
    batch.setGroundObjects(List.of());
    batch.setGroundItems(List.of());
    batch.setPlayers(List.of());
    batch.setWidgets(List.of());

    Map<String, Object> quest = new HashMap<>();
    quest.put("active", false);
    batch.setQuest(quest);

    service.processTickBatch(batch);

    ArgumentCaptor<TickSnapshot> captor = ArgumentCaptor.forClass(TickSnapshot.class);
    verify(snapshotRepository).save(captor.capture());
    TickSnapshot saved = captor.getValue();

    assertThat(saved.getPlayerState().path("position").path("x").asInt()).isEqualTo(3200);
    assertThat(saved.getPlayerState().path("position").path("y").asInt()).isEqualTo(3200);
    assertThat(saved.getPlayerState().path("position").path("z").asInt()).isEqualTo(3200);
  }

  @Test
  @DisplayName("Strict v2 accepts flat worldX/worldY/plane")
  void strictV2_acceptsWorldXWorldY() {
    String sessionId = "s12";
    when(sessionService.getSessionForIngestion(sessionId))
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

    TickBatchMessage batch = strictV2Batch(sessionId, 1200);
    Map<String, Object> playerState = new LinkedHashMap<>();
    playerState.put("worldX", 3200);
    playerState.put("worldY", 3200);
    playerState.put("plane", 0);
    batch.setPlayerState(playerState);

    service.processTickBatch(batch);

    ArgumentCaptor<TickSnapshot> captor = ArgumentCaptor.forClass(TickSnapshot.class);
    verify(snapshotRepository).save(captor.capture());
    JsonNode savedPlayerState = captor.getValue().getPlayerState();

    assertThat(savedPlayerState.path("position").path("x").asInt()).isEqualTo(3200);
    assertThat(savedPlayerState.path("position").path("y").asInt()).isEqualTo(3200);
    assertThat(savedPlayerState.path("position").path("z").asInt()).isEqualTo(3200);
    assertThat(savedPlayerState.path("x").asInt()).isEqualTo(3200);
    assertThat(savedPlayerState.path("z").asInt()).isEqualTo(3200);
  }

  @Test
  @DisplayName("Strict v2 rejects nested position with null x")
  void strictV2_rejectsNestedPositionWithNullX() {
    String sessionId = "s13";

    TickBatchMessage batch = strictV2Batch(sessionId, 1300);
    Map<String, Object> position = new LinkedHashMap<>();
    position.put("x", null);
    position.put("y", 3200);
    position.put("plane", 0);
    batch.setPlayerState(new LinkedHashMap<>(Map.of("position", position)));

    assertThrows(IllegalArgumentException.class, () -> service.processTickBatch(batch));

    verify(snapshotRepository, never()).save(any(TickSnapshot.class));
  }

  @Test
  @DisplayName("Strict v2 rejects flat position with null x")
  void strictV2_rejectsFlatPositionWithNullX() {
    String sessionId = "s14";

    TickBatchMessage batch = strictV2Batch(sessionId, 1400);
    batch.getPlayerState().put("x", null);

    assertThrows(IllegalArgumentException.class, () -> service.processTickBatch(batch));

    verify(snapshotRepository, never()).save(any(TickSnapshot.class));
  }

  @Test
  @DisplayName("Strict v2 rejects a non-map position without escaping normalization")
  void strictV2_rejectsNonMapPosition() {
    String sessionId = "s15";

    TickBatchMessage batch = strictV2Batch(sessionId, 1500);
    Map<String, Object> playerState = new LinkedHashMap<>();
    playerState.put("position", "bad");
    batch.setPlayerState(playerState);

    assertThrows(IllegalArgumentException.class, () -> service.processTickBatch(batch));

    verify(snapshotRepository, never()).save(any(TickSnapshot.class));
  }

  @Test
  @DisplayName("Invalid nested position does not create null flat aliases")
  void normalizationDoesNotCreateNullFlatAliasesFromInvalidNestedPosition() {
    String sessionId = "s16";

    TickBatchMessage batch = strictV2Batch(sessionId, 1600);
    batch.setPlayerState(new LinkedHashMap<>(Map.of("position", Map.of("y", 3200))));

    assertThrows(IllegalArgumentException.class, () -> service.processTickBatch(batch));

    verify(snapshotRepository, never()).save(any(TickSnapshot.class));
    assertThat(batch.getPlayerState()).doesNotContainKey("x");
  }

  @Test
  @DisplayName("Old flat x/z/plane synthesizes position.y")
  void oldFlatXZ_synthesizesPositionY() {
    String sessionId = "s11";
    when(sessionService.getSessionForIngestion(sessionId))
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

    TickBatchMessage batch = new TickBatchMessage();
    batch.setSchemaVersion(2);
    batch.setSessionId(sessionId);
    batch.setTick(1100);
    batch.setTimestamp("2025-06-21T10:00:00Z");

    Map<String, Object> playerState = new LinkedHashMap<>();
    playerState.put("x", 3200);
    playerState.put("z", 3200);
    playerState.put("plane", 0);
    batch.setPlayerState(playerState);

    batch.setInventory(List.of());
    batch.setEquipment(List.of());
    batch.setThreats(List.of());
    batch.setProjectiles(List.of());
    batch.setCooldowns(new HashMap<>());
    batch.setLogs(new ArrayList<>());
    batch.setOverlays(new ArrayList<>());
    batch.setNpcs(List.of());
    batch.setGameObjects(List.of());
    batch.setGroundObjects(List.of());
    batch.setGroundItems(List.of());
    batch.setPlayers(List.of());
    batch.setWidgets(List.of());

    Map<String, Object> quest = new HashMap<>();
    quest.put("active", false);
    batch.setQuest(quest);

    service.processTickBatch(batch);

    ArgumentCaptor<TickSnapshot> captor = ArgumentCaptor.forClass(TickSnapshot.class);
    verify(snapshotRepository).save(captor.capture());
    TickSnapshot saved = captor.getValue();

    assertThat(saved.getPlayerState().has("position")).isTrue();
    assertThat(saved.getPlayerState().path("position").path("x").asInt()).isEqualTo(3200);
    assertThat(saved.getPlayerState().path("position").path("y").asInt()).isEqualTo(3200);
    assertThat(saved.getPlayerState().path("position").path("z").asInt()).isEqualTo(3200);
  }

  @Test
  @DisplayName("Strict v2 rejects flat position with non-numeric x")
  void strictV2_rejectsFlatPositionWithNonNumericX() {
    TickBatchMessage batch = strictV2Batch("s17", 1700);
    batch.getPlayerState().put("x", "bad");
    batch.getPlayerState().put("z", 3200);

    assertThrows(IllegalArgumentException.class, () -> service.processTickBatch(batch));

    verify(snapshotRepository, never()).save(any(TickSnapshot.class));
  }

  @Test
  @DisplayName("Strict v2 rejects nested position with boolean x")
  void strictV2_rejectsNestedPositionWithBooleanX() {
    TickBatchMessage batch = strictV2Batch("s18", 1800);
    Map<String, Object> position = new LinkedHashMap<>();
    position.put("x", true);
    position.put("y", 3200);
    batch.setPlayerState(new LinkedHashMap<>(Map.of("position", position)));

    assertThrows(IllegalArgumentException.class, () -> service.processTickBatch(batch));

    verify(snapshotRepository, never()).save(any(TickSnapshot.class));
  }

  @Test
  @DisplayName("Strict v2 rejects flat position with object worldX")
  void strictV2_rejectsFlatPositionWithObjectWorldX() {
    TickBatchMessage batch = strictV2Batch("s19", 1900);
    Map<String, Object> playerState = new LinkedHashMap<>();
    playerState.put("worldX", Map.of("bad", true));
    playerState.put("worldY", 3200);
    batch.setPlayerState(playerState);

    assertThrows(IllegalArgumentException.class, () -> service.processTickBatch(batch));

    verify(snapshotRepository, never()).save(any(TickSnapshot.class));
  }

  @Test
  @DisplayName("Strict v2 rejects invalid plane")
  void strictV2_rejectsInvalidPlane() {
    TickBatchMessage batch = strictV2Batch("s20", 2000);
    batch.getPlayerState().put("plane", "bad");

    assertThrows(IllegalArgumentException.class, () -> service.processTickBatch(batch));

    verify(snapshotRepository, never()).save(any(TickSnapshot.class));
  }

  @Test
  @DisplayName("Strict v2 accepts and normalizes numeric string coordinates")
  void strictV2_acceptsNumericStringCoordinates() {
    String sessionId = "s21";
    when(sessionService.getSessionForIngestion(sessionId))
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

    TickBatchMessage batch = strictV2Batch(sessionId, 2100);
    Map<String, Object> playerState = new LinkedHashMap<>();
    playerState.put("x", "3200");
    playerState.put("z", "3200");
    playerState.put("plane", "0");
    batch.setPlayerState(playerState);

    service.processTickBatch(batch);

    ArgumentCaptor<TickSnapshot> captor = ArgumentCaptor.forClass(TickSnapshot.class);
    verify(snapshotRepository).save(captor.capture());
    JsonNode savedPlayerState = captor.getValue().getPlayerState();

    assertThat(savedPlayerState.path("position").path("x").isNumber()).isTrue();
    assertThat(savedPlayerState.path("position").path("x").asInt()).isEqualTo(3200);
    assertThat(savedPlayerState.path("position").path("y").asInt()).isEqualTo(3200);
    assertThat(savedPlayerState.path("position").path("z").asInt()).isEqualTo(3200);
    assertThat(savedPlayerState.path("position").path("plane").asInt()).isEqualTo(0);
    assertThat(savedPlayerState.path("x").isNumber()).isTrue();
    assertThat(savedPlayerState.path("z").isNumber()).isTrue();
    assertThat(savedPlayerState.path("plane").isNumber()).isTrue();
  }

  @Test
  @DisplayName("Strict v2 normalizes numeric string world coordinates")
  void strictV2_normalizesNumericStringWorldCoordinates() {
    String sessionId = "s22";
    when(sessionService.getSessionForIngestion(sessionId))
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

    TickBatchMessage batch = strictV2Batch(sessionId, 2200);
    Map<String, Object> playerState = new LinkedHashMap<>();
    playerState.put("worldX", "3200");
    playerState.put("worldY", "3200");
    batch.setPlayerState(playerState);

    service.processTickBatch(batch);

    ArgumentCaptor<TickSnapshot> captor = ArgumentCaptor.forClass(TickSnapshot.class);
    verify(snapshotRepository).save(captor.capture());
    JsonNode savedPlayerState = captor.getValue().getPlayerState();

    assertThat(savedPlayerState.path("position").path("x").asInt()).isEqualTo(3200);
    assertThat(savedPlayerState.path("position").path("y").asInt()).isEqualTo(3200);
    assertThat(savedPlayerState.path("x").isNumber()).isTrue();
    assertThat(savedPlayerState.path("z").isNumber()).isTrue();
    assertThat(savedPlayerState.path("worldX").isNumber()).isTrue();
    assertThat(savedPlayerState.path("worldY").isNumber()).isTrue();
  }

  @Test
  @DisplayName("wire identity fields must match the DTO before receipt lookup")
  void wireIdentityMismatchIsRejectedBeforeReceiptLookup() {
    TickBatchMessage batch = strictV2Batch("wire-identity", 300);
    batch.setBatchId("00000000-0000-4000-8000-000000000301");
    applyBatchChecksum(batch);
    JsonNode wire = objectMapper.valueToTree(batch);
    ((ObjectNode) wire).put("tick", 301L);

    assertThrows(
        IllegalArgumentException.class, () -> receiptService().processTickBatch(batch, wire));
    verify(batchReceiptRepository, never())
        .insertIfAbsent(any(), any(), anyLong(), any(), any(), any());
    verify(snapshotRepository, never()).save(any());
  }

  @Test
  @DisplayName("checksum mismatch is rejected before any persistence")
  void checksumMismatchIsRejectedBeforePersistence() {
    TickBatchMessage batch = strictV2Batch("checksum-mismatch", 310);
    batch.setBatchId("00000000-0000-4000-8000-000000000302");
    applyBatchChecksum(batch);
    batch.setPayloadChecksum("sha256:" + "f".repeat(64));
    JsonNode wire = objectMapper.valueToTree(batch);

    assertThrows(
        IllegalArgumentException.class, () -> receiptService().processTickBatch(batch, wire));
    verify(snapshotRepository, never()).save(any());
  }

  @Test
  @DisplayName("typed action checksum must match the Micro6 golden vector")
  void typedActionChecksumMatchesMicro6Vector() {
    ReplayRecordingActionIngestionService actions =
        new ReplayRecordingActionIngestionService(
            org.mockito.Mockito.mock(
                com.chromascape.web.debug.tickdebugger.entity.ReplayRecordingActionRepository
                    .class),
            objectMapper,
            new TickBatchCanonicalChecksumService(objectMapper));
    com.chromascape.web.debug.tickdebugger.dto.RecordingAction action =
        new com.chromascape.web.debug.tickdebugger.dto.RecordingAction();
    action.setRecordingId("recording-1");
    action.setSessionId("session-1");
    action.setTick(12);
    action.setSequence(3);
    action.setActionType("INTERACT_NPC");
    action.setTarget(Map.of("kind", "NPC", "stableId", "npc:1518:3200:3200:0"));
    action.setArguments(new HashMap<>(Map.of("option", "net")));
    action.setSource(
        com.chromascape.web.debug.tickdebugger.dto.RecordingAction.SOURCE_MICRO6_SEMANTIC_DISPATCH);
    action.setProvenance(
        com.chromascape.web.debug.tickdebugger.dto.RecordingAction.PROVENANCE_CAPTURED);
    action.setConfidence(java.math.BigDecimal.ONE);
    action.setCapturedAt("2026-08-31T22:00:00Z");
    action.setContentChecksum(actions.computeContentChecksum(action));

    assertThat(action.getContentChecksum())
        .isEqualTo("sha256:4ef4c963fe183e1c91ac29e8d612a3e61e4a2f6dd3033fe109e0a9afc97e3547");
  }

  @Test
  @DisplayName("log failure rolls back snapshot, receipt, actions and bounds")
  void logFailureRollsBackAllWork() {
    String sessionId = "rollback-logs";
    String batchId = "00000000-0000-4000-8000-000000000303";
    when(sessionService.getSessionForIngestion(sessionId))
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));
    when(batchReceiptRepository.insertIfAbsent(any(), any(), anyLong(), any(), any(), any()))
        .thenReturn(1);
    doThrow(new IllegalStateException("log failure")).when(logRepository).save(any());
    TickBatchMessage batch = strictV2Batch(sessionId, 320);
    batch.setBatchId(batchId);
    batch.setLogs(
        List.of(new TickBatchMessage.TickLogEntry("System", "Info", null, "boom", null, null)));
    applyBatchChecksum(batch);

    assertThrows(IllegalStateException.class, () -> receiptService().processTickBatch(batch));
    verify(overlayRepository, never()).save(any());
  }

  @Test
  @DisplayName("overlay failure rolls back all work")
  void overlayFailureRollsBackAllWork() {
    String sessionId = "rollback-overlays";
    String batchId = "00000000-0000-4000-8000-000000000304";
    when(sessionService.getSessionForIngestion(sessionId))
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));
    when(batchReceiptRepository.insertIfAbsent(any(), any(), anyLong(), any(), any(), any()))
        .thenReturn(1);
    doThrow(new IllegalStateException("overlay failure")).when(overlayRepository).save(any());
    TickBatchMessage batch = strictV2Batch(sessionId, 330);
    batch.setBatchId(batchId);
    batch.setOverlays(List.of(new TickBatchMessage.TickOverlayEntry("note", Map.of("k", "v"))));
    applyBatchChecksum(batch);

    assertThrows(IllegalStateException.class, () -> receiptService().processTickBatch(batch));
  }

  @Test
  @DisplayName("post-commit publication is registered only on the success path")
  void postCommitPublicationOnlyOnSuccess() {
    String sessionId = "post-commit";
    String batchId = "00000000-0000-4000-8000-000000000305";
    when(sessionService.getSessionForIngestion(sessionId))
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));
    when(batchReceiptRepository.insertIfAbsent(any(), any(), anyLong(), any(), any(), any()))
        .thenReturn(1);
    doThrow(new IllegalStateException("snapshot failure")).when(snapshotRepository).save(any());
    TickBatchMessage failing = strictV2Batch(sessionId, 340);
    failing.setBatchId(batchId);
    applyBatchChecksum(failing);

    // This is a direct-construction unit call, not a Spring transaction-commit verification:
    // a failure must propagate and no PERSISTED acknowledgment may be produced.
    assertThrows(IllegalStateException.class, () -> receiptService().processTickBatch(failing));
  }

  private DebugSession finalizedSession() {
    DebugSession session =
        new DebugSession("closed", "account", "script", LocalDateTime.of(2025, 6, 15, 12, 0), 10L);
    session.setEndTime(LocalDateTime.of(2025, 6, 15, 12, 1));
    session.setEndTick(100L);
    session.setLifecycleState(
        com.chromascape.web.debug.tickdebugger.entity.DebugSessionLifecycleState.FINALIZED);
    when(sessionService.getSessionForIngestion("closed")).thenReturn(Optional.of(session));
    return session;
  }

  @Test
  void finalizedSessionRejectsBeyondEndBeforeAnyEvidenceWrite() {
    finalizedSession();
    TickBatchMessage batch = strictV2Batch("closed", 101L);
    batch.setTimestamp(
        LocalDateTime.of(2025, 6, 15, 12, 0, 30)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toString());
    assertThat(
            org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> service.processTickBatch(batch)))
        .hasMessageContaining("FINALIZED_SESSION_BOUNDARY");
    org.mockito.Mockito.verifyNoInteractions(
        snapshotRepository,
        logRepository,
        overlayRepository,
        markerRepository,
        batchReceiptRepository);
    verify(sessionService, never()).updateSessionTickBounds(any(), anyLong());
  }

  @Test
  void finalizedSessionRejectsLaterTimeEvenWithReusedTick() {
    finalizedSession();
    TickBatchMessage batch = strictV2Batch("closed", 50L);
    batch.setTimestamp(
        LocalDateTime.of(2025, 6, 15, 12, 2)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toString());
    assertThrows(IllegalArgumentException.class, () -> service.processTickBatch(batch));
    org.mockito.Mockito.verifyNoInteractions(snapshotRepository, logRepository, overlayRepository);
  }

  @Test
  void finalizedSessionAcceptsDelayedPacketInsideBothBounds() {
    finalizedSession();
    TickBatchMessage batch = strictV2Batch("closed", 50L);
    batch.setTimestamp(
        LocalDateTime.of(2025, 6, 15, 12, 0, 30)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toString());
    service.processTickBatch(batch);
    verify(snapshotRepository).save(any(TickSnapshot.class));
  }

  @Test
  void finalizedSessionRejectsUnknownTimestamp() {
    finalizedSession();
    TickBatchMessage batch = strictV2Batch("closed", 50L);
    batch.setTimestamp("not-a-timestamp");
    assertThrows(IllegalArgumentException.class, () -> service.processTickBatch(batch));
    org.mockito.Mockito.verifyNoInteractions(snapshotRepository);
  }

  @Test
  void finalizedSessionRejectsDataFromBeforeRecording() {
    finalizedSession();
    TickBatchMessage batch = strictV2Batch("closed", 9L);
    assertThrows(IllegalArgumentException.class, () -> service.processTickBatch(batch));
    org.mockito.Mockito.verifyNoInteractions(snapshotRepository);
  }
}
