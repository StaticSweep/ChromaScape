package com.chromascape.web.gameplay.orchestration.accountbuilder;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.testsupport.IsolatedPostgresTestDatabase;
import com.chromascape.testsupport.IsolatedPostgresTestDatabase.Database;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.CapabilitySnapshot;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayCorrelation;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayReceipt;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Lease;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Mode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationView;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.State;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/** Committed two-context restart proof against the real Flyway schema. */
class AccountBuilderOrchestrationRestartTest {

  @Test
  void committedEvidenceSurvivesContextRestartAndRecoveryNeverRedispatches() throws Exception {
    Database database = IsolatedPostgresTestDatabase.newDatabase();
    String url = database.jdbcUrl() + "?currentSchema=" + database.schema();
    try {
      Flyway flyway =
          Flyway.configure()
              .dataSource(url, database.username(), database.password())
              .schemas(database.schema())
              .defaultSchema(database.schema())
              .target(MigrationVersion.LATEST)
              .locations("classpath:db/migration")
              .load();
      flyway.migrate();
      assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("47");

      Map<String, CommittedAggregate> before;
      try (ConfigurableApplicationContext contextA = start(database)) {
        JpaAccountBuilderOrchestrationStore storeA =
            contextA.getBean(JpaAccountBuilderOrchestrationStore.class);
        before = populate(storeA, database);
      }

      try (ConfigurableApplicationContext contextB = start(database)) {
        JpaAccountBuilderOrchestrationStore storeB =
            contextB.getBean(JpaAccountBuilderOrchestrationStore.class);
        for (Map.Entry<String, CommittedAggregate> entry : before.entrySet()) {
          CommittedAggregate after = read(storeB, database, entry.getKey());
          assertThat(after).isEqualTo(entry.getValue());
        }

        RecoveryGateway gateway = new RecoveryGateway();
        AccountBuilderOrchestrationService service = service(storeB, gateway);
        List<OrchestrationView> recovered = service.recoverAfterRestart();

        assertThat(recovered).hasSize(6);
        assertThat(gateway.reservations).hasValue(0);
        assertThat(gateway.dispatches).hasValue(0);

        OrchestrationView noLease = service.reconcile("orchestration-restart-no-lease");
        assertThat(noLease.state()).isEqualTo(State.DENIED);
        assertThat(noLease.failureCode()).isEqualTo(FailureCode.RESERVATION_REJECTED);
        assertThat(gateway.reservationLookups).hasValue(1);

        OrchestrationView leased = service.reconcile("orchestration-restart-needs-lease");
        assertThat(leased.state()).isEqualTo(State.CONFIRMED);
        assertThat(gateway.leasedReconciliations).hasValue(1);
        assertThat(gateway.reservations).hasValue(0);
        assertThat(gateway.dispatches).hasValue(0);
      }
    } finally {
      dropSchema(database);
    }
  }

