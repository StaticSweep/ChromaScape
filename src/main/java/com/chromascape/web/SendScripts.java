package com.chromascape.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller responsible for providing available script names.
 *
 * <p>This controller scans the {@code scripts} directory for available script files and returns
 * their names to the client.
 */
@RestController
@RequestMapping("/api")
public class SendScripts {

  /** The directory where script classes are located. */
  private static final Path SCRIPTS_DIR = Paths.get("src/main/java/com/chromascape/scripts");

  /**
   * Returns a list of script file names located in the {@code scripts} directory.
   *
   * <p>This endpoint scans only the top-level entries (non-recursive).
   *
   * @return a list of script file names relative to {@code SCRIPTS_DIR}
   * @throws IOException if an I/O error occurs while reading the directory
   */
  @GetMapping("/scripts")
  public List<String> getScripts() throws IOException {
    try (Stream<Path> stream = Files.walk(SCRIPTS_DIR, 1)) {
      return stream
          .filter(path -> !path.equals(SCRIPTS_DIR))
          .map(SCRIPTS_DIR::relativize)
          .map(Path::toString)
          .collect(Collectors.toList());
    }
  }
}
