package com.chromascape.web.gameplay.orchestration.accountbuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.testsupport.IsolatedPostgresTestDatabase;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Lease;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.State;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** PostgreSQL fault-injection proof that normalized writes roll back with the root row. */
@SpringBootTest(
    classes = JpaAccountBuilderOrchestrationStoreTest.JpaTestBootstrap.class,
    properties = {
      "spring.flyway.target=32",
      "spring.jpa.hibernate.ddl-auto=validate",
      "stabilization.auth.token=sc1431-read-token",
      "stabilization.auth.write-token=sc1431-write-token",
      "gameplay.bootstrap.authority-hmac-secret=sc1431-test-authority-secret-32-bytes"
    })
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles({"test", "sc1431-jpa"})
class AccountBuilderOrchestrationRollbackTest {
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private JpaAccountBuilderOrchestrationStore store;

  private String activeFunction;
  private String activeTrigger;
  private String activeTable;

  @DynamicPropertySource
  static void isolatedPostgres(DynamicPropertyRegistry registry) {
    IsolatedPostgresTestDatabase.register(registry);
  }

  @AfterEach
  void removeFault() {
    if (activeTrigger != null) {
      jdbcTemplate.execute("drop trigger if exists " + activeTrigger + " on " + activeTable);
      jdbcTemplate.execute("drop function if exists " + activeFunction + "()");
      activeFunction = null;
      activeTrigger = null;
      activeTable = null;
    }
  }

  @Test
  void budgetInsertFailureRollsBackTheEntireClaimAggregate() {
    OrchestrationRequest request = AccountBuilderPostgresTestFixtures.request("rollback-budget");
    enableFault("budget", "ab_orchestration_budget");

    assertThatThrownBy(
            () ->
                store.claim(
                    request,
                    request.requestFingerprint(),
                    AccountBuilderPostgresTestFixtures.envelopeDigest(request),
                    AccountBuilderPostgresTestFixtures.budget(request)))
        .isInstanceOf(RuntimeException.class);

    // The atomic claim must leave no root, snapshot, correlation, budget or event behind.
    for (String table :
        List.of(
            "ab_orchestration",
            "ab_orchestration_budget",
            "ab_snapshot_binding",
            "ab_gateway_correlation",
            "ab_orchestration_event")) {
      assertThat(
              jdbcTemplate.queryForObject(
                  "select count(*) from " + table + " where orchestration_id = ?",
                  Integer.class,
                  request.orchestrationId()))
          .as(table)
          .isZero();
    }
  }

  @Test
  void receiptInsertFailureRollsBackRootLeaseReceiptAndEvent() {
    OrchestrationRequest request = prepareAuthorized("rollback-receipt");
    Lease lease = AccountBuilderPostgresTestFixtures.lease(request, "rollback-receipt");
    var receipt =
        AccountBuilderPostgresTestFixtures.receipt(
            request, "RESERVATION", State.RESERVED, FailureCode.NONE, lease, false, false);
    DatabaseSnapshot before = snapshot(request.orchestrationId());
    enableFault("receipt", "ab_orchestration_receipt");

    assertThatThrownBy(
            () ->
                store.transition(
                    request.orchestrationId(),
                    State.RESERVED,
                    FailureCode.NONE,
                    lease,
                    receipt,
                    false,
                    false))
        .isInstanceOf(RuntimeException.class);

    assertUnchanged(before);
  }

  @Test
  void eventInsertFailureRollsBackTheLifecycleTransition() {
    OrchestrationRequest request = AccountBuilderPostgresTestFixtures.request("rollback-event");
    claim(request);
    DatabaseSnapshot before = snapshot(request.orchestrationId());
    enableFault("event", "ab_orchestration_event");

    assertThatThrownBy(
            () ->
                store.transition(
                    request.orchestrationId(),
                    State.VALIDATED,
                    FailureCode.NONE,
                    null,
                    null,
                    false,
                    false))
        .isInstanceOf(RuntimeException.class);

    assertUnchanged(before);
  }

  @Test
  void terminalInsertFailureRollsBackTheTerminalTransition() {
    OrchestrationRequest request = AccountBuilderPostgresTestFixtures.request("rollback-terminal");
    claim(request);
    store.transition(
        request.orchestrationId(), State.VALIDATED, FailureCode.NONE, null, null, false, false);
    DatabaseSnapshot before = snapshot(request.orchestrationId());
    enableFault("terminal", "ab_orchestration_terminal_result");

    assertThatThrownBy(
            () ->
                store.transition(
                    request.orchestrationId(),
                    State.DENIED,
                    FailureCode.RUNTIME_DISABLED,
                    null,
                    null,
                    false,
                    false))
        .isInstanceOf(RuntimeException.class);

    assertUnchanged(before);
  }

