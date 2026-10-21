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
      if (fields.contains(field) && token.isScalarValue()) {
        JsonNode value = mapper.readTree(parser);
        if (value.isTextual() && value.textValue().length() > 1024) {
          throw new IOException("Artifact scalar exceeds its bound");
        }
        result.set(field, value);
      } else {
        parser.skipChildren();
      }
    }
    return result;
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
