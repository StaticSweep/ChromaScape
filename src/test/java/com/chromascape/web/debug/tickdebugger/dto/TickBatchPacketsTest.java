package com.chromascape.web.debug.tickdebugger.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * SC-1618: schema-v3 packet DTO coverage. Schema-v2 payloads without packets keep deserializing
 * with packets defaulted to empty.
 */
class TickBatchPacketsTest {

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void deserializesSchemaV3WithPackets() throws Exception {
    String json =
        "{\"type\":\"TICK_BATCH\",\"schemaVersion\":3,\"batchId\":\"b1\","
            + "\"payloadChecksum\":\"sha256:abc\",\"sessionId\":\"s1\",\"tick\":12004,"
            + "\"timestamp\":\"2026-09-04T18:12:34.567Z\","
            + "\"playerState\":{\"x\":3200,\"z\":3200},"
            + "\"inventory\":[],\"equipment\":[],\"threats\":[],\"projectiles\":[],"
            + "\"cooldowns\":{},\"logs\":[],\"overlays\":[],"
            + "\"quest\":{},\"npcs\":[],\"gameObjects\":[],\"groundObjects\":[],"
            + "\"groundItems\":[],\"players\":[],\"widgets\":[],"
            + "\"packets\":[{"
            + "\"schema\":\"rsprox-packet/v1\",\"sourceSessionId\":\"proxy-session-uuid\","
            + "\"sequence\":4812,\"capturedAt\":\"2026-09-04T18:12:34.567Z\","
            + "\"captureDeltaMillis\":4,\"direction\":\"SERVER_TO_CLIENT\",\"opcode\":81,"
            + "\"protocolName\":\"NPC_INFO_LARGE\",\"encodedSize\":137,"
            + "\"decodedType\":\"NpcInfo\",\"decoded\":{},"
            + "\"decodeStatus\":\"DECODED\",\"synthetic\":false,\"tick\":12004}],"
            + "\"transport\":{\"droppedPacketCount\":0,\"packetSourceStatus\":\"CONNECTED\"}}";

    TickBatchMessage msg = objectMapper.readValue(json, TickBatchMessage.class);
    assertThat(msg.getSchemaVersion()).isEqualTo(3);
    assertThat(msg.getPackets()).hasSize(1);
    TickBatchMessage.TickPacketEntry packet = msg.getPackets().get(0);
    assertThat(packet.getSequence()).isEqualTo(4812L);
    assertThat(packet.getDirection()).isEqualTo("SERVER_TO_CLIENT");
    assertThat(packet.getProtocolName()).isEqualTo("NPC_INFO_LARGE");
    assertThat(packet.getTick()).isEqualTo(12004L);
    assertThat(msg.getTransport().getPacketSourceStatus()).isEqualTo("CONNECTED");
  }

  @Test
  void schemaV2WithoutPacketsDefaultsToEmpty() throws Exception {
    String json =
        "{\"type\":\"TICK_BATCH\",\"schemaVersion\":2,\"sessionId\":\"s1\",\"tick\":50,"
            + "\"timestamp\":\"2025-06-15T12:00:00Z\","
            + "\"playerState\":{\"x\":3200,\"z\":3200},"
            + "\"inventory\":[],\"equipment\":[],\"threats\":[],\"projectiles\":[],"
            + "\"cooldowns\":{},\"logs\":[],\"overlays\":[],"
            + "\"quest\":{},\"npcs\":[],\"gameObjects\":[],\"groundObjects\":[],"
            + "\"groundItems\":[],\"players\":[],\"widgets\":[]}";

    TickBatchMessage msg = objectMapper.readValue(json, TickBatchMessage.class);
    assertThat(msg.getSchemaVersion()).isEqualTo(2);
    assertThat(msg.getPackets()).isNotNull();
    assertThat(msg.getPackets()).isEmpty();
  }
}
