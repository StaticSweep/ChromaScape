package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Evidence selector for V2 analysis requests.
 *
 * <p>Defines what evidence types to collect and any filters to apply.
 *
 * @since 2.0.0
 */
public class AnalysisV2EvidenceSelector {

  /** Type of evidence to collect (required). */
  @JsonProperty("type")
  private EvidenceType type;

  /** Whether this evidence type is required (default: false). */
  @JsonProperty("required")
  private Boolean required;

  /** Maximum count to collect (optional). */
  @JsonProperty("maxCount")
  private Integer maxCount;

  /** Time-based filter (optional). */
  @JsonProperty("timeWindow")
  private TimeWindow timeWindow;

  /** Tick-based filter (optional). */
  @JsonProperty("tickWindow")
  private TickWindow tickWindow;

  /** Additional filters as key-value pairs (optional). */
  @JsonProperty("filters")
  private List<Filter> filters;

  public AnalysisV2EvidenceSelector() {
    this.required = false;
    this.filters = new ArrayList<>();
  }

  public AnalysisV2EvidenceSelector(EvidenceType type) {
    this.type = type;
    this.required = false;
    this.filters = new ArrayList<>();
  }

  public EvidenceType getType() {
    return type;
  }

  public void setType(EvidenceType type) {
    this.type = type;
  }

  public Boolean getRequired() {
    return required != null ? required : false;
  }

  public void setRequired(Boolean required) {
    this.required = required;
  }

  public Integer getMaxCount() {
    return maxCount;
  }

  public void setMaxCount(Integer maxCount) {
    this.maxCount = maxCount;
  }

  public TimeWindow getTimeWindow() {
    return timeWindow;
  }

  public void setTimeWindow(TimeWindow timeWindow) {
    this.timeWindow = timeWindow;
  }

  public TickWindow getTickWindow() {
    return tickWindow;
  }

  public void setTickWindow(TickWindow tickWindow) {
    this.tickWindow = tickWindow;
  }

  public List<Filter> getFilters() {
    return filters != null ? filters : new ArrayList<>();
  }

  public void setFilters(List<Filter> filters) {
    this.filters = filters;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    AnalysisV2EvidenceSelector that = (AnalysisV2EvidenceSelector) o;
    return type == that.type
        && Objects.equals(required, that.required)
        && Objects.equals(maxCount, that.maxCount)
        && Objects.equals(timeWindow, that.timeWindow)
        && Objects.equals(tickWindow, that.tickWindow)
        && Objects.equals(filters, that.filters);
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, required, maxCount, timeWindow, tickWindow, filters);
  }

  @Override
  public String toString() {
    return "AnalysisV2EvidenceSelector{"
        + "type="
        + type
        + ", required="
        + required
        + ", maxCount="
        + maxCount
        + ", timeWindow="
        + timeWindow
        + ", tickWindow="
        + tickWindow
        + ", filters="
        + (filters != null ? filters.size() : 0)
        + '}';
  }

  /** A filter to apply when collecting evidence. */
  public static class Filter {

    /** Filter key (e.g., "level", "severity", "subsystem"). */
    @JsonProperty("key")
    private String key;

    /** Filter value (supports wildcards). */
    @JsonProperty("value")
    private String value;

    /** Filter operator (default: EQUALS). */
    @JsonProperty("operator")
    private FilterOperator operator;

    public Filter() {
      this.operator = FilterOperator.EQUALS;
    }

    public Filter(String key, String value) {
      this.key = key;
      this.value = value;
      this.operator = FilterOperator.EQUALS;
    }

    public Filter(String key, String value, FilterOperator operator) {
      this.key = key;
      this.value = value;
      this.operator = operator;
    }

    public String getKey() {
      return key;
    }

    public void setKey(String key) {
      this.key = key;
    }

    public String getValue() {
      return value;
    }

    public void setValue(String value) {
      this.value = value;
    }

    public FilterOperator getOperator() {
      return operator != null ? operator : FilterOperator.EQUALS;
    }

    public void setOperator(FilterOperator operator) {
      this.operator = operator;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Filter that = (Filter) o;
      return Objects.equals(key, that.key)
          && Objects.equals(value, that.value)
          && operator == that.operator;
    }

    @Override
    public int hashCode() {
      return Objects.hash(key, value, operator);
    }

    @Override
    public String toString() {
      return "Filter{"
          + "key='"
          + key
          + '\''
          + ", value='"
          + value
          + '\''
          + ", operator="
          + operator
          + '}';
    }
  }

  /** Filter operator for evidence selection. */
  public enum FilterOperator {
    /** Exact match */
    EQUALS,

    /** Case-insensitive match */
    CONTAINS,

    /** Regular expression match */
    REGEX,

    /** Greater than (numeric) */
    GREATER_THAN,

    /** Less than (numeric) */
    LESS_THAN
  }
}
