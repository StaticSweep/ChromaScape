package com.chromascape.web.aiplayer.routine.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.aiplayer.routine.entity.BehaviorGraphDefinition;
import com.chromascape.web.aiplayer.routine.entity.RoutineAssistantConversation;
import com.chromascape.web.aiplayer.routine.repository.BehaviorGraphDefinitionRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineAssistantConversationRepository;
import com.chromascape.testsupport.IsolatedPostgresTestDatabase;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Proves the routine JSON columns hold their JSON, against a real PostgreSQL.
 *
 * <p>They did not. Every one of them was mapped {@code @Lob} on a {@code String} field, which on
 * PostgreSQL makes Hibernate write the document into {@code pg_largeobject} and store only the
 * object id — a row whose identity is a multi-kilobyte document literally held the text {@code
 * "95342"}. Three SC-968 acceptance runs on 2026-08-28 failed on the consequences: "Unable to
 * access lob stream" reading one off a transaction, then "Large Objects may not be used in
 * auto-commit mode" and "cannot execute lo_creat() in a read-only transaction" from a {@code like}
 * predicate, which matched nothing in any case because it compared against the object id. SQL over
 * these columns was silently wrong wherever it existed, and no large object was ever unlinked.
 *
 * <p>The entities now declare {@code @JdbcTypeCode(SqlTypes.LONGVARCHAR)}, which is what every
 * Flyway declaration always said, and {@code V28__sc968_inline_json_large_objects.sql} brings
 * existing rows over. A mocked repository cannot express any of this, so these run for real.
 *
 * <p>Requires the local test PostgreSQL at {@code localhost:5433/chromascape_test}, matching {@code
 * application-test.yml} and the existing {@link Sc967FlywaySchemaTest} setup.
 */
