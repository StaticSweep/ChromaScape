package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.orchestration.v2.Micro6GameplayV2Gateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Gateway selection for the SC-1431 delivery boundary.
 *
 * <p>The closed gateway is the default: it is registered whenever the reviewed live-delivery
 * property ({@code gameplay.accountbuilder-orchestration.live-delivery-enabled}) is absent or
 * false. The Micro6 SC-1449 adapter is registered only when that property is explicitly enabled.
 * The two registrations are mutually exclusive by construction and a single factory method returns
 * the active gateway, so a configuration that would activate both gateways fails startup with an
 * ambiguous-bean error instead of silently choosing one.
 */
@Configuration
class AccountBuilderOrchestrationGatewayConfiguration {

  @Bean
  AccountBuilderOrchestrationGateway accountBuilderOrchestrationGateway(
      AccountBuilderOrchestrationProperties properties,
      ObjectProvider<Micro6AccountBuilderOrchestrationGateway> micro6Adapter) {
    if (properties.isLiveDeliveryEnabled()) {
      return micro6Adapter.getObject();
    }
    return new ClosedAccountBuilderOrchestrationGateway();
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "gameplay.accountbuilder-orchestration",
      name = "live-delivery-enabled",
      havingValue = "true")
  Micro6AccountBuilderOrchestrationGateway micro6AccountBuilderOrchestrationGateway(
      Micro6GameplayV2Gateway transport,
      CanonicalGameplayJson canonicalJson,
      ObjectMapper objectMapper) {
    return new Micro6AccountBuilderOrchestrationGateway(
        transport, canonicalJson, objectMapper, Clock.systemUTC());
  }
}
