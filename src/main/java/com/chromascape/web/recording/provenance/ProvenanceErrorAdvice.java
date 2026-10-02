package com.chromascape.web.recording.provenance;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Stable HTTP validation codes shared across recording provenance consumers. */
@RestControllerAdvice
public class ProvenanceErrorAdvice {
  @ExceptionHandler(ProvenanceException.class)
  public ResponseEntity<Map<String, String>> invalid(ProvenanceException ex) {
    return ResponseEntity.badRequest()
        .body(Map.of("code", ex.getCode(), "message", "Recording contract validation failed"));
  }
}
