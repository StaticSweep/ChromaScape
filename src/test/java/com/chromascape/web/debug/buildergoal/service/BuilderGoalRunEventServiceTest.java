package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.patchloop.entity.PatchLoopEvent;
import com.chromascape.web.debug.patchloop.entity.PatchLoopEventRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopEventType;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIteration;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIterationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

/** Tests for the SC-260 durable run-event contract ({@link BuilderGoalRunEventService}). */
@ExtendWith(MockitoExtension.class)
class BuilderGoalRunEventServiceTest {

  @Mock private PatchLoopEventRepository eventRepository;
  @Mock private PatchLoopIterationRepository iterationRepository;
  @Mock private PlatformTransactionManager transactionManager;
  @Mock private TransactionStatus transactionStatus;

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private BuilderGoalRunEventService service;

  @BeforeEach
  void setUp() {
    service = new BuilderGoalRunEventService(eventRepository, iterationRepository, objectMapper);
  }

  private PatchLoopIteration iteration(String iterationId) {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId(iterationId);
    iteration.setScriptName("FISHING_TRAINING");
    iteration.setPrompt("Fishing training run");
    return iteration;
  }

  @Test
  @DisplayName("recordRunEvent stamps the owning task/script identity into the event data")
  void recordRunEvent_stampsIdentity() {
    when(iterationRepository.findByIterationId("bg_test0001"))
        .thenReturn(Optional.of(iteration("bg_test0001")));
    when(eventRepository.findTopByIterationIdOrderByIdDesc("bg_test0001"))
        .thenReturn(Optional.empty());

    service.recordRunEvent(
        "bg_test0001", PatchLoopEventType.RUN_PAUSED, "Micro6 task paused", "INFO", null);

    ArgumentCaptor<PatchLoopEvent> captor = ArgumentCaptor.forClass(PatchLoopEvent.class);
    verify(eventRepository).save(captor.capture());
    PatchLoopEvent saved = captor.getValue();
    assertThat(saved.getIterationId()).isEqualTo("bg_test0001");
    assertThat(saved.getEventType()).isEqualTo(PatchLoopEventType.RUN_PAUSED);
    JsonNode data = saved.getData();
    assertThat(data.get("iteration_id").asText()).isEqualTo("bg_test0001");
    assertThat(data.get("task_key").asText()).isEqualTo("FISHING_TRAINING");
    assertThat(data.get("display_name").asText()).isEqualTo("Fishing training run");
  }

  @Test
  @DisplayName("recordFailureEvent carries failure_code and failure_reason for the owning script")
  void recordFailureEvent_carriesContractFields() {
    when(eventRepository.findTopByIterationIdOrderByIdDesc("bg_test0002"))
        .thenReturn(Optional.empty());
    when(iterationRepository.findByIterationId("bg_test0002"))
        .thenReturn(Optional.of(iteration("bg_test0002")));

    service.recordFailureEvent(
        "bg_test0002", 42, false, "SCRIPT_EXCEPTION", "Script threw NullPointerException");

    ArgumentCaptor<PatchLoopEvent> captor = ArgumentCaptor.forClass(PatchLoopEvent.class);
    verify(eventRepository).save(captor.capture());
    PatchLoopEvent saved = captor.getValue();
    assertThat(saved.getEventType()).isEqualTo(PatchLoopEventType.SCRIPT_FAILED);
    assertThat(saved.getSeverity()).isEqualTo("ERROR");
    JsonNode data = saved.getData();
    assertThat(data.get("failure_code").asText()).isEqualTo("SCRIPT_EXCEPTION");
    assertThat(data.get("failure_reason").asText()).isEqualTo("Script threw NullPointerException");
    assertThat(data.get("entry_id").asInt()).isEqualTo(42);
    assertThat(data.get("task_key").asText()).isEqualTo("FISHING_TRAINING");
  }

