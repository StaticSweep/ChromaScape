package com.chromascape.api;

import com.chromascape.utils.core.runtime.exception.DaxAuthException;
import com.chromascape.utils.core.runtime.exception.DaxException;
import com.chromascape.utils.core.runtime.exception.DaxRateLimitException;
import java.awt.Point;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Client wrapper for the DAX Walker REST API. Sends pathfinding requests and returns the raw JSON
 * response representing the calculated path.
 */
public class Dax {

  private static final String WALKER_ENDPOINT = "https://walker.dax.cloud/walker/generatePath";

  private final HttpClient client =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();

  /**
   * Sends a pathfinding request to the DAX Walker API.
   *
   * @param start The starting tile coordinates.
   * @param end The destination tile coordinates.
   * @param members True if the player is a member; false otherwise.
   * @return Raw JSON string representing the generated path.
   * @throws IOException If an IO error occurs during the request.
   * @throws InterruptedException If the thread is interrupted.
   * @throws DaxRateLimitException If HTTP 429 is returned.
   * @throws DaxAuthException If credentials or endpoint are invalid (400, 401, 404).
   */
  public String generatePath(Point start, Point end, boolean members)
      throws IOException, InterruptedException {

    String payload =
        String.format(
            """
               {
                 "start": {"x": %d, "y": %d, "z": 0},
                 "end": {"x": %d, "y": %d, "z": 0},
                 "player": {"members": %b}
               }
               """,
            start.x, start.y, end.x, end.y, members);

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(WALKER_ENDPOINT))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("key", "sub_DPjXXzL5DeSiPf")
            .header("secret", "PUBLIC-KEY")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

    return switch (response.statusCode()) {
      case 200 -> response.body();
      case 429 -> throw new DaxRateLimitException();
      case 400, 401, 404 -> throw new DaxAuthException();
      default -> throw new DaxException("Unexpected API error: " + response.statusCode());
    };
  }
}
