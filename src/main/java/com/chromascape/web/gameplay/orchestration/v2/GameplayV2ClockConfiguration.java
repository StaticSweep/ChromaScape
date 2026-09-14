package com.chromascape.web.gameplay.orchestration.v2;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Shared production clock for durable v2 gate timestamps. */
@Configuration
public class GameplayV2ClockConfiguration {
  @Bean
  Clock gameplayV2Clock() {
    return Clock.systemUTC();
  }
}