  @Test
  @DisplayName("recordFailureEvent records success as SCRIPT_COMPLETED without failure fields")
  void recordFailureEvent_successRecordsCompleted() {
    when(eventRepository.findTopByIterationIdOrderByIdDesc("bg_test0003"))
        .thenReturn(Optional.empty());
    when(iterationRepository.findByIterationId("bg_test0003"))
        .thenReturn(Optional.of(iteration("bg_test0003")));

    service.recordFailureEvent("bg_test0003", 43, true, null, null);

    ArgumentCaptor<PatchLoopEvent> captor = ArgumentCaptor.forClass(PatchLoopEvent.class);
    verify(eventRepository).save(captor.capture());
    PatchLoopEvent saved = captor.getValue();
    assertThat(saved.getEventType()).isEqualTo(PatchLoopEventType.SCRIPT_COMPLETED);
    assertThat(saved.getData().has("failure_code")).isFalse();
  }

  @Test
  @DisplayName("identical consecutive events are deduplicated")
  void recordRunEvent_deduplicatesIdenticalConsecutiveEvents() {
    PatchLoopEvent latest =
        new PatchLoopEvent("bg_test0004", PatchLoopEventType.RUN_PAUSED, "Micro6 task paused");
    when(eventRepository.findTopByIterationIdOrderByIdDesc("bg_test0004"))
        .thenReturn(Optional.of(latest));

    service.recordRunEvent(
        "bg_test0004", PatchLoopEventType.RUN_PAUSED, "Micro6 task paused", "INFO", null);

    verify(eventRepository, never()).save(any(PatchLoopEvent.class));
  }

  @Test
  @DisplayName("risk events are recorded with the raw executor payload and never repeat")
  void recordRiskEvent_storesRawPayloadAndSkipsConsecutiveRepeats() {
    when(iterationRepository.findByIterationId("bg_test0005"))
        .thenReturn(Optional.of(iteration("bg_test0005")));
    Map<String, Object> riskRaw = new LinkedHashMap<>();
    riskRaw.put("level", "HIGH");
    JsonNode latestRisk = objectMapper.valueToTree(riskRaw);
    PatchLoopEvent latestRiskEvent =
        new PatchLoopEvent(
            "bg_test0005",
            PatchLoopEventType.RUN_RISK_TRIGGERED,
            "Executor reported a risk trigger");
    latestRiskEvent.setData(
        objectMapper.valueToTree(Map.of("iteration_id", "bg_test0005", "risk", latestRisk)));
    when(eventRepository.findTopByIterationIdOrderByIdDesc("bg_test0005"))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(latestRiskEvent));

    service.recordRiskEvent("bg_test0005", 44, latestRisk);

    ArgumentCaptor<PatchLoopEvent> captor = ArgumentCaptor.forClass(PatchLoopEvent.class);
    verify(eventRepository, atMostOnce()).save(captor.capture());
    assertThat(captor.getValue().getEventType()).isEqualTo(PatchLoopEventType.RUN_RISK_TRIGGERED);
    assertThat(captor.getValue().getData().get("risk").get("level").asText()).isEqualTo("HIGH");

