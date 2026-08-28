package com.chromascape.web.aiplayer.routine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.aiplayer.routine.entity.BehaviorGraphDefinition;
import com.chromascape.web.aiplayer.routine.entity.RoutineAssistantConversation;
import com.chromascape.web.aiplayer.routine.repository.BehaviorGraphDefinitionRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineAssistantConversationRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Proves the Assistant generation context against a real PostgreSQL, which is the only place this
 * defect exists.
 *
 * <p>A mocked repository cannot express it. {@code BehaviorGraphDefinition.identityJson} is declared
 * {@code text} in the schema but mapped {@code @Lob}, so Hibernate writes the JSON into
 * {@code pg_largeobject} and stores only the object id in the column — a row whose identity is a
 * multi-kilobyte document literally holds the text {@code "95342"}. Three consequences, each of
 * which broke an SC-968 acceptance run on 2026-08-28:
 *
 * <ul>
 *   <li>loading the conversation off a transaction throws "Unable to access lob stream";
 *   <li>an {@code identityJson like ...} JPQL predicate binds through the large-object API and
 *       throws "Large Objects may not be used in auto-commit mode" off a transaction, then "cannot
 *       execute lo_creat() in a read-only transaction" inside a read-only one;
 *   <li>and even where it executes, it matches nothing, because it compares against the object id.
 * </ul>
 *
 * <p>Requires the local test PostgreSQL at {@code localhost:5433/chromascape_test}, matching
 * {@code application-test.yml} and the existing {@link Sc967FlywaySchemaTest} setup.
 */
// A real servlet container, not the default MOCK environment: WebSocketConfig's
// createWebSocketContainer bean needs jakarta.websocket.server.ServerContainer in the
// ServletContext, and the context fails to start without it.
@SpringBootTest(
    classes = com.chromascape.web.ChromaScapeApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RoutineAssistantGenerationContextIT {

  @Autowired private RoutineAssistantConversationStore store;
  @Autowired private RoutineAssistantConversationRepository conversations;
  @Autowired private BehaviorGraphDefinitionRepository graphs;

  private static String identityFor(String runId) {
    return "{\"schema\":\"current-slice-identity/v1\",\"run\":{\"run_id\":\""
        + runId
        + "\"},\"task\":{\"task_key\":\"FISHING_TRAINING\"}}";
  }

  @Test
  void resolvesTheRunsPublishedGraphFromOffRequestThread() throws Exception {
    String runId = "run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    String conversationId = "conv_" + UUID.randomUUID().toString().replace("-", "");
    String graphId = "graph_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    String graphHash = "sha256:" + "a".repeat(64);

    RoutineAssistantConversation conversation = new RoutineAssistantConversation();
    conversation.setConversationId(conversationId);
    conversation.setRunId(runId);
    conversation.setState("OPEN");
    // The generation message is the @Lob whose presence made loading the entity fail.
    conversation.setGenerationMessage("propose the minimal deterministic graph draft");
    conversations.save(conversation);

    BehaviorGraphDefinition graph = new BehaviorGraphDefinition();
    graph.setGraphId(graphId);
    graph.setGraphHash(graphHash);
    graph.setIdentityJson(identityFor(runId));
    graph.setDefinitionJson("{\"schema\":\"behavior-graph-definition/v1\",\"nodes\":[]}");
    graph.setState("PUBLISHED");
    graphs.save(graph);

    // The real generator runs on its own executor, not a request thread: no inherited transaction,
    // no open session, connection in auto-commit. Reproduce that exactly.
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<Map<String, Object>> future =
          executor.submit((Callable<Map<String, Object>>) () -> store.generationContext(conversationId));
      Map<String, Object> context = future.get();

      assertThat(context).containsEntry("run_id", runId);
      assertThat(context).containsEntry("graph_id", graphId);
      assertThat(context).containsEntry("graph_hash", graphHash);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void identityJsonColumnHoldsALargeObjectIdSoSqlMatchingCannotWork() throws Exception {
    String runId = "run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    String graphId = "graph_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);

    BehaviorGraphDefinition graph = new BehaviorGraphDefinition();
    graph.setGraphId(graphId);
    graph.setGraphHash("sha256:" + "b".repeat(64));
    graph.setIdentityJson(identityFor(runId));
    graph.setDefinitionJson("{\"schema\":\"behavior-graph-definition/v1\",\"nodes\":[]}");
    graph.setState("PUBLISHED");
    graphs.save(graph);

    // Reading the @Lob back outside a transaction is itself the production failure. Assert it, so
    // the reason RoutineAssistantConversationStore.generationContext must be @Transactional is
    // recorded rather than remembered.
    assertThatThrownBy(() -> graphs.findByGraphId(graphId).orElseThrow().getIdentityJson())
        .hasRootCauseMessage("Large Objects may not be used in auto-commit mode.");

    // The column does not contain the JSON, which is why no SQL predicate can ever find it.
    String url =
        System.getenv("SPRING_DATASOURCE_URL") == null
            ? "jdbc:postgresql://localhost:5433/chromascape_test?sslmode=disable"
            : System.getenv("SPRING_DATASOURCE_URL");
    try (Connection connection = DriverManager.getConnection(url, "chromascape", "chromascape123");
        Statement statement = connection.createStatement()) {
      try (ResultSet stored =
          statement.executeQuery(
              "select identity_json from behavior_graph_definitions where graph_id = '"
                  + graphId
                  + "'")) {
        assertThat(stored.next()).isTrue();
        assertThat(stored.getString(1)).doesNotContain(runId).matches("\\d+");
      }
      try (ResultSet matched =
          statement.executeQuery(
              // Cast because the column's own type varies: Flyway declares text, Hibernate's
              // create-drop declares oid. Either way it holds an object id, never the JSON.
              "select count(*) from behavior_graph_definitions"
                  + " where identity_json::text like '%"
                  + runId
                  + "%'")) {
        assertThat(matched.next()).isTrue();
        assertThat(matched.getInt(1)).isZero();
      }
      // The JSON is intact -- it simply lives in pg_largeobject, reachable only through the id.
      try (ResultSet resolved =
          statement.executeQuery(
              "select convert_from(lo_get(identity_json::oid), 'UTF8')"
                  + " from behavior_graph_definitions where graph_id = '"
                  + graphId
                  + "'")) {
        assertThat(resolved.next()).isTrue();
        assertThat(resolved.getString(1)).contains(runId);
      }
    }
  }
}