// A real servlet container, not the default MOCK environment: WebSocketConfig's
// createWebSocketContainer bean needs jakarta.websocket.server.ServerContainer in the
// ServletContext, and the context fails to start without it.
@SpringBootTest(
    classes = com.chromascape.web.ChromaScapeApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RoutineAssistantGenerationContextIT {

  private static final IsolatedPostgresTestDatabase.Database DATABASE =
      IsolatedPostgresTestDatabase.newDatabase();

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    IsolatedPostgresTestDatabase.register(registry, DATABASE);
  }

  @Autowired private RoutineAssistantConversationStore store;
  @Autowired private RoutineAssistantConversationRepository conversations;
  @Autowired private BehaviorGraphDefinitionRepository graphs;

  private static String identityFor(String runId) {
    return "{\"schema\":\"current-slice-identity/v1\",\"run\":{\"run_id\":\""
        + runId
        + "\"},\"task\":{\"task_key\":\"FISHING_TRAINING\"}}";
  }

  private static String randomId(String prefix, int length) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, length);
  }

  private BehaviorGraphDefinition publishedGraph(String graphId, String runId, String hashSeed) {
    BehaviorGraphDefinition graph = new BehaviorGraphDefinition();
    graph.setGraphId(graphId);
    graph.setGraphHash("sha256:" + hashSeed.repeat(64));
    graph.setIdentityJson(identityFor(runId));
    graph.setDefinitionJson("{\"schema\":\"behavior-graph-definition/v1\",\"nodes\":[]}");
    graph.setState("PUBLISHED");
    return graphs.save(graph);
  }

  @Test
  void resolvesTheRunsPublishedGraphFromOffRequestThread() throws Exception {
    String runId = randomId("run-", 16);
    String conversationId = randomId("conv_", 32);
    String graphId = randomId("graph_", 24);

    RoutineAssistantConversation conversation = new RoutineAssistantConversation();
    conversation.setConversationId(conversationId);
    conversation.setRunId(runId);
    conversation.setState("OPEN");
    // The generation message is the column whose presence made loading the entity fail.
    conversation.setGenerationMessage("propose the minimal deterministic graph draft");
    conversations.save(conversation);

    BehaviorGraphDefinition graph = publishedGraph(graphId, runId, "a");

    // The real generator runs on its own executor, not a request thread: no inherited transaction,
    // no open session, connection in auto-commit. Reproduce that exactly.
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<Map<String, Object>> future =
          executor.submit(
              (Callable<Map<String, Object>>) () -> store.generationContext(conversationId));
      Map<String, Object> context = future.get();

      assertThat(context).containsEntry("run_id", runId);
      assertThat(context).containsEntry("graph_id", graphId);
      assertThat(context).containsEntry("graph_hash", graph.getGraphHash());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void jsonColumnsAreReadableOutsideATransaction() {
    String runId = randomId("run-", 16);
    String graphId = randomId("graph_", 24);
    publishedGraph(graphId, runId, "b");

    // This threw "Large Objects may not be used in auto-commit mode" for as long as the column was
    // a large-object pointer, which is what forced every read behind a transaction.
    assertThat(graphs.findByGraphId(graphId).orElseThrow().getIdentityJson()).contains(runId);
  }

  @Test
  void theColumnHoldsTheJsonSoSqlOverItWorks() throws Exception {
    String runId = randomId("run-", 16);
    String graphId = randomId("graph_", 24);
    publishedGraph(graphId, runId, "c");

    String url = IsolatedPostgresTestDatabase.schemaJdbcUrl(DATABASE);
    try (Connection connection =
            DriverManager.getConnection(url, DATABASE.username(), DATABASE.password());
        Statement statement = connection.createStatement()) {
      try (ResultSet stored =
          statement.executeQuery(
              "select identity_json from behavior_graph_definitions where graph_id = '"
                  + graphId
                  + "'")) {
        assertThat(stored.next()).isTrue();
        // It used to be a bare integer here: the id of a row in pg_largeobject.
        assertThat(stored.getString(1)).contains(runId).startsWith("{");
      }
      try (ResultSet matched =
          statement.executeQuery(
              "select count(*) from behavior_graph_definitions"
                  + " where identity_json like '%"
                  + runId
                  + "%'")) {
        assertThat(matched.next()).isTrue();
        assertThat(matched.getInt(1)).isOne();
      }
      // The same shape V6's outbox backfill uses. It silently returned NULL for every row while the
      // column held a pointer, falling through to the hardcoded task key.
      try (ResultSet parsed =
          statement.executeQuery(
              "select identity_json::jsonb #>> '{task,task_key}'"
                  + " from behavior_graph_definitions where graph_id = '"
                  + graphId
                  + "'")) {
        assertThat(parsed.next()).isTrue();
        assertThat(parsed.getString(1)).isEqualTo("FISHING_TRAINING");
      }
    }
  }

  @Test
  void aDocumentLargerThanAnyVarcharBoundRoundTrips() {
    // The first attempt at removing @Lob used @JdbcTypeCode(SqlTypes.LONGVARCHAR), which the
    // PostgreSQL dialect renders as varchar(32600); with ddl-auto: update Hibernate then narrowed
    // every one of these columns from text. The next capture tick batch over that bound failed with
    // SQLSTATE 22001, the server answered 422, and the run's capture died as
    // CAPTURE_WRITE_REJECTED.
    // A tick batch is routinely larger than 32600 characters, so size is part of the contract.
    String runId = randomId("run-", 16);
    String graphId = randomId("graph_", 24);
    String padding = "x".repeat(64 * 1024);

    BehaviorGraphDefinition graph = new BehaviorGraphDefinition();
    graph.setGraphId(graphId);
    graph.setGraphHash("sha256:" + "d".repeat(64));
    graph.setIdentityJson(identityFor(runId));
    graph.setDefinitionJson(
        "{\"schema\":\"behavior-graph-definition/v1\",\"nodes\":[],\"padding\":\""
            + padding
            + "\"}");
    graph.setState("PUBLISHED");
    graphs.save(graph);

    String stored = graphs.findByGraphId(graphId).orElseThrow().getDefinitionJson();
    assertThat(stored).hasSize(graph.getDefinitionJson().length()).contains(padding);
  }
}
