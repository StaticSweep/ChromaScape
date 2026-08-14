package com.chromascape.web.aiplayer.routine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class RoutineArchitectureTest {
  @Test
  void routineProductionPackageCannotImportOrDispatchGameplayInput() throws IOException {
    Path source = Path.of("src/main/java/com/chromascape/web/aiplayer/routine");
    if (!Files.isDirectory(source)) return;
    try (Stream<Path> files = Files.walk(source)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        String text = Files.readString(file);
        assertFalse(text.contains("BotRuntimeAdapter"), file.toString());
        assertFalse(text.contains("Microbot"), file.toString());
        assertFalse(text.contains("InputManager"), file.toString());
        assertFalse(text.contains("MenuAction"), file.toString());
        assertFalse(text.contains("java.awt.Robot"), file.toString());
        assertFalse(text.contains("MouseEvent"), file.toString());
        assertFalse(text.contains("Keyboard"), file.toString());
      }
    }
  }
}
