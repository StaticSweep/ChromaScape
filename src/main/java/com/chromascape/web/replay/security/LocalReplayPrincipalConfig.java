package com.chromascape.web.replay.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Restricts the local-development principal bridge to replay API requests. */
@Configuration
public class LocalReplayPrincipalConfig {

  @Bean
  public FilterRegistrationBean<LocalReplayPrincipalFilter> localReplayPrincipalFilterRegistration(
      LocalReplayPrincipalFilter filter) {
    FilterRegistrationBean<LocalReplayPrincipalFilter> registration =
        new FilterRegistrationBean<>();
    registration.setFilter(filter);
    registration.addUrlPatterns("/api/replay/*", "/api/recordings/*", "/api/xrsps/sessions/*");
    registration.setName("localReplayPrincipalFilter");
    registration.setOrder(1);
    return registration;
  }
}
