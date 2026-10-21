package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class MouseVideoRequestGuardTest {

  private static final MouseVideoAccess.Actor ACTOR =
      new MouseVideoAccess.Actor("owner-1", "owner-1");

  private MouseVideoRequestGuard guard;
  private MouseVideoWriteNonceService nonces;

  @BeforeEach
  void setUp() {
    MouseVideoProperties properties = new MouseVideoProperties();
    properties.setWriteNonceSecret("a".repeat(48));
    nonces =
        new MouseVideoWriteNonceService(
            properties, Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC));
    guard = new MouseVideoRequestGuard(nonces);
  }

  private MockHttpServletRequest sameOrigin() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/debug/mouse-videos/imports/upload");
    request.setScheme("http");
    request.setServerName("localhost");
    request.setServerPort(8090);
    request.addHeader("Origin", "http://localhost:8090");
    return request;
  }

  @Test
  void acceptsSameOriginWithValidNonce() {
    MockHttpServletRequest request = sameOrigin();
    request.addHeader(MouseVideoWriteNonceService.HEADER, nonces.issue("owner-1").nonce());
    assertThatCode(() -> guard.requireWrite(request, ACTOR)).doesNotThrowAnyException();
  }

  @Test
  void rejectsMissingNonce() {
    assertThatThrownBy(() -> guard.requireWrite(sameOrigin(), ACTOR))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_FORBIDDEN);
  }

  @Test
  void rejectsCrossOriginRequest() {
    MockHttpServletRequest request = sameOrigin();
    request.removeHeader("Origin");
    request.addHeader("Origin", "http://evil.example.com");
    request.addHeader(MouseVideoWriteNonceService.HEADER, nonces.issue("owner-1").nonce());
    assertThatThrownBy(() -> guard.requireWrite(request, ACTOR))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_FORBIDDEN);
  }

  @Test
  void rejectsCrossSiteFetchMetadata() {
    MockHttpServletRequest request = sameOrigin();
    request.addHeader("Sec-Fetch-Site", "cross-site");
    request.addHeader(MouseVideoWriteNonceService.HEADER, nonces.issue("owner-1").nonce());
    assertThatThrownBy(() -> guard.requireWrite(request, ACTOR))
        .isInstanceOf(MouseVideoException.class);
  }

  @Test
  void rejectsNonceIssuedForAnotherOwner() {
    MockHttpServletRequest request = sameOrigin();
    request.addHeader(MouseVideoWriteNonceService.HEADER, nonces.issue("owner-2").nonce());
    assertThatThrownBy(() -> guard.requireWrite(request, ACTOR))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_FORBIDDEN);
  }
}
