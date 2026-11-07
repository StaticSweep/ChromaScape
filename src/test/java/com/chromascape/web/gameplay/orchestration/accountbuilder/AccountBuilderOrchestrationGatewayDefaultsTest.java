package com.chromascape.web.gameplay.orchestration.accountbuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * SC-1498/SC-1431 flags-off proof: the delivery boundary defaults to the closed gateway, so no
 * live Micro6 adapter is even resolved while live delivery is off.
 */
class AccountBuilderOrchestrationGatewayDefaultsTest {

  @Test
  void theClosedGatewayIsTheDefaultAndTheAdapterIsNeverResolved() {
    @SuppressWarnings("unchecked")
    ObjectProvider<Micro6AccountBuilderOrchestrationGateway> adapter = mock(ObjectProvider.class);
    AccountBuilderOrchestrationProperties properties = new AccountBuilderOrchestrationProperties();
    properties.validate();

    AccountBuilderOrchestrationGateway gateway =
        new AccountBuilderOrchestrationGatewayConfiguration()
            .accountBuilderOrchestrationGateway(properties, adapter);

    assertThat(gateway).isInstanceOf(ClosedAccountBuilderOrchestrationGateway.class);
    verifyNoInteractions(adapter);
  }
}
