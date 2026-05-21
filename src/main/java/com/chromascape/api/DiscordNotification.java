package com.chromascape.api;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides functionality to send logs as notifications to yourself via Discord.
 *
 * <ul>
 *   <li>Loads the {@code secrets.properties} file in the root directory.
 *   <li>Saves the specified WebHook URL.
 *   <li>Sends a POST req to the endpoint with the user's desired notification.
 * </ul>
 *
 * <p>This is extremely useful if you aren't actively babysitting your bot or in the case of
 * reaching a specified XP/GP goal. It's my personal advice to the reader for you to inform yourself
 * upon catastrophic failure, promptly.
 */
public class DiscordNotification {

  private static final Logger logger = LoggerFactory.getLogger(DiscordNotification.class);
  private static String webhookUrl;

  static {
    try (InputStream input = new FileInputStream("secrets.properties")) {
      Properties prop = new Properties();
      prop.load(input);
      webhookUrl = prop.getProperty("discord.webhook.url");
    } catch (IOException ex) {
      logger.info("Could not find secrets.properties in the project root.");
    }
  }

  /**
   * Sends a user specified message to a Discord WebHook endpoint. Sets up a post request and
   * expects a 204 response code for success.
   *
   * @param message User specified String to send to the endpoint.
   */
  public static void send(String message) {
    if (webhookUrl == null || webhookUrl.isEmpty()) {
      return;
    }

    String sanitizedMessage = message.replace("\"", "\\\"").replace("\n", "\\n");
    String jsonPayload = "{\"content\": \"" + sanitizedMessage + "\"}";

    try {
      URL url = new URL(webhookUrl);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setDoOutput(true);
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setRequestProperty("User-Agent", "Java-Discord-Webhook");

      try (OutputStream os = conn.getOutputStream()) {
        os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
      }

      // 204 means Success (No Content)
      if (conn.getResponseCode() != 204) {
        logger.error("Failed to send. Response code: {}", conn.getResponseCode());
      }

      conn.disconnect();
    } catch (Exception e) {
      logger.error("Error sending Discord notification: {}", e.getMessage());
    }
  }
}
