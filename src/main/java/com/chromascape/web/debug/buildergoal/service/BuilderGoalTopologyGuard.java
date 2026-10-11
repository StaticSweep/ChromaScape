package com.chromascape.web.debug.buildergoal.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Explicit environment boundary and separate credentials for executor observations and dial-out.
 */
@Component
public class BuilderGoalTopologyGuard {
  private final String heartbeatToken;
  private final String executorToken;

  public BuilderGoalTopologyGuard(
      @Value("${builder-goal.topology.environment:DEV}") String environment,
      @Value("${builder-goal.executor.heartbeat-token:}") String heartbeatToken,
      @Value("${builder-goal.executor.auth-token:}") String executorToken,
      @Value("${builder-goal.launcher.base-url:http://192.168.1.6:8084}") String launcherUrl,
      @Value("${builder-goal.launcher.api-token:devtoken123}") String launcherToken,
      @Value("${micro6.runtime-selection.registry-base-url:http://127.0.0.1:8084}")
          String registryUrl,
      @Value("${micro6.runtime-selection.registry-auth-token:}") String registryToken,
      @Value("${micro6.runtime-selection.dev-fallback-enabled:false}") boolean fallback,
      @Value("${builder-goal.local-launch.enabled:true}") boolean localLaunch) {
    this.heartbeatToken = heartbeatToken;
    this.executorToken = executorToken;
    if (!Set.of("DEV", "STAGING", "PRODUCTION").contains(environment))
      throw new IllegalStateException("TOPOLOGY_ENVIRONMENT_INVALID");
    if (!"DEV".equals(environment)) {
      if (fallback
          || localLaunch
          || !safeServiceOrigin(launcherUrl)
          || !safeServiceOrigin(registryUrl)
          || launcherUrl.contains("192.168.1.6")
          || !strong(heartbeatToken)
          || !strong(executorToken)
          || java.util.Objects.equals(heartbeatToken, executorToken)
          || !strong(launcherToken)
          || !strong(registryToken))
        throw new IllegalStateException("TOPOLOGY_UNSAFE_NON_DEVELOPMENT");
      for (String property :
          java.util.List.of("javax.net.ssl.trustStore", "javax.net.ssl.keyStore")) {
        String store = System.getProperty(property);
        if (store == null || !java.nio.file.Files.isRegularFile(java.nio.file.Path.of(store)))
          throw new IllegalStateException("TOPOLOGY_TLS_TRUST_REQUIRED");
      }
    }
  }

  private static boolean safeServiceOrigin(String value) {
    try {
      java.net.URI uri = java.net.URI.create(value);
      return "https".equals(uri.getScheme())
          && uri.getHost() != null
          && uri.getUserInfo() == null
          && uri.getRawQuery() == null
          && uri.getRawFragment() == null
          && uri.getPort() > 0
          && uri.getPort() <= 65535
          && (uri.getPath().isEmpty() || "/".equals(uri.getPath()));
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  private static boolean strong(String t) {
    return t != null
        && t.length() >= 32
        && t.length() <= 256
        && t.matches("[A-Za-z0-9_-]+")
        && !t.toLowerCase(java.util.Locale.ROOT).contains("dev");
  }

  public boolean authenticatesHeartbeat(String authorization) {
    return strong(heartbeatToken)
        && authorization != null
        && authorization.length() <= 263
        && MessageDigest.isEqual(
            ("Bearer " + heartbeatToken).getBytes(StandardCharsets.UTF_8),
            authorization.getBytes(StandardCharsets.UTF_8));
  }

  public String executorAuthorization() {
    if (!strong(executorToken)) throw new IllegalStateException("EXECUTOR_AUTH_NOT_CONFIGURED");
    return "Bearer " + executorToken;
  }
}
