package com.chromascape.web.artifact;

import static org.junit.jupiter.api.Assertions.*;

import com.chromascape.web.replay.security.ReplayPrincipalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ArtifactRegistryControllerTest {
  @TempDir Path root;

  @Test
  void authenticatedOwnerComesFromPrincipalAndResetIsDisabledOutsideDevelopment() throws Exception {
    var s =
        new ImmutableArtifactStore(
            root, 1024, 65536, new ObjectMapper(), TestDurability.positiveMode());
    var controller =
        new ArtifactRegistryController(s, new ReplayPrincipalService(), new StandardEnvironment());
    assertThrows(
        com.chromascape.web.replay.error.ReplayException.class, () -> controller.list(null));
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    request.setContent(new byte[] {1});
    String metadata =
        "{\"id\":\"id\",\"kind\":\"MODEL\",\"mediaType\":\"application/json\",\"lineage\":{\"control_enabled\":\"true\"},\"dependencies\":{}}";
    assertEquals("alice", controller.publish(() -> "alice", metadata, request).owner());
    assertThrows(ResponseStatusException.class, () -> controller.get(() -> "bob", "id"));
    assertEquals(
        HttpStatus.FORBIDDEN,
        assertThrows(ResponseStatusException.class, () -> controller.reset(() -> "alice", true))
            .getStatusCode());
    // Metadata is inert; the storage service/controller never changes any feature configuration.
    assertEquals("true", controller.get(() -> "alice", "id").lineage().get("control_enabled"));
  }

  @Test
  void rawPublicationIsBoundedBeforeRegistration() throws Exception {
    var s =
        new ImmutableArtifactStore(
            root, 4, 65536, new ObjectMapper(), TestDurability.positiveMode());
    var controller =
        new ArtifactRegistryController(s, new ReplayPrincipalService(), new StandardEnvironment());
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    request.setContent(new byte[5]);
    String metadata =
        "{\"id\":\"id\",\"kind\":\"MODEL\",\"mediaType\":\"application/json\",\"lineage\":{\"runId\":\"run\"},\"dependencies\":{}}";
    assertEquals(
        HttpStatus.PAYLOAD_TOO_LARGE,
        assertThrows(
                ResponseStatusException.class,
                () -> controller.publish(() -> "alice", metadata, request))
            .getStatusCode());
    assertTrue(s.list("alice").isEmpty());
  }

  @Test
  void chunkedPublicationIsBoundedBeforeRegistration() throws Exception {
    var s =
        new ImmutableArtifactStore(
            root, 4, 65536, new ObjectMapper(), TestDurability.positiveMode());
    var controller =
        new ArtifactRegistryController(s, new ReplayPrincipalService(), new StandardEnvironment());
    var request =
        new org.springframework.mock.web.MockHttpServletRequest() {
          @Override
          public long getContentLengthLong() {
            return -1;
          }
        };
    request.setContent(new byte[5]);
    String metadata =
        "{\"id\":\"id\",\"kind\":\"MODEL\",\"mediaType\":\"application/json\",\"lineage\":{\"runId\":\"run\"},\"dependencies\":{}}";
    assertEquals(
        HttpStatus.PAYLOAD_TOO_LARGE,
        assertThrows(
                ResponseStatusException.class,
                () -> controller.publish(() -> "alice", metadata, request))
            .getStatusCode());
    assertTrue(s.list("alice").isEmpty());
  }

  @Test
  void httpRoutesPublishAndResolveOnlyTheAuthenticatedOwnersArtifacts() throws Exception {
    var store =
        new ImmutableArtifactStore(
            root, 1024, 65536, new ObjectMapper(), TestDurability.positiveMode());
    var mvc =
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new ArtifactRegistryController(
                    store, new ReplayPrincipalService(), new StandardEnvironment()))
            .build();
    String metadata =
        "{\"id\":\"id\",\"kind\":\"MODEL\",\"mediaType\":\"application/json\",\"lineage\":{\"runId\":\"run\"},\"dependencies\":{}}";
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/replay/artifacts/v1")
                .principal(() -> "alice")
                .contentType("application/octet-stream")
                .header("X-Artifact-Manifest", metadata)
                .content(new byte[] {1}))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/replay/artifacts/v1/id/content")
                .principal(() -> "alice"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .bytes(new byte[] {1}));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/replay/artifacts/v1/id")
                .principal(() -> "bob"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isNotFound());
  }
}
