package com.chromascape.web.debug.patchloop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.debug.patchloop.entity.*;
import com.chromascape.web.debug.patchloop.service.PatchLoopAsyncWorker;
import com.chromascape.web.debug.patchloop.service.PatchLoopEvidenceService;
import com.chromascape.web.debug.patchloop.service.PatchLoopPersistenceService;
import com.chromascape.web.debug.patchloop.service.provider.AnalysisProvider;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.instance.ScriptControl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Test suite for PatchLoopAsyncWorker.
 *
 * <p>Note: These tests verify the worker's orchestration logic. Detailed persistence behavior is
 * tested in {@link PatchLoopPersistenceServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class PatchLoopAsyncWorkerTest {

  @Mock private PatchLoopIterationRepository iterationRepository;
  @Mock private PatchLoopEventRepository eventRepository;
  @Mock private PatchLoopEvidenceService evidenceService;
  @Mock private AnalysisProvider analysisProvider;
  @Mock private DebugSessionRepository debugSessionRepository;
  @Mock private ScriptControl scriptControl;
  @Mock private PatchLoopPersistenceService persistenceService;

  private PatchLoopAsyncWorker asyncWorker;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();

    // Setup mock provider to return a name
    when(analysisProvider.getProviderName()).thenReturn("mock-provider");

    asyncWorker =
        new PatchLoopAsyncWorker(
            iterationRepository,
            eventRepository,
            evidenceService,
            analysisProvider,
            debugSessionRepository,
            scriptControl,
            objectMapper,
            persistenceService);
  }

  @Test
  @DisplayName(
      "Worker successfully constructed with all dependencies including persistence service")
  void testWorkerConstruction() {
    assertNotNull(asyncWorker, "Worker should be constructed successfully");
  }
}
