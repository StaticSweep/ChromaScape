package com.chromascape.web.debug.patchloop.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Evaluates predicate expressions against JSON context data.
 *
 * <p>Supports a simple DSL for comparing fields, checking existence, and combining conditions with
 * logical operators.
 */
@Service
public class PredicateEvaluator {

  private static final Logger logger = LoggerFactory.getLogger(PredicateEvaluator.class);

  /** Pattern for matching comparison expressions: field operator value */
  private static final Pattern COMPARISON_PATTERN =
      Pattern.compile("(\\w+)\\s*(==|!=|>|<|>=|<=|contains|matches)\\s*(.+)");

  /** Pattern for matching "in" operator: field in [value1, value2, ...] */
  private static final Pattern IN_PATTERN = Pattern.compile("(\\w+)\\s+in\\s+\\[(.+)\\]");

  /**
   * Evaluates a predicate expression against the given context.
   *
   * <p>Supported operators:
   *
   * <ul>
   *   <li>Logical: &&, ||
   *   <li>Comparison: ==, !=, >, <, >=, <=
   *   <li>String: contains, matches (regex)
   *   <li>Membership: in [array]
   *   <li>Boolean field: fieldName (evaluates as truthy if true/false exists)
   * </ul>
   *
   * @param predicate the predicate expression to evaluate
   * @param context the JSON context containing field values
   * @return true if the predicate evaluates to true, false otherwise
   */
  public boolean evaluate(String predicate, JsonNode context) {
    if (predicate == null || predicate.isBlank()) {
      return false;
    }

    // Trim whitespace
    predicate = predicate.trim();

    // Handle logical AND
    int andIndex = findTopLevelOperator(predicate, "&&");
    if (andIndex != -1) {
      String left = predicate.substring(0, andIndex).trim();
      String right = predicate.substring(andIndex + 2).trim();
      return evaluate(left, context) && evaluate(right, context);
    }

    // Handle logical OR
    int orIndex = findTopLevelOperator(predicate, "||");
    if (orIndex != -1) {
      String left = predicate.substring(0, orIndex).trim();
      String right = predicate.substring(orIndex + 2).trim();
      return evaluate(left, context) || evaluate(right, context);
    }

    // Handle logical NOT
    if (predicate.startsWith("!")) {
      return !evaluate(predicate.substring(1).trim(), context);
    }

    // Handle parentheses
    if (predicate.startsWith("(") && predicate.endsWith(")")) {
      return evaluate(predicate.substring(1, predicate.length() - 1).trim(), context);
    }

    // Handle "in" operator
    Matcher inMatcher = IN_PATTERN.matcher(predicate);
    if (inMatcher.matches()) {
      String field = inMatcher.group(1);
      String valuesStr = inMatcher.group(2);
      Set<String> values = parseInValues(valuesStr);
      return evaluateIn(context, field, values);
    }

    // Handle comparisons
    Matcher matcher = COMPARISON_PATTERN.matcher(predicate);
    if (matcher.matches()) {
      String field = matcher.group(1);
      String operator = matcher.group(2);
      String value = matcher.group(3).replace("'", "").replace("\"", "").trim();
      return evaluateComparison(context, field, operator, value);
    }

    // Handle simple field existence (evaluates to true if field exists and is true)
    if (context.has(predicate)) {
      JsonNode fieldNode = context.get(predicate);
      return fieldNode.isBoolean() && fieldNode.asBoolean();
    }

    return false;
  }

  /**
   * Finds the index of a top-level logical operator, ignoring nested operators.
   *
   * @param expression the expression to search
   * @param operator the operator to find
   * @return the index of the operator, or -1 if not found
   */
  private int findTopLevelOperator(String expression, String operator) {
    int depth = 0;
    for (int i = 0; i < expression.length() - operator.length() + 1; i++) {
      char c = expression.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (depth == 0) {
        if (expression
            .substring(i, Math.min(i + operator.length(), expression.length()))
            .equals(operator)) {
          return i;
        }
      }
    }
    return -1;
  }

  /**
   * Parses the values from an "in" expression.
   *
   * @param valuesStr comma-separated values string
   * @return set of parsed values
   */
  private Set<String> parseInValues(String valuesStr) {
    Set<String> values = new HashSet<>();
    String[] parts = valuesStr.split(",");
    for (String part : parts) {
      values.add(part.trim().replace("'", "").replace("\"", ""));
    }
    return values;
  }

  /**
   * Evaluates an "in" comparison.
   *
   * @param context the JSON context
   * @param field the field name
   * @param values the set of values to check against
   * @return true if the field value is in the set
   */
  private boolean evaluateIn(JsonNode context, String field, Set<String> values) {
    if (!context.has(field)) {
      return false;
    }
    JsonNode fieldNode = context.get(field);
    String fieldValue = fieldNode.asText();
    return values.contains(fieldValue);
  }

  /**
   * Evaluates a comparison expression.
   *
   * @param context the JSON context
   * @param field the field name
   * @param operator the comparison operator
   * @param value the value to compare against
   * @return true if the comparison evaluates to true
   */
  private boolean evaluateComparison(
      JsonNode context, String field, String operator, String value) {
    if (!context.has(field)) {
      return false;
    }
    JsonNode fieldNode = context.get(field);

    return switch (operator) {
      case "==" -> fieldNode.isTextual() && fieldNode.asText().equals(value)
          || fieldNode.isBoolean() && fieldNode.asBoolean() == Boolean.parseBoolean(value)
          || fieldNode.isNumber() && fieldNode.asDouble() == Double.parseDouble(value);
      case "!=" -> !(fieldNode.isTextual() && fieldNode.asText().equals(value))
          && !(fieldNode.isBoolean() && fieldNode.asBoolean() == Boolean.parseBoolean(value))
          && !(fieldNode.isNumber() && fieldNode.asDouble() == Double.parseDouble(value));
      case ">" -> fieldNode.isNumber() && fieldNode.asDouble() > Double.parseDouble(value);
      case "<" -> fieldNode.isNumber() && fieldNode.asDouble() < Double.parseDouble(value);
      case ">=" -> fieldNode.isNumber() && fieldNode.asDouble() >= Double.parseDouble(value);
      case "<=" -> fieldNode.isNumber() && fieldNode.asDouble() <= Double.parseDouble(value);
      case "contains" -> fieldNode.isTextual() && fieldNode.asText().contains(value);
      case "matches" -> {
        try {
          yield fieldNode.isTextual() && fieldNode.asText().matches(value);
        } catch (Exception e) {
          logger.warn("Invalid regex pattern: {}", value);
          yield false;
        }
      }
      default -> {
        logger.warn("Unknown comparison operator: {}", operator);
        yield false;
      }
    };
  }

  /**
   * Builds a JSON context from a retest result.
   *
   * @param retestResult the retest result object
   * @return JSON context for predicate evaluation
   */
  public JsonNode buildContextFromResult(Object retestResult) {
    if (retestResult instanceof RetestOrchestrator.RetestResult retest) {
      com.fasterxml.jackson.databind.ObjectMapper mapper =
          new com.fasterxml.jackson.databind.ObjectMapper();
      com.fasterxml.jackson.databind.node.ObjectNode context = mapper.createObjectNode();
      context.put("success", retest.success());
      context.put("improvementCount", retest.improvements());
      context.put("regressionCount", retest.regressions());
      context.put(
          "comparisonSummary",
          retest.comparisonSummary() != null ? retest.comparisonSummary() : "");
      return context;
    }
    return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
  }
}
