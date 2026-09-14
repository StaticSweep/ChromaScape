package com.chromascape.web.replay.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.replay.playback.dto.ReplayPlaybackCatalogResponse;
import com.chromascape.web.replay.playback.dto.ReplayPlaybackFramePageResponse;
import com.chromascape.web.replay.security.ReplayPrincipalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ReplayPlaybackControllerTest {

  @Test
  void playbackEndpointsRequireIdentityAndDelegateToReplayService() {
    ReplayPlaybackService service = mock(ReplayPlaybackService.class);
    ReplayPrincipalService principalService = new ReplayPrincipalService();
    ReplayPlaybackController controller = new ReplayPlaybackController(service, principalService);
    Principal principal = () -> "operator";
    when(service.catalog(null, 50, (String) null))
        .thenReturn(new ReplayPlaybackCatalogResponse(List.of(), null, false));
    when(service.frames("recording-1", null, null, 100))
        .thenReturn(
            new ReplayPlaybackFramePageResponse("recording-1", "debug-1", List.of(), null, false));

    assertEquals(HttpStatus.OK, controller.catalog(principal, null, 50, null).getStatusCode());
    assertEquals(
        HttpStatus.OK,
        controller.frames(principal, "recording-1", null, null, 100).getStatusCode());
  }

  @Test
  void deprecatedAliasesReturnV1PayloadsAndMigrationHeaders() {
    ReplayPlaybackService service = mock(ReplayPlaybackService.class);
    ReplayPlaybackController controller =
        new ReplayPlaybackController(service, new ReplayPrincipalService());
    Principal principal = () -> "operator";
    when(service.catalog(null, 50, null, 0))
        .thenReturn(new ReplayPlaybackCatalogResponse(List.of(), null, false));
    when(service.frames("recording-1", null, null, 100))
        .thenReturn(
            new ReplayPlaybackFramePageResponse("recording-1", "debug-1", List.of(), null, false));

    var catalog = controller.catalogAlias(principal, null, 50, null, 0);
    var frames = controller.framesAlias(principal, "recording-1", null, null, 100);

    assertEquals("true", catalog.getHeaders().getFirst("Deprecation"));
    assertEquals(
        "</api/replay/playback/v1/recordings>; rel=\"successor-version\"",
        catalog.getHeaders().getFirst("Link"));
    assertEquals("replay-playback-catalog/v1", catalog.getBody().getSchema());
    assertEquals("true", frames.getHeaders().getFirst("Deprecation"));
    assertEquals(
        "</api/replay/playback/v1/recordings/recording-1/frames>; rel=\"successor-version\"",
        frames.getHeaders().getFirst("Link"));
    assertEquals("replay-playback-frame-page/v1", frames.getBody().getSchema());
  }

  @Test
  void playbackEndpointsRejectMissingIdentityAndBlankRecordingIds() {
    ReplayPlaybackService service = mock(ReplayPlaybackService.class);
    ReplayPlaybackController controller =
        new ReplayPlaybackController(service, new ReplayPrincipalService());

    assertEquals(
        HttpStatus.UNAUTHORIZED,
        assertThrows(
                com.chromascape.web.replay.error.ReplayException.class,
                () -> controller.catalog(null, null, 50, null))
            .getHttpStatus());
    assertEquals(
        "VALIDATION_ERROR",
        assertThrows(
                com.chromascape.web.replay.error.ReplayException.class,
                () -> controller.frames(() -> "operator", "  ", null, null, 100))
            .getErrorCode()
            .name());
  }

  @Test
  void canonicalAndDeprecatedFrameAliasesShareTheSameV1PayloadShape() throws Exception {
    ReplayPlaybackService service = mock(ReplayPlaybackService.class);
    ReplayPlaybackController controller =
        new ReplayPlaybackController(service, new ReplayPrincipalService());
    Principal principal = () -> "operator";
    var payload =
        new ReplayPlaybackFramePageResponse("recording-1", "debug-1", List.of(), null, false);
    when(service.frames("recording-1", null, null, 100)).thenReturn(payload);

    var canonical = controller.frames(principal, "recording-1", null, null, 100);
    var alias = controller.framesAlias(principal, "recording-1", null, null, 100);

    ObjectMapper mapper = new ObjectMapper();
    assertEquals(
        mapper.writeValueAsString(canonical.getBody()), mapper.writeValueAsString(alias.getBody()));
    assertEquals("replay-playback-frame-page/v1", canonical.getBody().getSchema());
    assertEquals("replay-playback-frame-page/v1", alias.getBody().getSchema());
  }
}
