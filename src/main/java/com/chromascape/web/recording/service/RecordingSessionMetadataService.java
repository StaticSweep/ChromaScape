package com.chromascape.web.recording.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Service for normalizing, validating, and computing replay metadata. */
@Service
public class RecordingSessionMetadataService {

  private static final int MAX_REPLAY_NAME_LENGTH = 160;
  private static final int MAX_TAGS = 20;
  private static final int MAX_TAG_LENGTH = 32;

  private static final DateTimeFormatter DISPLAY_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault());

  private final ObjectMapper objectMapper;

  public RecordingSessionMetadataService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * Parse a JSON array string into a list of tags.
   *
   * @param json the JSON array string (may be null or empty)
   * @return list of tags, never null
   */
  public List<String> parseTags(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<List<String>>() {});
    } catch (JsonProcessingException e) {
      return List.of();
    }
  }

  /**
   * Serialize a list of tags to JSON array string.
   *
   * @param tags the tags to serialize
   * @return JSON array string, or null if empty
   */
  public String serializeTags(List<String> tags) {
    if (tags == null || tags.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(tags);
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  /**
   * Normalize a list of tags: trim, drop empty, dedupe case-insensitive preserving first casing.
   *
   * @param tags raw tag list
   * @return normalized tag list
   */
  public List<String> normalizeTags(List<String> tags) {
    if (tags == null) {
      return List.of();
    }
    Set<String> seenLower = new LinkedHashSet<>();
    List<String> result = new ArrayList<>();
    for (String tag : tags) {
      if (tag == null) {
        continue;
      }
      String trimmed = tag.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      String lower = trimmed.toLowerCase(Locale.ROOT);
      if (seenLower.add(lower)) {
        result.add(trimmed);
      }
    }
    return result;
  }

  /**
   * Validate tags against limits.
   *
   * @param tags the tags to validate
   * @throws IllegalArgumentException if validation fails
   */
  public void validateTags(List<String> tags) {
    if (tags == null) {
      return;
    }
    if (tags.size() > MAX_TAGS) {
      throw new IllegalArgumentException("Too many tags (max " + MAX_TAGS + ")");
    }
    for (String tag : tags) {
      if (tag.length() > MAX_TAG_LENGTH) {
        throw new IllegalArgumentException(
            "Tag too long (max " + MAX_TAG_LENGTH + " chars): '" + tag + "'");
      }
    }
  }

  /**
   * Validate replay name.
   *
   * @param replayName the name to validate (may be null)
   * @throws IllegalArgumentException if validation fails
   */
  public void validateReplayName(String replayName) {
    if (replayName != null && replayName.length() > MAX_REPLAY_NAME_LENGTH) {
      throw new IllegalArgumentException(
          "Replay name too long (max " + MAX_REPLAY_NAME_LENGTH + " chars)");
    }
  }

  /**
   * Compute display name: returns replayName if set, otherwise auto-generated from session fields.
   *
   * @param replayName custom replay name (may be null)
   * @param accountName account name (may be null)
   * @param scriptName script name (may be null)
   * @param startTimeFormatted pre-formatted start time string (may be null)
   * @return the display name
   */
  public String computeDisplayName(
      String replayName, String accountName, String scriptName, String startTimeFormatted) {
    if (replayName != null && !replayName.isBlank()) {
      return replayName;
    }
    StringBuilder sb = new StringBuilder();
    if (scriptName != null && !scriptName.isBlank()) {
      sb.append(scriptName);
    }
    if (accountName != null && !accountName.isBlank()) {
      if (!sb.isEmpty()) {
        sb.append(" - ");
      }
      sb.append(accountName);
    }
    if (startTimeFormatted != null && !startTimeFormatted.isBlank()) {
      if (!sb.isEmpty()) {
        sb.append(" - ");
      }
      sb.append(startTimeFormatted);
    }
    return sb.isEmpty() ? "Unnamed Recording" : sb.toString();
  }

  /**
   * Format a start time for display.
   *
   * @param startTime the start time (may be null)
   * @return formatted string, or null
   */
  public String formatStartTime(java.time.LocalDateTime startTime) {
    if (startTime == null) {
      return null;
    }
    return startTime.format(DISPLAY_FORMATTER);
  }

  public int getMaxTags() {
    return MAX_TAGS;
  }

  public int getMaxTagLength() {
    return MAX_TAG_LENGTH;
  }

  public int getMaxReplayNameLength() {
    return MAX_REPLAY_NAME_LENGTH;
  }
}
