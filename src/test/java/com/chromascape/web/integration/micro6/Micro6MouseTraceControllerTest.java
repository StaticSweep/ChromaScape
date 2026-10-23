package com.chromascape.web.integration.micro6;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Micro6MouseTraceControllerTest {

  @Test
  void acceptsLoopbackLiteralsOnly() {
    assertThat(Micro6MouseTraceController.loopback("127.0.0.1")).isTrue();
    assertThat(Micro6MouseTraceController.loopback("0:0:0:0:0:0:0:1")).isTrue();
    assertThat(Micro6MouseTraceController.loopback("192.168.1.6")).isFalse();
    assertThat(Micro6MouseTraceController.loopback("localhost")).isFalse();
    assertThat(Micro6MouseTraceController.loopback(null)).isFalse();
  }
}
