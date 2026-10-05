package com.chromascape.web.debug.buildergoal.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutorAllocationHeadersTest {
  @Test
  void onlyServerSelectedAllocationMayStampExecutorHeaders() {
    var identity =
        new ObjectMapper()
            .valueToTree(
                Map.of(
                    "endpoint_ref",
                    "alloc-micro6:ref",
                    "allocation_id",
                    "allocation",
                    "fencing_generation",
                    2,
                    "executor_instance_id",
                    "executor",
                    "artifact_sha256",
                    "sha256:" + "a".repeat(64)));
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:8098/api/v1/execute"))
            .header("X-Executor-Generation", "999")
            .GET()
            .build();
    var qualified = ExecutorAllocationHeaders.apply(request, identity);
    assertEquals("2", qualified.headers().firstValue("X-Executor-Generation").orElseThrow());
    assertEquals(
        "allocation", qualified.headers().firstValue("X-Executor-Allocation-Id").orElseThrow());
    assertEquals(1, qualified.headers().allValues("X-Executor-Generation").size());
  }
}
