package com.chromascape.web.recording.provenance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class RecordingProvenanceConvergenceResourceTest {
  @Test
  void startupConvergenceResolvesPackagedProvenanceMigration() throws Exception {
    var resource = new ClassPathResource(RecordingProvenanceConvergence.MIGRATION_RESOURCE);
    assertTrue(resource.exists(), "Startup convergence migration must be packaged");
    try (var stream = resource.getInputStream()) {
      var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(sql.contains("recording_tick_frames"));
      assertTrue(sql.contains("recording_provenance"));
    }
  }
}
