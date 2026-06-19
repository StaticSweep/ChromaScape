package com.chromascape.web.config;

import jakarta.annotation.PostConstruct;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Validates patch-loop execution backend configuration at startup. */
@Component
public class PatchLoopExecutionBackendValidator {
  private static final Logger logger =
      LoggerFactory.getLogger(PatchLoopExecutionBackendValidator.class);
  private static final Set<String> VALID_BACKENDS =
      Set.of("local_instance", "micro6_local_executor");
  private final String backend;

  public PatchLoopExecutionBackendValidator(
      @Value("${chromascape.patchloop.execution-backend:micro6_local_executor}") String backend) {
    this.backend = backend;
  }

  @PostConstruct
  public void validate() {
    if (!VALID_BACKENDS.contains(backend)) {
      String error =
          String.format(
              "Invalid chromascape.patchloop.execution-backend='%s'. Valid values: %s",
              backend, VALID_BACKENDS);
      logger.error(error);
      throw new IllegalStateException(error);
    }
    logger.info("Patch-loop execution backend validated: {}", backend);
  }
}
