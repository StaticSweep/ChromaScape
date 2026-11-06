package com.chromascape.web.aiplayer.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Independently measures operator-installed client artifacts. The authenticated producer's running
 * artifact digest must equal these bytes and the reviewed pins, binding its build to a full commit
 * even when capture carries no task-source group. This is not a live-process readiness verdict.
 */
@Component
public final class CurrentClientArtifacts {
  private final Map<String, Map<String, Object>> observed;

  public CurrentClientArtifacts(
      @Value("${ai-player.contract.micro6-artifact:}") String micro6,
      @Value("${ai-player.contract.runelite-artifact:}") String runelite) {
    observed = Map.of("MICRO6_MANAGED", measure(micro6), "RUNELITE_CAPTURE", measure(runelite));
  }

  private static Map<String, Object> measure(String path) {
    try {
      return path == null || path.isBlank()
          ? Map.of()
          : CurrentArtifactIdentity.observe(Path.of(path));
    } catch (RuntimeException ignored) {
      return Map.of();
    }
  }

  public Map<String, Map<String, Object>> observed() {
    return observed;
  }

  public String refusal(String kind, JsonNode expected) {
    var actual = observed.get(kind);
    if (actual == null || actual.isEmpty()) return "CLIENT_ARTIFACT_UNVERIFIED";
    for (String field : new String[] {"build_id", "commit_id", "artifact_digest"}) {
      if (!actual.get(field).equals(expected.path(field).asText()))
        return "CLIENT_ARTIFACT_MISMATCH";
    }
    return null;
  }
}