    // A second, identical risk report in the next poll must not create a new event.
    service.recordRiskEvent("bg_test0005", 44, latestRisk);
    verify(eventRepository, atMostOnce()).save(any(PatchLoopEvent.class));
  }

  @Test
  @DisplayName("getTimelineEvents returns the chronological DTO stream")
  void getTimelineEvents_returnsChronologicalDtos() {
    PatchLoopEvent first =
        new PatchLoopEvent("bg_test0006", PatchLoopEventType.SCRIPT_STARTED, "started");
    PatchLoopEvent second =
        new PatchLoopEvent("bg_test0006", PatchLoopEventType.SCRIPT_FAILED, "failed", "ERROR");
    when(eventRepository.findByIterationIdOrderByTimestampAscIdAsc("bg_test0006"))
        .thenReturn(List.of(first, second));

    List<Map<String, Object>> timeline = service.getTimelineEvents("bg_test0006");

    assertThat(timeline).hasSize(2);
    assertThat(timeline.get(0).get("eventType")).isEqualTo("SCRIPT_STARTED");
    assertThat(timeline.get(1).get("eventType")).isEqualTo("SCRIPT_FAILED");
    assertThat(timeline.get(0))
        .containsKeys("id", "iterationId", "timestamp", "message", "severity");
  }

  @Test
  @DisplayName("recording failures never propagate to the caller")
  void recordingFailures_neverPropagate() {
    when(eventRepository.findTopByIterationIdOrderByIdDesc(anyString()))
        .thenReturn(Optional.empty());
    when(eventRepository.save(any(PatchLoopEvent.class)))
        .thenThrow(new RuntimeException("db down"));
    when(iterationRepository.findByIterationId(anyString()))
        .thenThrow(new RuntimeException("db down"));

    service.recordRunEvent(
        "bg_broken1", PatchLoopEventType.RUN_PAUSED, "Micro6 task paused", "INFO", null);
    service.recordFailureEvent("bg_broken1", 1, false, "SCRIPT_EXCEPTION", "failed");
    service.recordRiskEvent("bg_broken1", 1, objectMapper.valueToTree(Map.of("level", "HIGH")));

    // No exception reached the caller; the run-control contract holds.
  }

  @Test
  @DisplayName("a REQUIRES_NEW commit failure is contained by the public record method")
  void commitFailure_neverPropagates() {
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(transactionStatus);
    org.mockito.Mockito.doThrow(new RuntimeException("commit failed"))
        .when(transactionManager)
        .commit(transactionStatus);
    when(eventRepository.findTopByIterationIdOrderByIdDesc("bg_commit_failure"))
        .thenReturn(Optional.empty());
    when(iterationRepository.findByIterationId("bg_commit_failure"))
        .thenReturn(Optional.of(iteration("bg_commit_failure")));
    BuilderGoalRunEventService transactionalService =
        new BuilderGoalRunEventService(
            eventRepository, iterationRepository, objectMapper, transactionManager);

    assertThatCode(
            () ->
                transactionalService.recordRunEvent(
                    "bg_commit_failure",
                    PatchLoopEventType.RUN_PAUSED,
                    "Micro6 task paused",
                    "INFO",
                    null))
        .doesNotThrowAnyException();

    ArgumentCaptor<TransactionDefinition> definition =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(definition.capture());
    assertThat(definition.getValue().getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    verify(eventRepository).save(any(PatchLoopEvent.class));
  }

  @Test
  @DisplayName("a changed consecutive risk payload is recorded, not collapsed")
  void recordRiskEvent_changedPayloadIsRecorded() {
    when(iterationRepository.findByIterationId("bg_test0007"))
        .thenReturn(Optional.of(iteration("bg_test0007")));
    JsonNode firstRisk = objectMapper.valueToTree(Map.of("level", "HIGH"));
    JsonNode changedRisk = objectMapper.valueToTree(Map.of("level", "CRITICAL"));
    PatchLoopEvent latest =
        new PatchLoopEvent(
            "bg_test0007",
            PatchLoopEventType.RUN_RISK_TRIGGERED,
            "Executor reported a risk trigger");
    latest.setData(
        objectMapper.valueToTree(Map.of("iteration_id", "bg_test0007", "risk", firstRisk)));
    when(eventRepository.findTopByIterationIdOrderByIdDesc("bg_test0007"))
        .thenReturn(Optional.of(latest));

    // Same payload as the latest event -> deduplicated.
    service.recordRiskEvent("bg_test0007", 44, firstRisk);
    verify(eventRepository, never()).save(any(PatchLoopEvent.class));

    // Changed payload -> must be recorded as a new event.
    service.recordRiskEvent("bg_test0007", 44, changedRisk);
    ArgumentCaptor<PatchLoopEvent> captor = ArgumentCaptor.forClass(PatchLoopEvent.class);
    verify(eventRepository, times(1)).save(captor.capture());
    assertThat(captor.getValue().getData().get("risk").get("level").asText()).isEqualTo("CRITICAL");
  }
}
