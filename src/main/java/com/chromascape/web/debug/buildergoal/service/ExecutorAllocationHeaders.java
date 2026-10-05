package com.chromascape.web.debug.buildergoal.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpRequest;
import java.util.Map;

/** Stamps the allocation selected by the server; callers cannot override these headers. */
public final class ExecutorAllocationHeaders {
  private ExecutorAllocationHeaders() {}

  public static HttpRequest apply(HttpRequest request, JsonNode identity) {
    if (identity == null || identity.path("endpoint_ref").asText().isBlank()) return request;
    Map<String, String> names =
        Map.of(
            "endpoint_ref",
            "X-Executor-Endpoint-Ref",
            "allocation_id",
            "X-Executor-Allocation-Id",
            "fencing_generation",
            "X-Executor-Generation",
            "executor_instance_id",
            "X-Executor-Instance-Id",
            "artifact_sha256",
            "X-Executor-Artifact-Sha256");
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(
            request,
            (name, value) -> !name.toLowerCase(java.util.Locale.ROOT).startsWith("x-executor-"));
    for (var entry : names.entrySet()) {
      String value = identity.path(entry.getKey()).asText();
      if (value.isBlank() || value.length() > 256 || !value.matches("[A-Za-z0-9_.:-]+"))
        throw new IllegalStateException("ENDPOINT_CONTEXT_INVALID");
      builder.header(entry.getValue(), value);
    }
    return builder.build();
  }
}
