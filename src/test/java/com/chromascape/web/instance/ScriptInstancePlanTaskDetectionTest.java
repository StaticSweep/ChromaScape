package com.chromascape.web.instance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.recording.config.TaskType;
import com.chromascape.web.recording.service.TaskDetectionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ScriptInstance#dispatchTaskDetection} plan-vs-heuristic routing.
 *
 * <p>Verifies that plan-linked runs (non-blank {@code taskPlanId}) route to {@link
 * TaskDetectionService#onPlanTaskDetected}, while heuristic runs route to {@link
 * TaskDetectionService#onTaskDetected}. Tests the static dispatch helper directly so they do not
 * construct a {@code BaseScript} (which binds native input/hook resources unsuitable for unit
 * tests).
 */
@ExtendWith(MockitoExtension.class)
class ScriptInstancePlanTaskDetectionTest {

  @Mock private TaskDetectionService taskDetectionService;

  /** A non-blank taskPlanId routes to the plan-task path. */
  @Test
  void dispatch_withPlanId_routesToPlanTaskDetection() {
    ScriptInstance.dispatchTaskDetection(
        taskDetectionService, "plan-42", "item-7", "DemoMiningScript");

    verify(taskDetectionService).onPlanTaskDetected("plan-42", "item-7", "DemoMiningScript");
    verify(taskDetectionService, never()).onTaskDetected(any(), anyString());
  }

  /** A null taskPlanId routes to heuristic detection. */
  @Test
  void dispatch_withoutPlanId_routesToHeuristicDetection() {
    ScriptInstance.dispatchTaskDetection(taskDetectionService, null, null, "DemoMiningScript");

    verify(taskDetectionService).onTaskDetected(TaskType.MINING, "DemoMiningScript");
    verify(taskDetectionService, never())
        .onPlanTaskDetected(anyString(), anyString(), anyString());
  }

  /** A blank taskPlanId routes to heuristic detection (blank is treated as absent). */
  @Test
  void dispatch_withBlankPlanId_routesToHeuristicDetection() {
    ScriptInstance.dispatchTaskDetection(taskDetectionService, "   ", null, "DemoFishingScript");

    verify(taskDetectionService).onTaskDetected(TaskType.FISHING, "DemoFishingScript");
    verify(taskDetectionService, never())
        .onPlanTaskDetected(anyString(), anyString(), anyString());
  }
}
