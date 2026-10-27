package com.chromascape.web.aiplayer.routine.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chromascape.web.aiplayer.routine.controller.CandidateExecutionController;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The SC-2106 R9 execution endpoints. Lives beside the service so it can build the service's
 * package-private error type.
 */
class CandidateExecutionControllerTest {
  private static final String BASE = "/api/v1/ai-routine/patches/proposal-1/executions";

  private final CandidateExecutionJobService service = mock(CandidateExecutionJobService.class);
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new CandidateExecutionController(service)).build();

  @Test
  void submittingReturnsAcceptedWithTheJobLocation() throws Exception {
    when(service.submit(eq("proposal-1"), any())).thenReturn(jobStatus("key 1", "QUEUED"));

    mvc.perform(
            post(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jobId\":\"job-1\",\"idempotencyKey\":\"key 1\"}"))
        .andExpect(status().isAccepted())
        .andExpect(header().string("Location", BASE + "/key+1"))
        .andExpect(jsonPath("$.lifecycle").value("QUEUED"))
        .andExpect(jsonPath("$.attemptId").value("attempt-1"));
  }

  @Test
  void statusAndControlsAddressTheJobByItsKey() throws Exception {
    when(service.status("proposal-1", "key-1")).thenReturn(jobStatus("key-1", "RUNNING"));
    when(service.requestPause("proposal-1", "key-1", true))
        .thenReturn(jobStatus("key-1", "PAUSED"));
    when(service.requestPause("proposal-1", "key-1", false))
        .thenReturn(jobStatus("key-1", "RUNNING"));
    when(service.requestCancel("proposal-1", "key-1")).thenReturn(jobStatus("key-1", "RUNNING"));

    mvc.perform(get(BASE + "/key-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lifecycle").value("RUNNING"));
    mvc.perform(post(BASE + "/key-1/pause"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lifecycle").value("PAUSED"));
    mvc.perform(post(BASE + "/key-1/resume")).andExpect(status().isOk());
    mvc.perform(post(BASE + "/key-1/cancel")).andExpect(status().isOk());
    verify(service).requestCancel("proposal-1", "key-1");
  }

  @Test
  void aRecoveryIsSubmittedAgainstThePreviousJobsKey() throws Exception {
    when(service.submitRecovery(eq("proposal-1"), eq("key-crashed"), any()))
        .thenReturn(jobStatus("key-recovery", "QUEUED"));

    mvc.perform(
            post(BASE + "/key-crashed/recover")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jobId\":\"job-r\",\"idempotencyKey\":\"key-recovery\"}"))
        .andExpect(status().isAccepted())
        .andExpect(header().string("Location", BASE + "/key-recovery"));
  }

  @Test
  void serviceRefusalsKeepTheirStatusAndCode() throws Exception {
    when(service.status("proposal-1", "missing"))
        .thenThrow(
            new PatchCandidateWorkflowService.PatchWorkflowException(
                HttpStatus.NOT_FOUND, "EXECUTION_JOB_NOT_FOUND", "no job missing"));

    mvc.perform(get(BASE + "/missing"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("EXECUTION_JOB_NOT_FOUND"));
  }

  private static CandidateExecutionJobService.JobStatus jobStatus(String key, String lifecycle) {
    return new CandidateExecutionJobService.JobStatus(
        "attempt-1",
        "job-1",
        key,
        CandidateExecutionJobService.Lifecycle.valueOf(lifecycle),
        CandidateAttemptStore.State.RUNNING,
        false,
        "PAUSED".equals(lifecycle),
        "PAUSED".equals(lifecycle),
        null,
        null,
        null,
        List.of(),
        Map.of(),
        null,
        null);
  }
}
