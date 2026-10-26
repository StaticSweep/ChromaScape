package com.chromascape.web.aiplayer.contract;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** A control advertisement must be the literal JSON boolean false, never a coerced value. */
public final class StrictFalseBooleanDeserializer extends JsonDeserializer<Boolean> {
  @Override
  public Boolean deserialize(JsonParser parser, DeserializationContext context) throws IOException {
    if (parser.currentToken() != JsonToken.VALUE_FALSE) {
      return context.reportInputMismatch(
          Boolean.class, "CONTROL_FORBIDDEN: control_allowed must be false");
    }
    return false;
  }

  @Override
  public Boolean getNullValue(DeserializationContext context)
      throws com.fasterxml.jackson.databind.JsonMappingException {
    return context.reportInputMismatch(
        Boolean.class, "CONTROL_FORBIDDEN: control_allowed must be false");
  }
}
