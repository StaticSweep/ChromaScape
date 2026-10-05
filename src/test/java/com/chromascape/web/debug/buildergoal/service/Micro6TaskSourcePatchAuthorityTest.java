package com.chromascape.web.debug.buildergoal.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.debug.patchloop.dto.AnalysisV2PatchDraft;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class Micro6TaskSourcePatchAuthorityTest {
  @Test
  void rawUrlCannotAuthorizePatchWhenProductionAuthorityIsPresent() {
    var service = new Micro6TaskSourcePatchService(new ObjectMapper(), "http://127.0.0.1:8098");
    var authority = mock(BuilderGoalEndpointAuthority.class);
    ReflectionTestUtils.setField(service, "endpointAuthority", authority);
    assertThrows(
        IllegalStateException.class,
        () -> service.applyPatch("http://127.0.0.1:22", "task", "run", new AnalysisV2PatchDraft()));
    assertThrows(
        IllegalStateException.class,
        () ->
            service.applyAllocatedPatch(
                Map.of("endpoint_ref", "expired"), "task", "run", new AnalysisV2PatchDraft()));
    verify(authority).resolve(any());
  }
}
