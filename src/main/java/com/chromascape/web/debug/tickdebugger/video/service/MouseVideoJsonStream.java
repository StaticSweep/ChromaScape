package com.chromascape.web.debug.tickdebugger.video.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/** Bounded scalar projection; unknown/nested fields are skipped without building JSON trees. */
final class MouseVideoJsonStream {
  private static final JsonFactory FACTORY =
      JsonFactory.builder()
          .streamReadConstraints(
              StreamReadConstraints.builder()
                  .maxNestingDepth(32)
                  .maxStringLength(1024)
                  .maxNumberLength(128)
                  .maxNameLength(256)
                  .build())
          .build();

  private MouseVideoJsonStream() {}

  static JsonParser open(Path path) throws IOException {
    return FACTORY.createParser(path.toFile());
  }

  static ObjectNode scalars(JsonParser parser, ObjectMapper mapper, Collection<String> fields)
      throws IOException {
    return scalars(parser, mapper, fields, Map.of());
  }

  /**
   * Scalars as {@link #scalars(JsonParser, ObjectMapper, Collection)}, plus named nested objects
   * projected to an explicit set of nested scalar fields. Every other field, including unlisted
   * sub-fields of a named object, is skipped without being materialized.
   */
  static ObjectNode scalars(
      JsonParser parser,
      ObjectMapper mapper,
      Collection<String> fields,
      Map<String, Set<String>> objects)
      throws IOException {
    ObjectNode result = mapper.createObjectNode();
    if (!parser.hasToken(JsonToken.START_OBJECT)) {
      parser.skipChildren();
      return result;
    }
    while (parser.nextToken() != JsonToken.END_OBJECT) {
      if (!parser.hasToken(JsonToken.FIELD_NAME)) {
        throw new IOException("Malformed artifact record");
      }
      String field = parser.currentName();
      JsonToken token = parser.nextToken();
      if (token == null) {
        throw new IOException("Truncated artifact record");
      }
      Set<String> nested = objects.get(field);
      if (fields.contains(field) && token.isScalarValue()) {
        JsonNode value = mapper.readTree(parser);
        if (value.isTextual() && value.textValue().length() > 1024) {
          throw new IOException("Artifact scalar exceeds its bound");
        }
        result.set(field, value);
      } else if (nested != null && token == JsonToken.START_OBJECT) {
        result.set(field, projection(parser, mapper, nested));
      } else {
        parser.skipChildren();
      }
    }
    return result;
  }

  private static ObjectNode projection(JsonParser parser, ObjectMapper mapper, Set<String> allowed)
      throws IOException {
    ObjectNode projected = mapper.createObjectNode();
    while (parser.nextToken() != JsonToken.END_OBJECT) {
      if (!parser.hasToken(JsonToken.FIELD_NAME)) {
        throw new IOException("Malformed nested artifact record");
      }
      String name = parser.currentName();
      JsonToken token = parser.nextToken();
      if (token == null) {
        throw new IOException("Truncated nested artifact record");
      }
      if (allowed.contains(name) && token.isScalarValue()) {
        JsonNode value = mapper.readTree(parser);
        if (value.isTextual() && value.textValue().length() > 1024) {
          throw new IOException("Artifact scalar exceeds its bound");
        }
        projected.set(name, value);
      } else {
        parser.skipChildren();
      }
    }
    return projected;
  }

  static void root(JsonParser parser) throws IOException {
    if (parser.nextToken() != JsonToken.START_OBJECT) {
      throw new IOException("Artifact must be an object");
    }
  }

  static void eof(JsonParser parser) throws IOException {
    if (!parser.hasToken(JsonToken.END_OBJECT) || parser.nextToken() != null) {
      throw new IOException("Artifact is truncated or has trailing content");
    }
  }
}
