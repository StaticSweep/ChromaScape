package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class MouseVideoWriteNonceServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

  private MouseVideoWriteNonceService service() {
    MouseVideoProperties properties = new MouseVideoProperties();
    properties.setWriteNonceSecret("a".repeat(48));
    properties.setWriteNonceTtlSeconds(600L);
    return new MouseVideoWriteNonceService(properties, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void acceptsNonceForItsOwner() {
    MouseVideoWriteNonceService service = service();
    String nonce = service.issue("owner-1").nonce();
    assertThatCode(() -> service.verify("owner-1", nonce)).doesNotThrowAnyException();
  }

  @Test
  void rejectsNonceForAnotherOwner() {
    MouseVideoWriteNonceService service = service();
    String nonce = service.issue("owner-1").nonce();
    assertThatThrownBy(() -> service.verify("owner-2", nonce))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_FORBIDDEN);
  }

  @Test
  void rejectsMalformedAndExpiredNonces() {
    MouseVideoWriteNonceService service = service();
    assertThatThrownBy(() -> service.verify("owner-1", "garbage"))
        .isInstanceOf(MouseVideoException.class);
    String expired = "0.deadbeef";
    assertThatThrownBy(() -> service.verify("owner-1", expired))
        .isInstanceOf(MouseVideoException.class);
  }
}
