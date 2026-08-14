package com.chromascape.utils.core.screen.colour;

import com.chromascape.web.image.ColourData;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.bytedeco.opencv.opencv_core.Scalar;

/**
 * A utility class for loading and accessing named colour definitions used for screen detection.
 *
 * <p>Colour data is loaded once at class initialization from a {@code colours.json} file located
 * relative to the current working directory (usually the project root). Each colour definition
 * includes a name and a min-max HSV range, which is used to construct {@link ColourObj} instances.
 *
 * <p>Note: The fourth component of the {@link Scalar} is always zero due to JavaCV's Scalar
 * structure, so only the first three channels (H, S, V) are meaningful.
 */
public class ColourInstances {

  /** The cached list of all colour definitions loaded from the configuration file. */
  private static List<ColourObj> COLOURS;

  /**
   * Path to the colours JSON file, relative to working directory. Adjust this path if your file
   * location changes.
   */
  private static final String COLOURS_JSON_PATH = "colours/colours.json";

  // Static block to load colour data once at class load time
  static {
    try (InputStream is = Files.newInputStream(Path.of(COLOURS_JSON_PATH))) {
      ObjectMapper mapper = new ObjectMapper();

      List<ColourData> colourDataList = mapper.readValue(is, new TypeReference<>() {});

      COLOURS =
          colourDataList.stream()
              .map(
                  data ->
                      new ColourObj(
                          data.getName(),
                          new Scalar(
                              data.getMin()[0],
                              data.getMin()[1],
                              data.getMin()[2],
                              data.getMin()[3]),
                          new Scalar(
                              data.getMax()[0],
                              data.getMax()[1],
                              data.getMax()[2],
                              data.getMax()[3])))
              .toList();

    } catch (IOException e) {
      System.err.println(
          "Could not load colours.json from path '" + COLOURS_JSON_PATH + "': " + e.getMessage());
      COLOURS = List.of(); // Initialize as empty list to avoid null pointer issues
    }
  }

  /**
   * Retrieves a {@link ColourObj} by its name.
   *
   * @param name The name of the colour to retrieve.
   * @return The {@link ColourObj} matching the given name, or {@code null} if not found.
   */
  public static ColourObj getByName(String name) {
    for (ColourObj colour : COLOURS) {
      if (colour.name().equals(name)) {
        return colour;
      }
    }
    return null;
  }
}
