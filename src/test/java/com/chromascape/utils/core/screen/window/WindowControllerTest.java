package com.chromascape.utils.core.screen.window;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.Rectangle;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class WindowControllerTest {

  @Test
  void getCurrentWindowReturnsNoContentWhenNoWindowIsSelected() {
    WindowController controller = new WindowController(new WindowSelectionService());

    ResponseEntity<Map<String, Object>> response = controller.getCurrentWindow();

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
  }

  @Test
  void getCurrentWindowReturnsSelectedWindowPayload() {
    WindowSelectionService selectionService = new WindowSelectionService();
    selectionService.setSelectedWindow(
        new WindowInfo(
            "7", null, "RuneLite - test", "test", 1234, true, new Rectangle(10, 20, 300, 400)));
    WindowController controller = new WindowController(selectionService);

    ResponseEntity<Map<String, Object>> response = controller.getCurrentWindow();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals("7", response.getBody().get("id"));
    assertEquals("RuneLite - test", response.getBody().get("title"));
  }
}
