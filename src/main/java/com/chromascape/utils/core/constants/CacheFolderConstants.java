package com.chromascape.utils.core.constants;

/**
 * The {@code CacheFolderConstants} class defines directory and file naming conventions for the
 * ChromaScape cache structure.
 *
 * <p><b>Purpose:</b> Centralizes folder and file names used for local cache organization,
 * configuration, logs, scripts, and data files, ensuring consistency across the ChromaScape
 * framework.
 *
 * <p><b>Features:</b>
 *
 * <ul>
 *   <li>Top-level cache folder name
 *   <li>Subdirectory names for config, logs, scripts, data, and cache
 *   <li>Array of all cache subdirectory names
 *   <li>Common accounts file name
 * </ul>
 *
 * <p>These constants are intended for use wherever cache-related paths are constructed or
 * referenced within ChromaScape.
 *
 * <p><b>Example usage:</b>
 *
 * <pre>
 *   String configPath =
 *       Path.of(
 *               CacheFolderConstants.CHROMA_CACHE_FOLDER_NAME,
 *               CacheFolderConstants.CONFIG_FOLDER_NAME)
 *           .toString();
 * </pre>
 */
public class CacheFolderConstants {
  public static final String CHROMA_CACHE_FOLDER_NAME = ".chromascape";
  public static final String CONFIG_FOLDER_NAME = "config";
  public static final String LOGS_FOLDER_NAME = "logs";
  public static final String SCRIPTS_FOLDER_NAME = "scripts";
  public static final String DATA_FOLDER_NAME = "data";
  public static final String CACHE_FOLDER_NAME = "cache";
  public static final String[] CHROMA_CACHE_FOLDER_SUBDIRS = {
    CONFIG_FOLDER_NAME, LOGS_FOLDER_NAME, SCRIPTS_FOLDER_NAME, DATA_FOLDER_NAME, CACHE_FOLDER_NAME
  };
  public static final String ACCOUNTS_FILE_NAME = "accounts.json";
}
