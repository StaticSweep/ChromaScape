package com.chromascape.web.gameplay.orchestration.accountbuilder;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Mode;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** HTTP status regression tests for fail-closed orchestration outcomes. */
class AccountBuilderOrchestrationControllerTest {
  private static final String OPERATOR_KEY = "x".repeat(32);

  @Test
  void validDisabledMutationReturnsServiceUnavailable() {
    AccountBuilderOrchestrationController controller = controller(false);

    var response =
        controller.orchestrate(
            OPERATOR_KEY,
            AccountBuilderPostgresTestFixtures.freshRequest("disabled-http", Mode.LIVE_TASK));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().failureCode())
        .isEqualTo(AccountBuilderOrchestrationModels.FailureCode.RUNTIME_DISABLED);
  }

  @Test
  void heldMutationRemainsCreatedAndConsumesNoAuthority() {
    AccountBuilderOrchestrationController controller = controller(true);

    var response =
        controller.orchestrate(
            OPERATOR_KEY, AccountBuilderPostgresTestFixtures.freshRequest("held-http", Mode.HELD));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().authorityConsumed()).isFalse();
  }

  private AccountBuilderOrchestrationController controller(boolean enabled) {
    AccountBuilderOrchestrationProperties properties = new AccountBuilderOrchestrationProperties();
    properties.setEnabled(enabled);
    properties.setLiveDeliveryEnabled(false);
    properties.setOperatorKey(OPERATOR_KEY);
    var validator =
        new AccountBuilderOrchestrationValidator(
            AccountBuilderPostgresTestFixtures.CANONICAL_JSON,
            Clock.systemUTC(),
            (authority, envelope) -> true);
    var service =
        new AccountBuilderOrchestrationService(
            validator,
            AccountBuilderPostgresTestFixtures.CANONICAL_JSON,
            new InMemoryAccountBuilderOrchestrationStore(Clock.systemUTC()),
            new ClosedAccountBuilderOrchestrationGateway(),
            properties,
            Clock.systemUTC());
    return new AccountBuilderOrchestrationController(service, properties);
  }
}
