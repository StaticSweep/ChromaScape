package com.chromascape.web.debug.patchloop.graph;

import com.chromascape.web.debug.patchloop.entity.NodeType;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.Objects;

/**
 * A node in the patch loop graph.
 *
 * @param id unique node identifier
 * @param type the node type
 * @param label human-readable label
 * @param config optional node configuration
 */
public record GraphNode(
    @JsonProperty("id") String id,
    @JsonProperty("type") NodeType type,
    @JsonProperty("label") String label,
    @JsonProperty("config") Map<String, Object> config) {

  /**
   * Creates a new graph node.
   *
   * @param id the node ID
   * @param type the node type
   * @param label the display label
   */
  public GraphNode {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("Node ID cannot be null or blank");
    }
    if (type == null) {
      throw new IllegalArgumentException("Node type cannot be null");
    }
    if (label == null) {
      label = id;
    }
    if (config == null) {
      config = Map.of();
    }
  }

  /**
   * Creates a node with minimal parameters.
   *
   * @param id the node ID
   * @param type the node type
   * @return a new GraphNode
   */
  public static GraphNode of(String id, NodeType type) {
    return new GraphNode(id, type, id, Map.of());
  }

  /**
   * Creates a node with a custom label.
   *
   * @param id the node ID
   * @param type the node type
   * @param label the display label
   * @return a new GraphNode
   */
  public static GraphNode of(String id, NodeType type, String label) {
    return new GraphNode(id, type, label, Map.of());
  }

  /**
   * Creates a node with configuration.
   *
   * @param id the node ID
   * @param type the node type
   * @param label the display label
   * @param config the node configuration
   * @return a new GraphNode
   */
  public static GraphNode of(String id, NodeType type, String label, Map<String, Object> config) {
    return new GraphNode(id, type, label, config);
  }

  /**
   * Returns a configuration value as a string.
   *
   * @param key the configuration key
   * @param defaultValue the default value if not found
   * @return the configuration value
   */
  public String getConfigString(String key, String defaultValue) {
    Object value = config.get(key);
    return value != null ? value.toString() : defaultValue;
  }

  /**
   * Returns a configuration value as an integer.
   *
   * @param key the configuration key
   * @param defaultValue the default value if not found
   * @return the configuration value
   */
  public int getConfigInt(String key, int defaultValue) {
    Object value = config.get(key);
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    return defaultValue;
  }

  /**
   * Returns a configuration value as a boolean.
   *
   * @param key the configuration key
   * @param defaultValue the default value if not found
   * @return the configuration value
   */
  public boolean getConfigBoolean(String key, boolean defaultValue) {
    Object value = config.get(key);
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    return defaultValue;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    GraphNode graphNode = (GraphNode) o;
    return Objects.equals(id, graphNode.id) && type == graphNode.type;
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, type);
  }

  @Override
  public String toString() {
    return "GraphNode{" + "id='" + id + '\'' + ", type=" + type + ", label='" + label + '\'' + '}';
  }
}
