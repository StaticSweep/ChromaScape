package com.chromascape.web.aiplayer.contract;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.chromascape.web.aiplayer")
public class CurrentContractExceptionHandler {
  @ExceptionHandler(CurrentSliceContractException.class)
  public ResponseEntity<Map<String, Object>> refused(CurrentSliceContractException exception) {
    return ResponseEntity.unprocessableEntity()
        .body(
            Map.of(
                "code",
                exception.code().name(),
                "message",
                exception.code().name(),
                "status",
                "BLOCKED"));
  }
}
