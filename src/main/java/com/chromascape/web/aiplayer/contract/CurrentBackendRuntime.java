package com.chromascape.web.aiplayer.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Component;

/**
 * Measures the packaged application once at startup; configured expectations are never evidence.
 */
@Component
public final class CurrentBackendRuntime {
  private final Map<String, Object> observed;

  public CurrentBackendRuntime() {
    this(applicationArtifact());
  }

  CurrentBackendRuntime(Path artifact) {
    observed = CurrentArtifactIdentity.observe(artifact);
  }

  private static Path applicationArtifact() {
    var source = new ApplicationHome(CurrentBackendRuntime.class).getSource();
    return source == null ? null : source.toPath();
  }

  public Map<String, Object> observed() {
    return observed;
  }

  public String refusal(JsonNode expected) {
    if (observed.isEmpty()) return "BACKEND_RUNTIME_UNVERIFIED";
    for (String field : new String[] {"build_id", "commit_id", "artifact_digest"}) {
      if (!observed.get(field).equals(expected.path(field).asText())) {
        return "BACKEND_RUNTIME_MISMATCH";
      }
    }
    return null;
  }
}
