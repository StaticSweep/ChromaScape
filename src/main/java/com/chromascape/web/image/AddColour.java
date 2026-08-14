package com.chromascape.web.image;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for adding new colour data to the application's colour configuration file.
 *
 * <p>This class provides a method to append a new {@link ColourData} entry to the <code>
 * colours/colours.json</code> file. If the file does not exist, it will be created. The file is
 * expected to contain a JSON array of colour data objects.
 */
public class AddColour {

  /**
   * Adds a new {@link ColourData} entry to the <code>colours/colours.json</code> file.
   *
   * <p>If the file already exists, the new colour is appended to the existing list. If the file
   * does not exist, a new file is created with the new colour as the first entry.
   *
   * @param newColour the {@link ColourData} object to add
   * @throws IOException if there is an error reading or writing the file
   */
  public void addColour(ColourData newColour) throws IOException {
    Path file = Paths.get("colours/colours.json");
    ObjectMapper mapper = new ObjectMapper();
    List<ColourData> colours = new ArrayList<>();

    if (Files.exists(file)) {
      colours = mapper.readValue(file.toFile(), new TypeReference<>() {});
    }

    colours.add(newColour);

    mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), colours);
  }
}