  private ConfigurableApplicationContext start(Database database) {
    SpringApplication application =
        new SpringApplication(JpaAccountBuilderOrchestrationStoreTest.JpaTestApplication.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setRegisterShutdownHook(false);
    return application.run(
        "--spring.datasource.url=" + database.jdbcUrl() + "?currentSchema=" + database.schema(),
        "--spring.datasource.username=" + database.username(),
        "--spring.datasource.password=" + database.password(),
        "--spring.datasource.hikari.schema=" + database.schema(),
        "--spring.flyway.schemas=" + database.schema(),
        "--spring.flyway.default-schema=" + database.schema(),
        "--spring.flyway.target=32",
        "--spring.flyway.create-schemas=true",
        "--spring.flyway.locations=classpath:db/migration",
        "--spring.jpa.properties.hibernate.default_schema=" + database.schema(),
        "--spring.jpa.hibernate.ddl-auto=validate",
        "--spring.profiles.active=sc1431-jpa",
        "--stabilization.auth.token=sc1431-read-token",
        "--stabilization.auth.write-token=sc1431-write-token",
        "--gameplay.bootstrap.authority-hmac-secret=" + AccountBuilderPostgresTestFixtures.SECRET);
  }

  private Map<String, CommittedAggregate> populate(
      JpaAccountBuilderOrchestrationStore store, Database database) {
    Map<String, CommittedAggregate> result = new LinkedHashMap<>();

    OrchestrationRequest held =
        AccountBuilderPostgresTestFixtures.request("restart-held", Mode.HELD);
    claim(store, held);
    transition(store, held, State.VALIDATED, FailureCode.NONE, null, null);
    transition(store, held, State.HELD, FailureCode.NONE, null, null);
    result.put(held.orchestrationId(), read(store, database, held.orchestrationId()));

    OrchestrationRequest authorized =
        AccountBuilderPostgresTestFixtures.request("restart-authorized");
    authorize(store, authorized);
    result.put(authorized.orchestrationId(), read(store, database, authorized.orchestrationId()));

    OrchestrationRequest reserved = AccountBuilderPostgresTestFixtures.request("restart-reserved");
    Lease reservedLease = authorizeAndReserve(store, reserved);
    result.put(reserved.orchestrationId(), read(store, database, reserved.orchestrationId()));

    OrchestrationRequest dispatchSubmitted =
        AccountBuilderPostgresTestFixtures.request("restart-dispatch-submitted");
    Lease dispatchLease = authorizeAndReserve(store, dispatchSubmitted);
    transition(
        store, dispatchSubmitted, State.DISPATCH_SUBMITTED, FailureCode.NONE, dispatchLease, null);
    result.put(
        dispatchSubmitted.orchestrationId(),
        read(store, database, dispatchSubmitted.orchestrationId()));

    OrchestrationRequest active = AccountBuilderPostgresTestFixtures.request("restart-active");
    Lease activeLease = authorizeAndReserve(store, active);
    transition(store, active, State.DISPATCH_SUBMITTED, FailureCode.NONE, activeLease, null);
    transition(
        store,
        active,
        State.ACTIVE,
        FailureCode.NONE,
        activeLease,
        AccountBuilderPostgresTestFixtures.receipt(
            active, "DISPATCH", State.ACTIVE, FailureCode.NONE, activeLease, true, false));
    result.put(active.orchestrationId(), read(store, database, active.orchestrationId()));

    OrchestrationRequest needsLease =
        AccountBuilderPostgresTestFixtures.request("restart-needs-lease");
    Lease needsLeaseTuple = authorizeAndReserve(store, needsLease);
    transition(
        store, needsLease, State.DISPATCH_SUBMITTED, FailureCode.NONE, needsLeaseTuple, null);
    transition(
        store,
        needsLease,
        State.ACTIVE,
        FailureCode.NONE,
        needsLeaseTuple,
        AccountBuilderPostgresTestFixtures.receipt(
            needsLease, "DISPATCH", State.ACTIVE, FailureCode.NONE, needsLeaseTuple, true, false));
    transition(
        store,
        needsLease,
        State.NEEDS_RECONCILIATION,
        FailureCode.DELIVERY_UNCERTAIN,
        needsLeaseTuple,
        AccountBuilderPostgresTestFixtures.receipt(
            needsLease,
            "STATUS",
            State.ACTIVE,
            FailureCode.DELIVERY_UNCERTAIN,
            needsLeaseTuple,
            true,
            false));
    result.put(needsLease.orchestrationId(), read(store, database, needsLease.orchestrationId()));

    OrchestrationRequest noLease = AccountBuilderPostgresTestFixtures.request("restart-no-lease");
    authorize(store, noLease);
    transition(
        store, noLease, State.NEEDS_RECONCILIATION, FailureCode.DELIVERY_UNCERTAIN, null, null);
    result.put(noLease.orchestrationId(), read(store, database, noLease.orchestrationId()));

    OrchestrationRequest terminal = AccountBuilderPostgresTestFixtures.request("restart-terminal");
    claim(store, terminal);
    transition(store, terminal, State.VALIDATED, FailureCode.NONE, null, null);
    transition(store, terminal, State.DENIED, FailureCode.RUNTIME_DISABLED, null, null);
    result.put(terminal.orchestrationId(), read(store, database, terminal.orchestrationId()));

    assertThat(reservedLease).isNotNull();
    return result;
  }

  private AccountBuilderOrchestrationService service(
      JpaAccountBuilderOrchestrationStore store, RecoveryGateway gateway) {
    AccountBuilderOrchestrationValidator validator =
        new AccountBuilderOrchestrationValidator(
            AccountBuilderPostgresTestFixtures.CANONICAL_JSON,
            AccountBuilderPostgresTestFixtures.CLOCK,
            (authority, envelope) -> true);
    AccountBuilderOrchestrationProperties properties = new AccountBuilderOrchestrationProperties();
    properties.setEnabled(true);
    properties.setLiveDeliveryEnabled(true);
    properties.setOperatorKey("x".repeat(32));
    return new AccountBuilderOrchestrationService(
        validator,
        AccountBuilderPostgresTestFixtures.CANONICAL_JSON,
        store,
        gateway,
        properties,
        AccountBuilderPostgresTestFixtures.CLOCK);
  }

  private void authorize(JpaAccountBuilderOrchestrationStore store, OrchestrationRequest request) {
    claim(store, request);
    transition(store, request, State.VALIDATED, FailureCode.NONE, null, null);
    store.consumeAuthority(
        request.orchestrationId(),
        request.authority(),
        AccountBuilderPostgresTestFixtures.envelopeDigest(request),
        request);
    transition(store, request, State.AUTHORIZED, FailureCode.NONE, null, null);
  }

  private Lease authorizeAndReserve(
      JpaAccountBuilderOrchestrationStore store, OrchestrationRequest request) {
    authorize(store, request);
    Lease lease = AccountBuilderPostgresTestFixtures.lease(request, request.orchestrationId());
    transition(
        store,
        request,
        State.RESERVED,
        FailureCode.NONE,
        lease,
        AccountBuilderPostgresTestFixtures.receipt(
            request, "RESERVATION", State.RESERVED, FailureCode.NONE, lease, false, false));
    return lease;
  }

  private void claim(JpaAccountBuilderOrchestrationStore store, OrchestrationRequest request) {
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

  private void transition(
      JpaAccountBuilderOrchestrationStore store,
      OrchestrationRequest request,
      State state,
      FailureCode failureCode,
      Lease lease,
      GatewayReceipt receipt) {
    store.transition(request.orchestrationId(), state, failureCode, lease, receipt, false, false);
  }

  private CommittedAggregate read(
      JpaAccountBuilderOrchestrationStore store, Database database, String orchestrationId) {
    return new CommittedAggregate(
        store.readAggregate(orchestrationId), eventCount(database, orchestrationId));
  }

  private int eventCount(Database database, String orchestrationId) {
    try (Connection connection =
            DriverManager.getConnection(
                database.jdbcUrl() + "?currentSchema=" + database.schema(),
                database.username(),
                database.password());
        var query =
            connection.prepareStatement(
                "select count(*) from \""
                    + database.schema()
                    + "\".ab_orchestration_event where orchestration_id=?")) {
      query.setString(1, orchestrationId);
      try (var rows = query.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }

  private void dropSchema(Database database) throws Exception {
    try (Connection connection =
            DriverManager.getConnection(
                database.jdbcUrl(), database.username(), database.password());
        Statement statement = connection.createStatement()) {
      statement.execute("drop schema if exists \"" + database.schema() + "\" cascade");
    }
  }

  private record CommittedAggregate(
      AccountBuilderOrchestrationStore.NormalizedAggregate aggregate, int eventCount) {}

  private static final class RecoveryGateway implements AccountBuilderOrchestrationGateway {
    private final AtomicInteger reservations = new AtomicInteger();
    private final AtomicInteger dispatches = new AtomicInteger();
    private final AtomicInteger reservationLookups = new AtomicInteger();
    private final AtomicInteger leasedReconciliations = new AtomicInteger();

    @Override
    public CapabilitySnapshot capabilities(OrchestrationRequest request) {
      throw new AssertionError("restart recovery must not read gateway capabilities");
    }

    @Override
    public GatewayReceipt reserve(OrchestrationRequest request, String envelopeDigest) {
      reservations.incrementAndGet();
      throw new AssertionError("restart recovery must not reserve");
    }

    @Override
    public GatewayReceipt dispatch(
        OrchestrationRequest request, String envelopeDigest, Lease lease) {
      dispatches.incrementAndGet();
      throw new AssertionError("restart recovery must not dispatch");
    }

    @Override
    public GatewayReceipt status(GatewayCorrelation correlation, Lease lease) {
      throw new AssertionError("restart recovery must use reconciliation");
    }

    @Override
    public GatewayReceipt stop(
        GatewayCorrelation correlation, Lease lease, String reason, boolean killSwitch) {
      throw new AssertionError("restart recovery must not stop");
    }

    @Override
    public GatewayReceipt reconcile(GatewayCorrelation correlation, Lease lease) {
      leasedReconciliations.incrementAndGet();
      return AccountBuilderPostgresTestFixtures.correlationReceipt(
          correlation, "RECONCILE", State.CONFIRMED, FailureCode.NONE, lease, true, true);
    }

    @Override
    public GatewayReceipt reconcileReservation(GatewayCorrelation correlation) {
      reservationLookups.incrementAndGet();
      return AccountBuilderPostgresTestFixtures.correlationReceipt(
          correlation,
          "RESERVATION_RECONCILE",
          State.DENIED,
          FailureCode.RESERVATION_REJECTED,
          null,
          true,
          true);
    }
  }
}
