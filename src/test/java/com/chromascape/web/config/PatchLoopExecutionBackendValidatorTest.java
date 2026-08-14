package com.chromascape.web.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PatchLoopExecutionBackendValidatorTest {

  @Test
  @DisplayName("validate accepts local_instance")
  void validate_acceptsLocalInstance() {
    PatchLoopExecutionBackendValidator validator =
        new PatchLoopExecutionBackendValidator("local_instance");

    assertThatCode(validator::validate).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("validate accepts micro6_local_executor")
  void validate_acceptsMicro6() {
    PatchLoopExecutionBackendValidator validator =
        new PatchLoopExecutionBackendValidator("micro6_local_executor");

    assertThatCode(validator::validate).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("validate rejects unknown backend")
  void validate_rejectsUnknownBackend() {
    PatchLoopExecutionBackendValidator validator = new PatchLoopExecutionBackendValidator("osbot");

    assertThatThrownBy(validator::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Invalid chromascape.patchloop.execution-backend='osbot'")
        .hasMessageContaining("local_instance")
        .hasMessageContaining("micro6_local_executor");
  }
}
