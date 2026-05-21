package com.chromascape.web.stats;

import com.chromascape.utils.core.statistics.StatisticsManager;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Component responsible for broadcasting application statistics to connected WebSocket clients.
 *
 * <p>This class executes a scheduled task every second to aggregate performance metrics from the
 * {@link StatisticsManager} (such as uptime, CPU cycles, inputs, and object detections). These
 * metrics are formatted into a JSON payload and sent to the {@link StatisticsWebSocketHandler}.
 */
@Component
public class StatisticsBroadcaster {

  private final StatisticsWebSocketHandler handler;

  /**
   * Constructs a new broadcaster with the given WebSocket handler.
   *
   * @param handler the handler used to send messages to clients
   */
  @Autowired
  public StatisticsBroadcaster(StatisticsWebSocketHandler handler) {
    this.handler = handler;
  }

  /**
   * Periodically fetches the latest statistics and broadcasts them.
   *
   * <p>This method runs on a fixed schedule of 1000ms. It retrieves the following data:
   *
   * <ul>
   *   <li>Elapsed Time (formatted as HH:mm:ss)
   *   <li>Logic Cycles
   *   <li>Input Actions
   *   <li>Objects Detected
   * </ul>
   *
   * <p>The data is serialized into a simple JSON string before broadcast.
   */
  @Scheduled(fixedRate = 1000)
  public void pushStats() {
    long elapsedTime = StatisticsManager.getElapsedTime();
    String duration = formatDuration(elapsedTime);
    int cycles = StatisticsManager.getCycles();
    int inputs = StatisticsManager.getInputs();
    int objects = StatisticsManager.getObjectsDetected();

    String json =
        String.format(
            "{\"time\": \"%s\", \"cycles\": %d, \"inputs\": %d, \"objects\": %d}",
            duration, cycles, inputs, objects);

    handler.broadcast(json);
  }

  /**
   * Formats a duration in milliseconds into a readable string (HH:mm:ss).
   *
   * @param millis the duration in milliseconds
   * @return a formatted time string
   */
  private String formatDuration(long millis) {
    Duration d = Duration.ofMillis(millis);
    long hours = d.toHours();
    long minutes = d.toMinutesPart();
    long seconds = d.toSecondsPart();
    return String.format("%02d:%02d:%02d", hours, minutes, seconds);
  }
}
