package com.chromascape.web.debug.patchloop.service.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OpenAiCodexAnalysisProviderTest {

  @Test
  @DisplayName("getProviderName() returns openai-codex")
  void testGetProviderName() {
    OpenAiCodexAnalysisProvider provider =
        new OpenAiCodexAnalysisProvider(
            new ObjectMapper(),
            "test-key",
            "https://api.openai.com/v1/chat/completions",
            "gpt-5-mini",
            120,
            8192);

    assertEquals("openai-codex", provider.getProviderName());
  }

  @Test
  @DisplayName("isAvailable() is true when api key is configured")
  void testIsAvailableWithApiKey() {
    OpenAiCodexAnalysisProvider provider =
        new OpenAiCodexAnalysisProvider(
            new ObjectMapper(),
            "test-key",
            "https://api.openai.com/v1/chat/completions",
            "gpt-5-mini",
            120,
            8192);

    assertTrue(provider.isAvailable());
  }

  @Test
  @DisplayName("isAvailable() is false when api key is missing")
  void testIsAvailableWithoutApiKey() {
    OpenAiCodexAnalysisProvider provider =
        new OpenAiCodexAnalysisProvider(
            new ObjectMapper(),
            "",
            "https://api.openai.com/v1/chat/completions",
            "gpt-5-mini",
            120,
            8192);

    assertFalse(provider.isAvailable());
  }
}
