package com.chromascape.web.recording.provenance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chromascape.utils.core.screen.window.WindowSelectionService;
import com.chromascape.web.debug.tickdebugger.service.DebugSessionService;
import com.chromascape.web.recording.config.RecordingConfig;
import com.chromascape.web.recording.controller.RecordingController;
import com.chromascape.web.recording.entity.*;
import com.chromascape.web.recording.service.*;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class VideoCatalogAccessTest {
  @Test
  void newVideoCatalogKeepsLegacyAccessAndVideoOnlyRowsWithoutTickAuthentication() {
    var pager = mock(RecordingCatalogPager.class);
    var controller =
        new RecordingController(
            mock(RecordingService.class),
            mock(RecordingSessionRepository.class),
            mock(WindowSelectionService.class),
            new RecordingConfig(),
            mock(RecordingSessionMetadataService.class),
            mock(DebugSessionService.class));
    ReflectionTestUtils.setField(controller, "catalogPager", pager);
    var row =
        new RecordingSession(
            "video-only",
            "actual-account",
            "script",
            LocalDateTime.now(),
            RecordingSession.SessionTrigger.MANUAL,
            null);
    when(pager.page("actual-account", null, 1, null))
        .thenReturn(new RecordingCatalogPager.Page(List.of(row), null, false));
    var response = controller.catalogPage("actual-account", null, 1, null);
    assertEquals(200, response.getStatusCode().value());
    assertEquals(1, ((List<?>) response.getBody().get("entries")).size());
    assertFalse((Boolean) response.getBody().get("hasMore"));
    verify(pager).page("actual-account", null, 1, null);
  }
}
