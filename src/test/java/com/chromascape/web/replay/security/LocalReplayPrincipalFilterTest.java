package com.chromascape.web.replay.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LocalReplayPrincipalFilterTest {

  @Test
  void localDevelopmentLoopbackReceivesPrincipal() throws Exception {
    LocalReplayPrincipalFilter filter = filterFor("local-development");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/replay/recordings");
    request.setRemoteAddr("127.0.0.1");
    AtomicReference<Principal> observed = new AtomicReference<>();

    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (servletRequest, servletResponse) ->
            observed.set(((HttpServletRequest) servletRequest).getUserPrincipal()));

    assertEquals(System.getProperty("user.name"), observed.get().getName());
  }

  @Test
  void nonLocalProfileDoesNotCreatePrincipal() throws Exception {
    LocalReplayPrincipalFilter filter = filterFor("production");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/replay/recordings");
    request.setRemoteAddr("127.0.0.1");
    AtomicReference<Principal> observed = new AtomicReference<>();

    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (servletRequest, servletResponse) ->
            observed.set(((HttpServletRequest) servletRequest).getUserPrincipal()));

    assertNull(observed.get());
  }

  @Test
  void existingPrincipalIsPreserved() throws Exception {
    LocalReplayPrincipalFilter filter = filterFor("local-development");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/replay/recordings");
    request.setRemoteAddr("127.0.0.1");
    request.setUserPrincipal(() -> "authenticated-user");
    AtomicReference<Principal> observed = new AtomicReference<>();

    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (servletRequest, servletResponse) ->
            observed.set(((HttpServletRequest) servletRequest).getUserPrincipal()));

    assertEquals("authenticated-user", observed.get().getName());
  }

  private static LocalReplayPrincipalFilter filterFor(String profile) {
    StandardEnvironment environment = new StandardEnvironment();
    environment.setActiveProfiles(profile);
    return new LocalReplayPrincipalFilter(environment);
  }

  @Test
  void sessionRoutesUseTheSameRestrictedLocalPrincipalBridge() throws Exception {
    var config = new LocalReplayPrincipalConfig();
    var registration =
        config.localReplayPrincipalFilterRegistration(filterFor("local-development"));
    org.junit.jupiter.api.Assertions.assertTrue(
        registration.getUrlPatterns().contains("/api/xrsps/sessions/*"));
    for (String[] profiles :
        new String[][] {
          {"production"}, {"production", "local-development"}, {"local-development"}
        }) {
      StandardEnvironment environment = new StandardEnvironment();
      environment.setActiveProfiles(profiles);
      for (String peer : new String[] {"127.0.0.1", "192.0.2.1"}) {
        var request = new MockHttpServletRequest("POST", "/api/xrsps/sessions");
        request.setRemoteAddr(peer);
        AtomicReference<Principal> observed = new AtomicReference<>();
        new LocalReplayPrincipalFilter(environment)
            .doFilter(
                request,
                new MockHttpServletResponse(),
                (req, res) -> observed.set(((HttpServletRequest) req).getUserPrincipal()));
        if (profiles.length == 1
            && profiles[0].equals("local-development")
            && peer.equals("127.0.0.1"))
          org.junit.jupiter.api.Assertions.assertNotNull(observed.get());
        else assertNull(observed.get());
      }
    }
  }
}
