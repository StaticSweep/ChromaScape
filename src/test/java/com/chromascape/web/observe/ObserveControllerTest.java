package com.chromascape.web.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ObserveControllerTest {

  @Test
  void startStopAndStatusDelegateToTheService() {
    ObserveService service = mock(ObserveService.class);
    when(service.start("", 0, 0)).thenReturn(Map.of("state", "ACTIVE"));
    when(service.stop()).thenReturn(Map.of("state", "IDLE"));
    when(service.status()).thenReturn(Map.of("state", "IDLE"));
    ObserveController controller = new ObserveController(service);

    assertEquals("ACTIVE", controller.start(loopback(), null).getBody().get("state"));
    verify(service).start("", 0, 0);
    assertEquals("IDLE", controller.stop(loopback()).getBody().get("state"));
    assertEquals("IDLE", controller.status(loopback()).getBody().get("state"));
  }

  @Test
  void mapsFailureCodesToStatusCodes() {
    ObserveService service = mock(ObserveService.class);
    when(service.start("", 0, 0))
        .thenThrow(new ObserveException(ObserveException.CONFLICT, "busy"));
    ObserveController controller = new ObserveController(service);

    ResponseEntity<Map<String, Object>> conflict = controller.start(loopback(), Map.of());
    assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
    assertEquals(ObserveException.CONFLICT, conflict.getBody().get("code"));

    ObserveService missing = mock(ObserveService.class);
    when(missing.start("", 0, 0))
        .thenThrow(new ObserveException(ObserveException.FFMPEG_MISSING, "no ffmpeg"));
    ResponseEntity<Map<String, Object>> unavailable =
        new ObserveController(missing).start(loopback(), null);
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getStatusCode());
  }

  @Test
  void refusesNonLoopbackCallers() {
    ObserveService service = mock(ObserveService.class);
    ObserveController controller = new ObserveController(service);
    HttpServletRequest remote = mock(HttpServletRequest.class);
    when(remote.getRemoteAddr()).thenReturn("192.168.1.6");

    assertEquals(HttpStatus.FORBIDDEN, controller.start(remote, null).getStatusCode());
    assertEquals(HttpStatus.FORBIDDEN, controller.stop(remote).getStatusCode());
    assertEquals(HttpStatus.FORBIDDEN, controller.status(remote).getStatusCode());
  }

  private static HttpServletRequest loopback() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRemoteAddr()).thenReturn("127.0.0.1");
    return request;
  }
}
