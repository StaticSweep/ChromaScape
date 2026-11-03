package com.chromascape.web.replay.security;

import com.chromascape.web.security.LocalMachineRequest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Supplies a principal for same-host operator calls from the application host itself.
 *
 * <p>Enabled by the sole {@code local-development} profile, or by the Fedora development profile
 * only when {@code chromascape.local.operator.principal.enabled} is explicitly set. The caller
 * cannot opt in through a header: the flag is server configuration, the peer must be this machine,
 * and any real principal always wins.
 */
@Component
public class LocalReplayPrincipalFilter extends OncePerRequestFilter {

  private final boolean localDevelopment;

  public LocalReplayPrincipalFilter(
      Environment environment,
      @org.springframework.beans.factory.annotation.Value(
              "${chromascape.local.operator.principal.enabled:false}")
          boolean localOperatorEnabled) {
    var activeProfiles = Arrays.asList(environment.getActiveProfiles());
    this.localDevelopment =
        (activeProfiles.size() == 1 && "local-development".equals(activeProfiles.get(0)))
            || (localOperatorEnabled && activeProfiles.contains("fedora-development"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (request.getUserPrincipal() != null
        || !localDevelopment
        || !LocalMachineRequest.isLocalMachine(request)) {
      filterChain.doFilter(request, response);
      return;
    }

    String configuredName = System.getProperty("user.name");
    String principalName =
        configuredName == null || configuredName.isBlank() ? "local-development" : configuredName;
    Principal principal = () -> principalName;
    HttpServletRequestWrapper authenticatedRequest =
        new HttpServletRequestWrapper(request) {
          @Override
          public Principal getUserPrincipal() {
            return principal;
          }

          @Override
          public String getRemoteUser() {
            return principal.getName();
          }
        };
    filterChain.doFilter(authenticatedRequest, response);
  }
}
