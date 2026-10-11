package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class TrustedExecutorEndpointTest {

  private static final Set<String> TOPOLOGY = Set.of("192.168.1.6", "100.120.100.78");

  @Test
  void loopbackEndpointsAreTrustedWithoutTopologyHosts() {
    assertThat(TrustedExecutorEndpoint.evaluate("http://127.0.0.1:8098", Set.of()).trusted())
        .isTrue();
    assertThat(TrustedExecutorEndpoint.evaluate("http://localhost:8098", Set.of()).trusted())
        .isTrue();
    assertThat(TrustedExecutorEndpoint.evaluate("http://[::1]:8098", Set.of()).trusted()).isTrue();
  }

  @Test
  void topologyHostsAreTrustedAndNormalized() {
    TrustedExecutorEndpoint.Verdict verdict =
        TrustedExecutorEndpoint.evaluate("HTTP://192.168.1.6:8098/", TOPOLOGY);
    assertThat(verdict.trusted()).isTrue();
    assertThat(verdict.normalizedUrl()).isEqualTo("http://192.168.1.6:8098");
    assertThat(TrustedExecutorEndpoint.evaluate("https://100.120.100.78:8098", TOPOLOGY).trusted())
        .isTrue();
  }

  @Test
  void unknownHostsAreRejectedWithoutDialTarget() {
    TrustedExecutorEndpoint.Verdict verdict =
        TrustedExecutorEndpoint.evaluate("http://203.0.113.7:8098", TOPOLOGY);
    assertThat(verdict.trusted()).isFalse();
    assertThat(verdict.normalizedUrl()).isNull();
    assertThat(verdict.reasonCode()).isEqualTo(TrustedExecutorEndpoint.REASON_UNTRUSTED);
    assertThat(verdict.reason()).doesNotContain("203.0.113.7");
  }

  @Test
  void linkLocalMetadataEndpointsAreRejected() {
    assertThat(TrustedExecutorEndpoint.evaluate("http://169.254.169.254:80", TOPOLOGY).trusted())
        .isFalse();
  }

  @Test
  void nonHttpSchemesAreRejected() {
    assertThat(TrustedExecutorEndpoint.evaluate("ftp://127.0.0.1:8098", TOPOLOGY).trusted())
        .isFalse();
    assertThat(TrustedExecutorEndpoint.evaluate("file:///etc/passwd", TOPOLOGY).trusted())
        .isFalse();
    assertThat(TrustedExecutorEndpoint.evaluate("gopher://127.0.0.1:70", TOPOLOGY).trusted())
        .isFalse();
  }

  @Test
  void credentialBearingUrlsAreRejected() {
    assertThat(
            TrustedExecutorEndpoint.evaluate("http://user:pass@127.0.0.1:8098", TOPOLOGY).trusted())
        .isFalse();
    assertThat(
            TrustedExecutorEndpoint.evaluate("http://127.0.0.1@203.0.113.7:8098", TOPOLOGY)
                .trusted())
        .isFalse();
  }

  @Test
  void missingPortPathQueryAndFragmentAreRejected() {
    assertThat(TrustedExecutorEndpoint.evaluate("http://127.0.0.1", TOPOLOGY).trusted()).isFalse();
    assertThat(TrustedExecutorEndpoint.evaluate("http://127.0.0.1:8098/api", TOPOLOGY).trusted())
        .isFalse();
    assertThat(TrustedExecutorEndpoint.evaluate("http://127.0.0.1:8098?x=1", TOPOLOGY).trusted())
        .isFalse();
    assertThat(TrustedExecutorEndpoint.evaluate("http://127.0.0.1:8098#frag", TOPOLOGY).trusted())
        .isFalse();
    assertThat(TrustedExecutorEndpoint.evaluate("http://127.0.0.1:99999", TOPOLOGY).trusted())
        .isFalse();
  }

  @Test
  void missingAndMalformedInputFailsClosed() {
    assertThat(TrustedExecutorEndpoint.evaluate(null, TOPOLOGY).trusted()).isFalse();
    assertThat(TrustedExecutorEndpoint.evaluate(null, TOPOLOGY).reasonCode())
        .isEqualTo(TrustedExecutorEndpoint.REASON_MISSING);
    assertThat(TrustedExecutorEndpoint.evaluate("   ", TOPOLOGY).trusted()).isFalse();
    assertThat(TrustedExecutorEndpoint.evaluate("not a url", TOPOLOGY).trusted()).isFalse();
    assertThat(TrustedExecutorEndpoint.evaluate("http://", TOPOLOGY).trusted()).isFalse();
  }

  @Test
  void loopbackPortsAreBoundAndIpv6RemainsValid() {
    assertThat(TrustedExecutorEndpoint.evaluate("http://127.0.0.1:22", Set.of()).trusted())
        .isFalse();
    assertThat(TrustedExecutorEndpoint.evaluate("http://127.0.0.1:5433", Set.of()).trusted())
        .isFalse();
    assertThat(
            java.net
                .URI
                .create(
                    TrustedExecutorEndpoint.evaluate("http://[::1]:8098", Set.of()).normalizedUrl())
                .getHost())
        .isNotNull();
  }

  @Test
  void topologyHostsAreExtractedAndExtrasParsed() {
    assertThat(
            TrustedExecutorEndpoint.trustedHostsFromBaseUrls(
                "http://192.168.1.6:8084", "http://127.0.0.1:8098", null, "  ", "garbage %%%"))
        .containsExactlyInAnyOrder("192.168.1.6", "127.0.0.1");
    assertThat(TrustedExecutorEndpoint.parseExtraHosts("10.0.0.9, EXAMPLE.local ,,bad/host"))
        .containsExactlyInAnyOrder("10.0.0.9", "example.local");
    assertThat(
            TrustedExecutorEndpoint.buildTrustedHosts(
                Set.of("192.168.1.6"), TrustedExecutorEndpoint.parseExtraHosts("10.0.0.9")))
        .contains("127.0.0.1", "192.168.1.6", "10.0.0.9");
  }
}