  @Test
  void leaseInsertFailureRollsBackTheReservationBoundary() {
    OrchestrationRequest request = prepareAuthorized("rollback-lease");
    Lease lease = AccountBuilderPostgresTestFixtures.lease(request, "rollback-lease");
    DatabaseSnapshot before = snapshot(request.orchestrationId());
    enableFault("lease", "ab_orchestration_lease");

    assertThatThrownBy(
            () ->
                store.transition(
                    request.orchestrationId(),
                    State.RESERVED,
                    FailureCode.NONE,
                    lease,
                    null,
                    false,
                    false))
        .isInstanceOf(RuntimeException.class);

    assertUnchanged(before);
  }

  @Test
  void authorityInsertFailureAfterRootLockRollsBackOwnershipAndRootMarker() {
    OrchestrationRequest request = AccountBuilderPostgresTestFixtures.request("rollback-authority");
    claim(request);
    store.transition(
        request.orchestrationId(), State.VALIDATED, FailureCode.NONE, null, null, false, false);
    DatabaseSnapshot before = snapshot(request.orchestrationId());
    enableFault("authority", "ab_authority_consumption");

    assertThatThrownBy(
            () ->
                store.consumeAuthority(
                    request.orchestrationId(),
                    request.authority(),
                    AccountBuilderPostgresTestFixtures.envelopeDigest(request),
                    request))
        .isInstanceOf(RuntimeException.class);

    assertUnchanged(before);
  }

  private OrchestrationRequest prepareAuthorized(String suffix) {
    OrchestrationRequest request = AccountBuilderPostgresTestFixtures.request(suffix);
    claim(request);
    store.transition(
        request.orchestrationId(), State.VALIDATED, FailureCode.NONE, null, null, false, false);
    store.consumeAuthority(
        request.orchestrationId(),
        request.authority(),
        AccountBuilderPostgresTestFixtures.envelopeDigest(request),
        request);
    store.transition(
        request.orchestrationId(), State.AUTHORIZED, FailureCode.NONE, null, null, false, false);
    return request;
  }

  private void claim(OrchestrationRequest request) {
    assertThat(
            store
                .claim(
                    request,
                    request.requestFingerprint(),
                    AccountBuilderPostgresTestFixtures.envelopeDigest(request),
                    AccountBuilderPostgresTestFixtures.budget(request))
                .created())
        .isTrue();
  }

  private void enableFault(String suffix, String table) {
    activeFunction = "sc1431_test_fault_" + suffix;
    activeTrigger = activeFunction + "_trigger";
    activeTable = table;
    jdbcTemplate.execute(
        "create function "
            + activeFunction
            + "() returns trigger language plpgsql as $$ begin raise exception "
            + "'SC-1431 injected normalized write failure'; return new; end; $$");
    jdbcTemplate.execute(
        "create trigger "
            + activeTrigger
            + " before insert on "
            + activeTable
            + " for each row execute function "
            + activeFunction
            + "()");
  }

  private DatabaseSnapshot snapshot(String orchestrationId) {
    RootSnapshot root =
        jdbcTemplate.queryForObject(
            "select state, failure_code, retry_allowed, view_version, entity_version,"
                + " updated_at, view_json from ab_orchestration where orchestration_id=?",
            (result, row) ->
                new RootSnapshot(
                    result.getString("state"),
                    result.getString("failure_code"),
                    result.getBoolean("retry_allowed"),
                    result.getLong("view_version"),
                    result.getLong("entity_version"),
                    result.getTimestamp("updated_at"),
                    result.getString("view_json")),
            orchestrationId);
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String table :
        List.of(
            "ab_snapshot_binding",
            "ab_gateway_correlation",
            "ab_authority_consumption",
            "ab_orchestration_lease",
            "ab_orchestration_receipt",
            "ab_orchestration_event",
            "ab_orchestration_terminal_result")) {
      counts.put(
          table,
          jdbcTemplate.queryForObject(
              "select count(*) from " + table + " where orchestration_id=?",
              Integer.class,
              orchestrationId));
    }
    return new DatabaseSnapshot(root, counts);
  }

  private void assertUnchanged(DatabaseSnapshot before) {
    assertThat(snapshot(before.root().orchestrationId())).isEqualTo(before);
  }

  private record DatabaseSnapshot(RootSnapshot root, Map<String, Integer> counts) {
    private DatabaseSnapshot {
      counts = Map.copyOf(counts);
    }
  }

  private record RootSnapshot(
      String state,
      String failureCode,
      boolean retryAllowed,
      long viewVersion,
      long entityVersion,
      Timestamp updatedAt,
      String viewJson) {
    private String orchestrationId() {
      int start = viewJson.indexOf("\"orchestrationId\":\"") + "\"orchestrationId\":\"".length();
      int end = viewJson.indexOf('"', start);
      return viewJson.substring(start, end);
    }
  }
}
