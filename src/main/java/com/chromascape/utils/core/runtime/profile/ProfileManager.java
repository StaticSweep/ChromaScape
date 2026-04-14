package com.chromascape.utils.core.runtime.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Manages RuneLite profile configuration for ChromaScape.
 *
 * <p>This class is responsible for loading existing RuneLite profiles, checking whether a
 * ChromaScape-specific profile already exists, and creating one if necessary. A template profile is
 * bundled with the project resources and copied into RuneLite's profile directory when required.
 *
 * <p>Profiles are tracked in two ways:
 *
 * <ul>
 *   <li>The {@code profiles.json} file maintained by RuneLite
 *   <li>A corresponding {@code .properties} file for each profile
 * </ul>
 *
 * <p>The ChromaScape profile is added only if it is missing. The profile data is then saved back to
 * {@code profiles.json}.
 */
@SuppressWarnings("checkstyle:SummaryJavadoc")
public class ProfileManager {
  /** Path to the current user's home directory. */
  private final String userHome = System.getProperty("user.home");

  /** RuneLite profile directory. On Linux with native Bolt, uses the Bolt data path. */
  private final Path profileDir = resolveProfileDir();

  /** List of all loaded RuneLite profiles from profiles.json. */
  private List<Profile> profiles = null;

  /** JSON mapper for serializing and deserializing profile data. */
  private final ObjectMapper mapper;

  /** Logger for status and diagnostic messages. */
  private static final Logger logger = LogManager.getLogger(ProfileManager.class);

  /** Creates a new {@code ProfileManager} with a default Jackson ObjectMapper. */
  public ProfileManager() {
    mapper = new ObjectMapper();
  }

  /**
   * Resolves the RuneLite profile directory for the current operating system.
   *
   * <p>On Windows, returns the standard {@code %APPDATA%\Local\RuneLite\profiles2} path.
   *
   * <p>On Linux, checks for a native Bolt installation first ({@code
   * ~/.var/app/com.adamcake.Bolt/data/bolt-launcher/.runelite/profiles2}), falling back to the
   * standard {@code ~/.runelite/profiles2} path if Bolt is not detected.
   *
   * <p>On macOS, returns {@code ~/Library/Application Support/RuneLite/profiles2}.
   *
   * @return The resolved profile directory path
   */
  private Path resolveProfileDir() {
    String home = System.getProperty("user.home");
    String os = System.getProperty("os.name").toLowerCase();

    if (os.contains("win")) {
      return Path.of(home, "AppData", "Local", "RuneLite", "profiles2");
    }

    if (os.contains("linux")) {
      Path boltPath =
          Path.of(home, ".var/app/com.adamcake.Bolt/data/bolt-launcher/.runelite/profiles2");
      if (Files.exists(boltPath.getParent())) {
        logger.info("ProfileManager: using Bolt RuneLite path: {}", boltPath);
        return boltPath;
      }
    }

    if (os.contains("mac")) {
      return Path.of(home, "Library/Application Support/RuneLite/profiles2");
    }

    // Fallback (Linux standard or unknown OS)
    return Path.of(home, ".runelite/profiles2");
  }

  /**
   * Ensures that the ChromaScape profile exists in the RuneLite configuration.
   *
   * <p>If the profile is already present, no changes are made. Otherwise:
   *
   * <ol>
   *   <li>The bundled template properties file is copied into the profile directory
   *   <li>A new entry is added to the in-memory profile list
   *   <li>The updated profiles.json is written to disk
   * </ol>
   *
   * @throws IOException if profile data cannot be read or written
   */
  public void loadBotProfile() throws IOException {
    loadProfileInfoFromDisk();
    if (hasChromaScapeProfile()) {
      logger.info("ChromaScape RuneLite profile already loaded");
      return;
    }
    logger.info("ChromaScape RuneLite profile doesn't exist, loading profile...");
    addProfile();
    saveProfileInfoToDisk();
  }

  /**
   * Loads the current RuneLite profile information from {@code profiles.json}.
   *
   * @throws IOException if the file cannot be read
   */
  private void loadProfileInfoFromDisk() throws IOException {
    try (InputStream in = Files.newInputStream(profileDir.resolve("profiles.json"))) {
      profiles = mapper.readValue(in, ProfileContainer.class).profiles();
    }
  }

  /**
   * Saves the current in-memory profile list back to {@code profiles.json}.
   *
   * @throws IOException if the file cannot be written
   */
  private void saveProfileInfoToDisk() throws IOException {
    ProfileContainer profileContainer = new ProfileContainer(profiles);
    mapper.writeValue(profileDir.resolve("profiles.json").toFile(), profileContainer);
  }

  /**
   * Checks whether a ChromaScape profile is already defined.
   *
   * @return {@code true} if a profile with name "ChromaScape" exists, {@code false} otherwise
   */
  private boolean hasChromaScapeProfile() {
    for (Profile profile : profiles) {
      if (Objects.equals(profile.name(), "ChromaScape")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Adds a new ChromaScape profile by:
   *
   * <ul>
   *   <li>Generating a unique identifier for the profile
   *   <li>Copying the template properties file from resources to the RuneLite directory
   *   <li>Appending a new {@link Profile} entry to the in-memory list
   * </ul>
   *
   * @throws IOException if the template file cannot be found or written
   */
  private void addProfile() throws IOException {
    // Generate unique ID
    long id = System.currentTimeMillis();
    for (Profile profile : profiles) {
      if (profile.id() == id) {
        id = System.currentTimeMillis();
      }
    }
    // Copy profile to the directory and rename it using the ID
    try (InputStream savedProfile =
        this.getClass().getResourceAsStream("/profiles/ChromaScape.properties")) {
      if (savedProfile != null) {
        Files.copy(
            savedProfile,
            profileDir.resolve("ChromaScape-" + id + ".properties"),
            StandardCopyOption.REPLACE_EXISTING);
      } else {
        throw new FileNotFoundException("Resource not found: /profiles/ChromaScape.properties");
      }
    }
    // Add new profile to the locally saved copy
    Profile botProfile = new Profile(id, "ChromaScape", false, false, -1, new String[0]);
    profiles.add(botProfile);
  }
}
