package com.fluxpay.common.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.TreeSet;

/** Deterministic object JSON; domain services normalize currency and money strings first. */
public final class OperationJson {
  private static final ObjectMapper VALIDATION_MAPPER = new ObjectMapper();

  private OperationJson() {}

  public static String canonicalize(ObjectMapper mapper, String json) {
    return normalize(mapper, readObject(mapper, json));
  }

  public static void requireObject(String json) {
    readObject(VALIDATION_MAPPER, json);
  }

  private static JsonNode readObject(ObjectMapper mapper, String json) {
    try {
      JsonNode value =
          mapper
              .reader()
              .with(
                  com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                  com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
              .readTree(json);
      if (value == null || !value.isObject())
        throw new IllegalArgumentException("An operation request must be a JSON object");
      return value;
    } catch (java.io.IOException | IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "An operation request must be a valid JSON object", exception);
    }
  }

  public static String normalize(ObjectMapper mapper, Object value) {
    JsonNode tree = mapper.valueToTree(value);
    if (!tree.isObject())
      throw new IllegalArgumentException("An operation request must be a JSON object");
    return sorted(mapper, tree).toString();
  }

  private static JsonNode sorted(ObjectMapper mapper, JsonNode node) {
    if (node.isObject()) {
      ObjectNode result = mapper.createObjectNode();
      var fields = new TreeSet<String>();
      node.fieldNames().forEachRemaining(fields::add);
      fields.forEach(field -> result.set(field, sorted(mapper, node.get(field))));
      return result;
    }
    if (node.isArray()) {
      var result = mapper.createArrayNode();
      node.forEach(value -> result.add(sorted(mapper, value)));
      return result;
    }
    if (node.isNumber()) {
      var number = node.decimalValue().stripTrailingZeros();
      return number.scale() <= 0
          ? com.fasterxml.jackson.databind.node.BigIntegerNode.valueOf(number.toBigIntegerExact())
          : DecimalNode.valueOf(number);
    }
    return node;
  }
}
