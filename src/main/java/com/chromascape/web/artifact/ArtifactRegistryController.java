package com.chromascape.web.artifact;

import com.chromascape.web.replay.security.ReplayPrincipalService;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Owner identity is server-resolved; client manifest metadata cannot authorize another owner. */
@RestController
@RequestMapping("/api/replay/artifacts/v1")
public class ArtifactRegistryController {
  private final ImmutableArtifactStore store;
  private final ReplayPrincipalService principals;
  private final boolean development;

  public ArtifactRegistryController(
      ImmutableArtifactStore store, ReplayPrincipalService principals, Environment environment) {
    this.store = store;
    this.principals = principals;
    development = List.of(environment.getActiveProfiles()).equals(List.of("local-development"));
  }

  public record PublicationMetadata(
      String id,
      ImmutableArtifactStore.Kind kind,
      String mediaType,
      Map<String, String> lineage,
      Map<String, String> dependencies) {}

  @PostMapping(consumes = "application/octet-stream")
  public ImmutableArtifactStore.Manifest publish(
      Principal principal,
      @RequestHeader("X-Artifact-Manifest") String metadata,
      jakarta.servlet.http.HttpServletRequest request)
      throws java.io.IOException {
    String owner = principals.requirePrincipal(principal);
    if (metadata.length() > 65536)
      throw new ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE, "ARTIFACT_MANIFEST_TOO_LARGE");
    PublicationMetadata manifest;
    try {
      manifest =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readValue(metadata, PublicationMetadata.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_ARTIFACT_MANIFEST");
    }
    if (manifest == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_ARTIFACT_MANIFEST");
    if (request.getContentLengthLong() > store.maxArtifactBytes())
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "ARTIFACT_TOO_LARGE");
    try (var input = request.getInputStream();
        var content = new java.io.ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      long size = 0;
      int count;
      while ((count = input.read(buffer)) != -1) {
        size += count;
        if (size > store.maxArtifactBytes())
          throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "ARTIFACT_TOO_LARGE");
        content.write(buffer, 0, count);
      }
      return store.put(
          owner,
          manifest.id(),
          manifest.kind(),
          content.toByteArray(),
          manifest.mediaType(),
          manifest.lineage(),
          manifest.dependencies());
    }
  }

  @PostMapping("/{id}/activate-knowledge")
  public ImmutableArtifactStore.SnapshotReference activateKnowledge(
      Principal principal, @PathVariable String id) {
    return store.activateKnowledgeSnapshot(principals.requirePrincipal(principal), id);
  }

  @GetMapping("/active-knowledge")
  public ImmutableArtifactStore.SnapshotReference activeKnowledge(Principal principal) {
    return store.activeKnowledgeSnapshot(principals.requirePrincipal(principal));
  }

  @GetMapping("/{id}/state")
  public ImmutableArtifactStore.State state(Principal principal, @PathVariable String id) {
    return store.state(principals.requirePrincipal(principal), id);
  }

  @PostMapping("/{id}/quarantine")
  public void quarantine(Principal principal, @PathVariable String id) {
    store.quarantine(principals.requirePrincipal(principal), id);
  }

  @GetMapping
  public List<ImmutableArtifactStore.Manifest> list(Principal principal) {
    return store.list(principals.requirePrincipal(principal));
  }

  @GetMapping("/{id}")
  public ImmutableArtifactStore.Manifest get(Principal principal, @PathVariable String id) {
    return store.get(principals.requirePrincipal(principal), id);
  }

  @GetMapping(value = "/{id}/content", produces = "application/octet-stream")
  public byte[] content(Principal principal, @PathVariable String id) {
    return store.read(principals.requirePrincipal(principal), id);
  }

  @PostMapping("/development-reset")
  public void reset(Principal principal, @RequestParam boolean confirmed) {
    String owner = principals.requirePrincipal(principal);
    if (!development)
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "DEVELOPMENT_RESET_DISABLED");
    store.resetDevelopment(owner, confirmed);
  }
}
