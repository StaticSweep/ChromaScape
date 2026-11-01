package com.chromascape.web.replay.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Restricts the local-development principal bridge to replay, recordings, session and
 * external-video API requests.
 */
@Configuration
public class LocalReplayPrincipalConfig {

  @Bean
  public FilterRegistrationBean<LocalReplayPrincipalFilter> localReplayPrincipalFilterRegistration(
      LocalReplayPrincipalFilter filter) {
    FilterRegistrationBean<LocalReplayPrincipalFilter> registration =
        new FilterRegistrationBean<>();
    registration.setFilter(filter);
    registration.addUrlPatterns(
        "/api/replay/*",
        "/api/recordings/*",
        "/api/xrsps/sessions/*",
        // SC-2150 external video and the owner review workspace: without this pattern every
        // owner call is refused as unauthenticated on the local-development host.
        "/api/debug/mouse-videos/*");
    registration.setName("localReplayPrincipalFilter");
    registration.setOrder(1);
    return registration;
  }
}
