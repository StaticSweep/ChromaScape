package com.chromascape.web.debug.buildergoal.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.debug.buildergoal.service.BuilderGoalSensitiveTextSanitizer;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecoverySanitizationTest {

  private static final String CANARY_PASSWORD = "s3cret-hunter2-value";
  private static final String CANARY_TOKEN = "tok_9f8e7d6c5b4a3210";

  @Test
  void sanitizerVersionIsBumpedForArtifactAudit() {
    assertThat(BuilderGoalSensitiveTextSanitizer.SANITIZER_VERSION).isEqualTo("2");
  }

  @Test
  void redactsAuthorizationHeadersAndCookies() {
    String result =
        BuilderGoalSensitiveTextSanitizer.sanitize(
            "Authorization: Bearer " + CANARY_TOKEN + "\nCookie: session=abc123");
    assertThat(result).doesNotContain(CANARY_TOKEN);
    assertThat(result).doesNotContain("session=abc123");
    assertThat(result).contains("Authorization: Bearer ********");
  }

  @Test
  void redactsApiKeysAndClientSecrets() {
    String result =
        BuilderGoalSensitiveTextSanitizer.sanitize(
            "api_key=" + CANARY_TOKEN + " client_secret=\"" + CANARY_PASSWORD + "\"");
    assertThat(result).doesNotContain(CANARY_TOKEN);
    assertThat(result).doesNotContain(CANARY_PASSWORD);
  }

  @Test
  void redactsUrlUserinfoAndSensitiveQueryParameters() {
    String result =
        BuilderGoalSensitiveTextSanitizer.sanitize(
            "https://user:" + CANARY_PASSWORD + "@registry.example/v2?token=" + CANARY_TOKEN);
    assertThat(result).doesNotContain(CANARY_PASSWORD);
    assertThat(result).doesNotContain(CANARY_TOKEN);
    assertThat(result).contains("https://********:********@registry.example/v2?token=********");
  }

  @Test
  void redactsWindowsAndUnixHomePaths() {
    String result =
        BuilderGoalSensitiveTextSanitizer.sanitize(
            "GRADLE_USER_HOME=C:\\Users\\operator\\.gradle CREDENTIALS=/home/operator/.secrets");
    assertThat(result).doesNotContain("operator");
    assertThat(result).contains("C:\\Users\\********");
  }

  @Test
  void redactsConfiguredLiteralSecretsFromEnvironment() {
    String result =
        BuilderGoalSensitiveTextSanitizer.sanitizeArtifact(
            "leaked=SC-1173-CANARY-LITERAL-VALUE", List.of("SC-1173-CANARY-LITERAL-VALUE"));
    assertThat(result).doesNotContain("SC-1173-CANARY-LITERAL-VALUE");
    assertThat(result).contains("********");
  }

  @Test
  void environmentCollectorNeverReturnsShortValues() {
    List<String> secrets = BuilderGoalSensitiveTextSanitizer.environmentLiteralSecrets();
    assertThat(secrets).allSatisfy(secret -> assertThat(secret.length()).isGreaterThanOrEqualTo(6));
  }

  @Test
  void leavesOrdinaryCompilerTextIntact() {
    String line =
        "src/main/java/FishingExecutionTask.java:93: error: cannot find symbol Fish.SHRIMP";
    assertThat(BuilderGoalSensitiveTextSanitizer.sanitize(line)).isEqualTo(line);
  }
}
