package com.chromascape.web.recording.config;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "recording.tasks")
public class TaskRecordingConfig {

  private Map<String, Boolean> enabledTasks = new ConcurrentHashMap<>();

  public Map<String, Boolean> getEnabledTasks() {
    return enabledTasks;
  }

  public void setEnabledTasks(Map<String, Boolean> enabledTasks) {
    this.enabledTasks = new ConcurrentHashMap<>(enabledTasks);
  }

  public boolean isTaskEnabled(TaskType taskType) {
    return enabledTasks.getOrDefault(taskType.name().toLowerCase(), false);
  }

  public void setTaskEnabled(TaskType taskType, boolean enabled) {
    enabledTasks.put(taskType.name().toLowerCase(), enabled);
  }

  // Default configuration
  public static Map<String, Boolean> getDefaultTasks() {
    Map<String, Boolean> defaults = new HashMap<>();
    defaults.put("combat", true);
    defaults.put("bossing", true);
    defaults.put("woodcutting", false);
    defaults.put("mining", false);
    defaults.put("fishing", false);
    defaults.put("cooking", false);
    defaults.put("smithing", false);
    defaults.put("crafting", false);
    defaults.put("thieving", false);
    defaults.put("farming", false);
    defaults.put("questing", false);
    defaults.put("other", false);
    return defaults;
  }
}
