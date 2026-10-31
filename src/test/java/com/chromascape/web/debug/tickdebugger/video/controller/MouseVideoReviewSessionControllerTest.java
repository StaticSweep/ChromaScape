package com.chromascape.web.debug.tickdebugger.video.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoAccess;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoRequestGuard;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoReviewSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Status semantics: a new protocol write is 201 and an identical retry is 200. */
class MouseVideoReviewSessionControllerTest {

  private MouseVideoReviewSessionService sessions;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    sessions = mock(MouseVideoReviewSessionService.class);
    var controller =
        new MouseVideoReviewSessionController(
            sessions, new MouseVideoAccess(), mock(MouseVideoRequestGuard.class));
    mvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void newSessionCreatesAndIdenticalRetryReuses() throws Exception {
    UUID sourceId = UUID.randomUUID();
    var body = new ObjectMapper().createObjectNode().put("requestId", UUID.randomUUID().toString());
    when(sessions.createResult(anyString(), anyString(), any(), any()))
        .thenReturn(new MouseVideoReviewSessionService.Result(Map.of("id", "s1"), true))
        .thenReturn(new MouseVideoReviewSessionService.Result(Map.of("id", "s1"), false));
    mvc.perform(
            post("/api/debug/mouse-videos/sources/{sourceId}/review-sessions", sourceId)
                .principal(() -> "owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().isCreated());
    mvc.perform(
            post("/api/debug/mouse-videos/sources/{sourceId}/review-sessions", sourceId)
                .principal(() -> "owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("s1"));
  }

  @Test
  void newSnapshotCreatesAndIdenticalRetryReuses() throws Exception {
    UUID sessionId = UUID.randomUUID();
    var body = new ObjectMapper().createObjectNode().put("snapshotJson", "{\"requestId\":\"r1\"}");
    when(sessions.submitResult(anyString(), anyString(), any(), any()))
        .thenReturn(new MouseVideoReviewSessionService.Result(Map.of("revisionId", "r1"), true))
        .thenReturn(new MouseVideoReviewSessionService.Result(Map.of("revisionId", "r1"), false));
    mvc.perform(
            post("/api/debug/mouse-videos/review-sessions/{id}/reviews", sessionId)
                .principal(() -> "owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().isCreated());
    mvc.perform(
            post("/api/debug/mouse-videos/review-sessions/{id}/reviews", sessionId)
                .principal(() -> "owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revisionId").value("r1"));
  }
}
