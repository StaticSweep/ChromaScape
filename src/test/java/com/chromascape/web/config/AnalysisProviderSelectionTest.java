package com.chromascape.web.config;

import static org.junit.jupiter.api.Assertions.*;

import com.chromascape.web.debug.patchloop.service.provider.ClaudeApiAnalysisProvider;
import com.chromascape.web.debug.patchloop.service.provider.ClaudeMcpAnalysisProvider;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpConfigProperties;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpTransportClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Unit tests for analysis provider selection configuration.
 *
 * <p>Verifies that provider classes are properly annotated for configuration-based selection and
 * can be instantiated correctly.
 *
 * <p><strong>Runtime Verification:</strong> Full integration testing of provider selection is best
 * done at application startup. When the application starts, check logs for:
 *
 * <pre>
 * ===========================================
 * Analysis Provider: claude-api (or claude-mcp)
 * Provider Available: true/false
 * ===========================================
 * </pre>
 *
 * <p>To manually test provider selection:
 *
 * <pre>
 * # Test 1: Default (claude-api)
 * ./gradlew bootRun
 *
 * # Test 2: Explicit claude-api
 * export ANALYSIS_PROVIDER=claude-api && ./gradlew bootRun
 *
 * # Test 3: Claude MCP
 * export ANALYSIS_PROVIDER=claude-mcp && ./gradlew bootRun
 *
 * # Test 4: Invalid provider (fails at startup with validation error)
 * export ANALYSIS_PROVIDER=invalid && ./gradlew bootRun
 * # Expected: Application fails with IllegalStateException from AnalysisProviderValidator
 * # ERROR: Invalid analysis.provider='invalid'. Valid values: [claude-api, claude-mcp]
 * </pre>
 *
 * <p><strong>Note on Invalid Values:</strong> Spring's
 * {@code @ConditionalOnProperty(matchIfMissing=true)} only applies when the property is
 * <em>undefined</em>, NOT when it has an invalid value. To prevent confusing downstream bean
 * creation failures, {@link AnalysisProviderValidator} explicitly validates the property value at
 * startup and fails fast with a clear error message if invalid.
 */
class AnalysisProviderSelectionTest {

  @Test
  @DisplayName("ClaudeApiAnalysisProvider has @ConditionalOnProperty annotation")
  void testClaudeApiProviderAnnotation() {
    // Verify the class has the correct conditional annotation
    ConditionalOnProperty annotation =
        ClaudeApiAnalysisProvider.class.getAnnotation(ConditionalOnProperty.class);

    assertNotNull(
        annotation, "ClaudeApiAnalysisProvider should have @ConditionalOnProperty annotation");
    assertArrayEquals(
        new String[] {"analysis.provider"},
        annotation.name(),
        "Should check 'analysis.provider' property");
    assertEquals(
        "claude-api", annotation.havingValue(), "Should activate when value is 'claude-api'");
    // Note: matchIfMissing=true makes this the default provider
  }

  @Test
  @DisplayName("ClaudeMcpAnalysisProvider has @ConditionalOnProperty annotation")
  void testClaudeMcpProviderAnnotation() {
    // Verify the class has the correct conditional annotation
    ConditionalOnProperty annotation =
        ClaudeMcpAnalysisProvider.class.getAnnotation(ConditionalOnProperty.class);

    assertNotNull(
        annotation, "ClaudeMcpAnalysisProvider should have @ConditionalOnProperty annotation");
    assertArrayEquals(
        new String[] {"analysis.provider"},
        annotation.name(),
        "Should check 'analysis.provider' property");
    assertEquals(
        "claude-mcp", annotation.havingValue(), "Should activate when value is 'claude-mcp'");
    // Note: matchIfMissing defaults to false, so this is NOT the default provider
  }

  @Test
  @DisplayName("ClaudeApiAnalysisProvider can be instantiated with valid configuration")
  void testClaudeApiProviderInstantiation() {
    // Verify provider can be created with valid configuration
    ObjectMapper objectMapper = new ObjectMapper();
    ClaudeApiAnalysisProvider provider =
        new ClaudeApiAnalysisProvider(
            objectMapper, "test-key", "https://api.example.com", "claude-opus-4-6", 120, 4096);

    assertNotNull(provider, "Provider should be instantiated");
    assertEquals("claude-api", provider.getProviderName(), "Provider name should be 'claude-api'");
    assertTrue(provider.isAvailable(), "Provider should be available with API key");
  }

  @Test
  @DisplayName("ClaudeApiAnalysisProvider reports unavailable without API key")
  void testClaudeApiProviderUnavailableWithoutKey() {
    ObjectMapper objectMapper = new ObjectMapper();
    ClaudeApiAnalysisProvider provider =
        new ClaudeApiAnalysisProvider(
            objectMapper, "", "https://api.example.com", "claude-opus-4-6", 120, 4096);

    assertNotNull(provider, "Provider should be instantiated");
    assertFalse(provider.isAvailable(), "Provider should be unavailable without API key");
  }

  @Test
  @DisplayName("ClaudeMcpAnalysisProvider can be instantiated with valid configuration")
  void testClaudeMcpProviderInstantiation() {
    // Verify provider can be created with valid configuration
    McpConfigProperties config = new McpConfigProperties();
    config.setServerUrl("http://localhost:8765");
    config.setTimeoutSeconds(120);
    config.setMaxRetries(3);

    McpTransportClient transport = new McpTransportClient(config);
    ClaudeMcpAnalysisProvider provider = new ClaudeMcpAnalysisProvider(transport, config);

    assertNotNull(provider, "Provider should be instantiated");
    assertEquals("claude-mcp", provider.getProviderName(), "Provider name should be 'claude-mcp'");
    // Note: isAvailable() now performs actual health check, which will fail without a real MCP
    // server
    // For this test, we just verify the provider can be instantiated
  }

  @Test
  @DisplayName("Both providers implement AnalysisProvider interface")
  void testProviderInterfaceImplementation() {
    assertTrue(
        com.chromascape.web.debug.patchloop.service.provider.AnalysisProvider.class
            .isAssignableFrom(ClaudeApiAnalysisProvider.class),
        "ClaudeApiAnalysisProvider should implement AnalysisProvider");
    assertTrue(
        com.chromascape.web.debug.patchloop.service.provider.AnalysisProvider.class
            .isAssignableFrom(ClaudeMcpAnalysisProvider.class),
        "ClaudeMcpAnalysisProvider should implement AnalysisProvider");
  }
}
