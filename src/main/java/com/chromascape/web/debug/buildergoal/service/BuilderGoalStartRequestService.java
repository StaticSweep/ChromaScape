package com.chromascape.web.debug.buildergoal.service;

import com.chromascape.web.artifact.ImmutableArtifactStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A durable request is reserved, bound and committed with its run, never with transport receipt.
 */
@Service
public class BuilderGoalStartRequestService {
  private final JdbcTemplate jdbc;

  public BuilderGoalStartRequestService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public String reserve(String requestId, String fingerprint) {
    requireTransaction();
    if (requestId == null || requestId.isBlank() || requestId.length() > 256)
      throw failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_ID");
    jdbc.update(
        "INSERT INTO builder_goal_start_request (request_id, fingerprint) VALUES (?, ?) ON CONFLICT (request_id) DO NOTHING",
        requestId,
        fingerprint);
    return jdbc.queryForObject(
        "SELECT fingerprint, iteration_id FROM builder_goal_start_request WHERE request_id = ? FOR UPDATE",
        (rs, row) -> {
          if (!fingerprint.equals(rs.getString("fingerprint")))
            throw failure(HttpStatus.CONFLICT, "REQUEST_IDENTITY_CONFLICT");
          return rs.getString("iteration_id");
        },
        requestId);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void bind(String requestId, String iterationId) {
    requireTransaction();
    if (jdbc.update(
            "UPDATE builder_goal_start_request SET iteration_id = ? WHERE request_id = ? AND iteration_id IS NULL",
            iterationId,
            requestId)
        != 1) throw failure(HttpStatus.CONFLICT, "REQUEST_BINDING_CONFLICT");
  }

  public static String requestId(JsonNode payload) {
    if (payload == null) return null;
    String selected = null;
    for (String field : new String[] {"request_id", "requestId"}) {
      JsonNode value = payload.get(field);
      if (value == null || value.isNull()) continue;
      if (!value.isTextual()) throw failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_ID");
      String id = value.asText().trim();
      if (id.isEmpty()) continue;
      if (id.length() > 256) throw failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_ID");
      if (selected != null && !selected.equals(id))
        throw failure(HttpStatus.BAD_REQUEST, "REQUEST_ID_ALIAS_CONFLICT");
      selected = id;
    }
    return selected;
  }

  public static String fingerprint(ObjectMapper mapper, Map<String, Object> identity) {
    try {
      return ImmutableArtifactStore.checksum(
          mapper.writeValueAsBytes(canonical(mapper.valueToTree(identity))));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid request identity", e);
    }
  }

  private static JsonNode canonical(JsonNode value) {
    if (value.isObject()) {
      var result = JsonNodeFactory.instance.objectNode();
      var fields = new TreeMap<String, JsonNode>();
      value.fields().forEachRemaining(e -> fields.put(e.getKey(), e.getValue()));
      fields.forEach((key, child) -> result.set(key, canonical(child)));
      return result;
    }
    if (value.isArray()) {
      var result = JsonNodeFactory.instance.arrayNode();
      value.forEach(child -> result.add(canonical(child)));
      return result;
    }
    if (value.isNumber())
      return JsonNodeFactory.instance.numberNode(value.decimalValue().stripTrailingZeros());
    return value;
  }

  private void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))
      throw new IllegalStateException("Request identity requires the run creation transaction");
  }

  private static BuilderGoalStartException failure(HttpStatus status, String code) {
    return new BuilderGoalStartException(
        status, code, "REQUEST_RESERVATION", code, null, null, null, null);
  }
}
