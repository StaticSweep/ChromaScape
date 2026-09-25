package com.chromascape.web.debug.tickdebugger.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.recording.config.RecordingTriggerSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SessionStartRequest} preferred-session binding support added for the
 * debugger recovery-mode rebind contract.
 */
class SessionStartRequestTest {

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void preferredSessionId_roundTripsThroughGetSet() {
    SessionStartRequest request = new SessionStartRequest();
    request.setAccountName("account-1");
    request.setScriptName("configurable_woodcutting");
    request.setRecordingEnabled(true);
    request.setPreferredSessionId("debug_reserved_42");

    assertThat(request.getPreferredSessionId()).isEqualTo("debug_reserved_42");
    assertThat(request.getAccountName()).isEqualTo("account-1");
    assertThat(request.getRecordingEnabled()).isTrue();
  }

  @Test
  void fiveArgConstructor_setsPreferredSessionId() {
    SessionStartRequest request =
        new SessionStartRequest(
            "account-1",
            "configurable_woodcutting",
            true,
            RecordingTriggerSource.MICRO6_AUTO,
            "debug_reserved_99");

    assertThat(request.getPreferredSessionId()).isEqualTo("debug_reserved_99");
    assertThat(request.getTriggerSource()).isEqualTo(RecordingTriggerSource.MICRO6_AUTO);
  }

  @Test
  void preferredSessionId_defaultsToNullAndDeserializesFromJson() throws Exception {
    String json =
        "{\"accountName\":\"a\",\"scriptName\":\"s\",\"recordingEnabled\":true,"
            + "\"preferredSessionId\":\"debug_from_json\"}";
    SessionStartRequest request = objectMapper.readValue(json, SessionStartRequest.class);

    assertThat(request.getPreferredSessionId()).isEqualTo("debug_from_json");
  }

  @Test
  void preferredSessionId_omittedInJsonYieldsNull() throws Exception {
    String json = "{\"accountName\":\"a\",\"scriptName\":\"s\",\"recordingEnabled\":false}";
    SessionStartRequest request = objectMapper.readValue(json, SessionStartRequest.class);

    assertThat(request.getPreferredSessionId()).isNull();
  }

  @Test
  void legacySessionIdName_isAcceptedAsAliasForPreferredSessionId() throws Exception {
    String json =
        "{\"accountName\":\"a\",\"scriptName\":\"s\",\"recordingEnabled\":true,"
            + "\"sessionId\":\"debug_legacy_alias\"}";
    SessionStartRequest request = objectMapper.readValue(json, SessionStartRequest.class);

    assertThat(request.getPreferredSessionId()).isEqualTo("debug_legacy_alias");
  }

  @Test
  void preferredSessionIdWinsWhenBothJsonNamesPresent() throws Exception {
    String json =
        "{\"accountName\":\"a\",\"scriptName\":\"s\",\"recordingEnabled\":true,"
            + "\"preferredSessionId\":\"debug_canonical\",\"sessionId\":\"debug_legacy\"}";
    SessionStartRequest request = objectMapper.readValue(json, SessionStartRequest.class);

    assertThat(request.getPreferredSessionId()).isEqualTo("debug_canonical");
  }

  @Test
  void canonicalFieldWinsWhenLegacySessionIdAppearsFirst() throws Exception {
    String json =
        "{\"accountName\":\"a\",\"scriptName\":\"s\",\"recordingEnabled\":true,"
            + "\"sessionId\":\"debug_legacy\",\"preferredSessionId\":\"debug_canonical\"}";
    SessionStartRequest request = objectMapper.readValue(json, SessionStartRequest.class);

    assertThat(request.getPreferredSessionId())
        .isEqualTo("debug_canonical")
        .as("the canonical preferredSessionId must win regardless of JSON field order");
  }

  @Test
  void canonicalFieldWinsWhenPreferredAppearsFirst() throws Exception {
    String json =
        "{\"accountName\":\"a\",\"scriptName\":\"s\",\"recordingEnabled\":true,"
            + "\"preferredSessionId\":\"debug_canonical\",\"sessionId\":\"debug_legacy\"}";
    SessionStartRequest request = objectMapper.readValue(json, SessionStartRequest.class);

    assertThat(request.getPreferredSessionId())
        .isEqualTo("debug_canonical")
        .as("the canonical preferredSessionId must win regardless of JSON field order");
  }

  @Test
  void clientRequestId_deserializesIndependentlyFromPreferredSessionId() throws Exception {
    String json =
        "{\"accountName\":\"a\",\"scriptName\":\"s\",\"recordingEnabled\":true,"
            + "\"clientRequestId\":\"a4a7d886-1111-2222-3333-444455556666\","
            + "\"preferredSessionId\":\"debug_reserved_1\"}";
    SessionStartRequest request = objectMapper.readValue(json, SessionStartRequest.class);

    assertThat(request.getClientRequestId()).isEqualTo("a4a7d886-1111-2222-3333-444455556666");
    assertThat(request.getPreferredSessionId()).isEqualTo("debug_reserved_1");
  }

  @Test
  void clientRequestId_isSeparateFromLegacySessionIdAlias() throws Exception {
    String json =
        "{\"accountName\":\"a\",\"scriptName\":\"s\",\"recordingEnabled\":true,"
            + "\"clientRequestId\":\"a4a7d886-9999\","
            + "\"sessionId\":\"debug_legacy_alias\"}";
    SessionStartRequest request = objectMapper.readValue(json, SessionStartRequest.class);

    assertThat(request.getClientRequestId()).isEqualTo("a4a7d886-9999");
    assertThat(request.getPreferredSessionId()).isEqualTo("debug_legacy_alias");
  }

  @Test
  void clientRequestId_omittedInJsonYieldsNull() throws Exception {
    String json = "{\"accountName\":\"a\",\"scriptName\":\"s\",\"recordingEnabled\":false}";
    SessionStartRequest request = objectMapper.readValue(json, SessionStartRequest.class);

    assertThat(request.getClientRequestId()).isNull();
  }

  @Test
  void blankClientRequestId_remainsBackwardCompatible() throws Exception {
    String json =
        "{\"accountName\":\"a\",\"scriptName\":\"s\",\"recordingEnabled\":true,"
            + "\"clientRequestId\":\"\"}";
    SessionStartRequest request = objectMapper.readValue(json, SessionStartRequest.class);

    // A blank correlation id must not break deserialization or the rest of the request.
    assertThat(request.getClientRequestId()).isEqualTo("");
    assertThat(request.getAccountName()).isEqualTo("a");
    assertThat(request.getScriptName()).isEqualTo("s");
    assertThat(request.getRecordingEnabled()).isTrue();
    assertThat(request.getPreferredSessionId()).isNull();
  }

  @Test
  void sixArgConstructor_setsPreferredSessionIdAndClientRequestId() {
    SessionStartRequest request =
        new SessionStartRequest(
            "account-1",
            "configurable_woodcutting",
            true,
            RecordingTriggerSource.MICRO6_AUTO,
            "debug_reserved_99",
            "a4a7d886-1234");

    assertThat(request.getPreferredSessionId()).isEqualTo("debug_reserved_99");
    assertThat(request.getClientRequestId()).isEqualTo("a4a7d886-1234");
  }
}
