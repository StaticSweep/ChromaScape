package com.chromascape.web.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.web.server.ResponseStatusException;

/** Child JVM for the cross-process admission test; prints ADMITTED or the refusal reason. */
public final class CrossProcessAdmissionMain {
  private CrossProcessAdmissionMain() {}

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    var mode = ImmutableArtifactStore.DurabilityMode.valueOf(args[1]);
    Path go = Path.of(args[2]);
    while (!Files.exists(go)) Thread.sleep(1);
    try {
      new ImmutableArtifactStore(root, 1024, 65536, new ObjectMapper(), mode, directory -> {});
      System.out.println("ADMITTED");
    } catch (ResponseStatusException e) {
      System.out.println(e.getReason());
    }
  }
}
