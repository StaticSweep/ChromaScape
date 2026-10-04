package com.chromascape.web.replay.hash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * SC-275 deterministic bounded structural diffs, mirroring {@code structural-diff-v2.ts}.
 *
 * <p>First mismatch order is stable: trace ordinal, then tick/sequence, then declared domain order,
 * then deterministic field order. JSON pointers escape {@code ~} and {@code /}. Output is bounded
 * before construction: node/depth/difference budgets clip traversal with truncated markers, and
 * display values are byte-capped with non-allowlisted strings redacted.
 *
 * <p>Causal state differences use kind {@code CAUSAL_STATE}. Policy prediction comparisons use kind
 * {@code POLICY_PREDICTION} and never feed causal hashes.
 */
public final class StructuralDiff {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Set<String> DISPLAYABLE_STRING_KEYS =
      Set.of(
          "stableId",
          "spotId",
          "kind",
          "status",
          "code",
          "domain",
          "path",
          "tick",
          "sequence",
          "ordinal",
          "projectionVersion",
          "hashVersion",
          "traceVersion",
          "capacity",
          "viewVersion",
          "selectionPolicyVersion",
          "name",
          "option",
          "action",
          "result",
          "tag",
          "schema",
          "version");

  private static final List<String> TRANSITION_FIELD_ORDER =
      List.of(
          "traceVersion",
          "manifestDigest",
          "runtimeDigest",
          "tick",
          "sequence",
          "ordinal",
          "kind",
          "actionDigest",
          "resultDigest",
          "stateHash",
          "previousChainHash",
          "chainHash");

  private StructuralDiff() {}

  /** Deterministic output budgets. */
  public record DiffLimits(
      int maxDepth, int maxNodes, int maxDifferences, int maxDisplayValueUtf8Bytes) {
    /** Default budgets shared with the TypeScript differ. */
    public static DiffLimits defaults() {
      return new DiffLimits(32, 10000, 100, 1024);
    }
  }

  /** One bounded, redacted difference with its trace position. */
  public record StateDifference(
      String kind,
      String domain,
      String path,
      JsonNode expected,
      JsonNode actual,
      boolean truncated,
      Long tick,
      Long sequence,
      Integer ordinal) {}

  /** Bounded diff output: the differences plus whether any budget clipped them. */
  public record DiffResult(List<StateDifference> differences, boolean truncated) {}

  /**
   * Stack-safe display depth, shared with the TypeScript differ. Traversal can hand the display
   * path a composite nested far deeper than maxDepth, and caller limits must never control
   * call-stack use.
   */
  private static final int DISPLAY_MAX_DEPTH = 64;

  /** Escapes one JSON pointer segment ({@code ~} to {@code ~0}, {@code /} to {@code ~1}). */
  public static String escapePointer(String segment) {
    return segment.replace("~", "~0").replace("/", "~1");
  }

  /** Compares two values with full diff context. */
  public static DiffResult diffValues(
      JsonNode expected, JsonNode actual, String kind, String domain) {
    return diffValues(expected, actual, kind, domain, null, null, null, DiffLimits.defaults());
  }

  /** Compares two values with full diff context. */
  public static DiffResult diffValues(
      JsonNode expected,
      JsonNode actual,
      String kind,
      String domain,
      Long tick,
      Long sequence,
      Integer ordinal,
      DiffLimits limits) {
    assertLimits(limits);
    Differ differ = new Differ(kind, domain, tick, sequence, ordinal, limits);
    return differ.run(expected, actual);
  }

  /** Compares two causal projections in declared domain order. */
  public static DiffResult diffDomains(
      long expectedTick,
      Map<String, JsonNode> expected,
      long actualTick,
      Map<String, JsonNode> actual,
      List<String> domainOrder,
      String kind) {
    return diffDomains(
        expectedTick, expected, actualTick, actual, domainOrder, kind, DiffLimits.defaults());
  }

  /** Compares two causal projections in declared domain order. */
  public static DiffResult diffDomains(
      long expectedTick,
      Map<String, JsonNode> expected,
      long actualTick,
      Map<String, JsonNode> actual,
      List<String> domainOrder,
      String kind,
      DiffLimits limits) {
    assertLimits(limits);
    List<StateDifference> out = new ArrayList<>();
    boolean truncated = false;
    if (limits.maxDifferences() == 0) {
      return new DiffResult(out, true);
    }
    int[] budget = new int[] {limits.maxNodes()};
    if (expectedTick != actualTick) {
      Differ ticks = new Differ(kind, "tick", null, null, null, limits);
      ticks.budget = budget;
      DiffResult part =
          ticks.run(
              MAPPER.getNodeFactory().numberNode(expectedTick),
              MAPPER.getNodeFactory().numberNode(actualTick));
      out.addAll(part.differences());
      truncated = part.truncated();
    }

    for (String domain : domainOrder) {
      // One shared output budget across domains: each domain receives only the
      // remaining allowance, so the total never exceeds maxDifferences.
      int remaining = limits.maxDifferences() - out.size();
      if (remaining <= 0) {
        truncated = true;
        break;
      }
      DiffLimits remainingLimits =
          new DiffLimits(
              limits.maxDepth(), limits.maxNodes(), remaining, limits.maxDisplayValueUtf8Bytes());
      Differ differ = new Differ(kind, domain, expectedTick, null, null, remainingLimits);
      differ.budget = budget;
      DiffResult part = differ.run(expected.get(domain), actual.get(domain));
      out.addAll(part.differences());
      truncated = truncated || part.truncated();
    }
    return new DiffResult(out, truncated);
  }

  /** Earliest-first comparison of two transition frame sequences. */
  public static DiffResult diffTransitions(
      List<JsonNode> expected, List<JsonNode> actual, String kind) {
    return diffTransitions(expected, actual, kind, DiffLimits.defaults());
  }

  /** Earliest-first comparison of two transition frame sequences. */
  public static DiffResult diffTransitions(
      List<JsonNode> expected, List<JsonNode> actual, String kind, DiffLimits limits) {
    assertLimits(limits);
    List<StateDifference> out = new ArrayList<>();
    boolean truncated = false;
    if (limits.maxDifferences() == 0) {
      return new DiffResult(out, true);
    }
    int[] budget = new int[] {limits.maxNodes()};
    int shared = Math.min(expected.size(), actual.size());
    for (int index = 0; index < shared; index++) {
      JsonNode left = expected.get(index);
      JsonNode right = actual.get(index);
      for (String field : TRANSITION_FIELD_ORDER) {
        if (--budget[0] < 0) {
          return new DiffResult(out, true);
        }
        JsonNode expField = left.path(field);
        JsonNode actField = right.path(field);
        // Containers never short-circuit here: frame fields are scalars by
        // contract, and a hostile nested value must reach the depth-capped
        // display path instead of Jackson's recursive deep-equals.
        if (!shallowEqual(expField, actField)) {
          Displayed shown = display(expField, field, limits.maxDisplayValueUtf8Bytes(), budget);
          Displayed shownActual =
              display(actField, field, limits.maxDisplayValueUtf8Bytes(), budget);
          Long tick = left.path("tick").isIntegralNumber() ? left.path("tick").longValue() : null;
          Long sequence =
              left.path("sequence").isIntegralNumber() ? left.path("sequence").longValue() : null;
          boolean clip = shown.truncated() || shownActual.truncated();
          out.add(
              new StateDifference(
                  kind,
                  "transition",
                  "/" + index + "/" + field,
                  shown.value(),
                  shownActual.value(),
                  clip,
                  tick,
                  sequence,
                  index));
          return new DiffResult(out, truncated || clip);
        }
      }
    }
    if (expected.size() != actual.size()) {
      out.add(
          new StateDifference(
              kind,
              "transition",
              "/length",
              MAPPER.getNodeFactory().numberNode(expected.size()),
              MAPPER.getNodeFactory().numberNode(actual.size()),
              false,
              null,
              null,
              null));
    }
    return new DiffResult(out, truncated);
  }

  /**
   * Stack-safe equality: reference equality plus value-node equality only. Container equality is
   * decided by iterative traversal, never by Jackson's recursive deep-equals, so hostile nesting
   * cannot overflow the stack.
   */
  private static boolean shallowEqual(JsonNode left, JsonNode right) {
    if (left == right) {
      return true;
    }
    return left != null
        && right != null
        && left.isValueNode()
        && right.isValueNode()
        && left.equals(right);
  }

  /**
   * True when value nests deeper than max. Heap-based: never uses the call stack. Cyclic values
   * read as too deep so display clips them instead of serializing forever.
   */
  private static boolean exceedsDepth(JsonNode value, int max) {
    Set<JsonNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    ArrayDeque<JsonNode> nodes = new ArrayDeque<>();
    ArrayDeque<Integer> depths = new ArrayDeque<>();
    nodes.push(value);
    depths.push(0);
    while (!nodes.isEmpty()) {
      JsonNode current = nodes.pop();
      int depth = depths.pop();
      if (depth > max) {
        return true;
      }
      if (current != null && current.isContainerNode()) {
        if (!seen.add(current)) {
          return true;
        }
        for (JsonNode child : current) {
          nodes.push(child);
          depths.push(depth + 1);
        }
      }
    }
    return false;
  }

  private record Displayed(JsonNode value, boolean truncated) {}

  /** Rejects non-finite, non-integer, or negative budgets before traversal. */
  private static void assertLimits(DiffLimits limits) {
    if (limits.maxDepth() < 0
        || limits.maxNodes() < 0
        || limits.maxDifferences() < 0
        || limits.maxDisplayValueUtf8Bytes() < 0) {
      throw new Sc275Exception(
          "PROJECTION_DIFF_LIMIT", "diff limits must be non-negative integers");
    }
  }

  private static Displayed clipText(String text, int limit, boolean alreadyClipped) {
    StringBuilder out = new StringBuilder();
    int bytes = 0;
    for (int offset = 0; offset < text.length(); ) {
      int point = text.codePointAt(offset);
      int size = point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
      if (bytes + size > limit) {
        return new Displayed(MAPPER.getNodeFactory().textNode(out.toString()), true);
      }
      out.appendCodePoint(point);
      bytes += size;
      offset += Character.charCount(point);
    }
    return new Displayed(MAPPER.getNodeFactory().textNode(out.toString()), alreadyClipped);
  }

  private static Displayed display(JsonNode value, String key, int limit) {
    return display(value, key, limit, new int[] {10000});
  }

  private static Displayed display(JsonNode value, String key, int limit, int[] budget) {
    if (value == null) {
      return new Displayed(MAPPER.nullNode(), false);
    }
    if (value.isTextual()) {
      String text = value.textValue();
      boolean marker = Set.of("[absent]", "[clipped]", "[cycle]").contains(text);
      return clipText(
          marker || (key != null && DISPLAYABLE_STRING_KEYS.contains(key)) ? text : "[redacted]",
          limit,
          marker && !text.equals("[absent]"));
    }
    if (value.isValueNode()) {
      return value.toString().length() <= limit
          ? new Displayed(value, false)
          : clipText("[clipped]", limit, true);
    }
    try {
      JsonNode copy =
          redactBounded(
              value,
              key,
              0,
              budget,
              new int[] {limit},
              Collections.newSetFromMap(new IdentityHashMap<>()));
      return clipText(CausalCanonicalJson.canonicalize(copy), limit, false);
    } catch (DisplayLimit exhausted) {
      return clipText("[clipped]", limit, true);
    }
  }

  private static final class DisplayLimit extends RuntimeException {}

  private static JsonNode redactBounded(
      JsonNode value, String key, int depth, int[] budget, int[] bytes, Set<JsonNode> active) {
    if (--budget[0] < 0 || depth > 32) {
      throw new DisplayLimit();
    }
    if (value.isValueNode()) {
      if (value.isTextual()) {
        String text =
            key != null && DISPLAYABLE_STRING_KEYS.contains(key) ? value.textValue() : "[redacted]";
        Displayed shown = clipText(text, Math.max(0, bytes[0]), false);
        if (shown.truncated()) {
          throw new DisplayLimit();
        }
        bytes[0] -= shown.value().textValue().getBytes(StandardCharsets.UTF_8).length + 2;
        if (bytes[0] < 0) {
          throw new DisplayLimit();
        }
        return shown.value();
      }
      bytes[0] -= 8;
      if (bytes[0] < 0) {
        throw new DisplayLimit();
      }
      return value;
    }
    if (!active.add(value) || value.size() > budget[0]) {
      throw new DisplayLimit();
    }
    bytes[0] -= 2;
    if (bytes[0] < 0) {
      throw new DisplayLimit();
    }
    JsonNode result;
    if (value.isArray()) {
      ArrayNode copy = MAPPER.createArrayNode();
      for (JsonNode entry : value) {
        copy.add(redactBounded(entry, null, depth + 1, budget, bytes, active));
      }
      result = copy;
    } else {
      TreeSet<String> keys = new TreeSet<>();
      Iterator<String> names = value.fieldNames();
      while (names.hasNext()) {
        if (keys.size() >= Math.min(budget[0], bytes[0])) {
          throw new DisplayLimit();
        }
        keys.add(names.next());
      }
      var copy = MAPPER.createObjectNode();
      for (String field : keys) {
        Displayed name = clipText(field, Math.max(0, bytes[0]), false);
        if (name.truncated()) {
          throw new DisplayLimit();
        }
        bytes[0] -= field.getBytes(StandardCharsets.UTF_8).length + 4;
        if (bytes[0] < 0) {
          throw new DisplayLimit();
        }
        copy.set(field, redactBounded(value.get(field), field, depth + 1, budget, bytes, active));
      }
      result = copy;
    }
    active.remove(value);
    return result;
  }

  /** One deferred traversal step. Absent and exit markers consume no node/depth budget. */
  private record Frame(
      JsonNode expected,
      JsonNode actual,
      String path,
      String key,
      int depth,
      Absent absent,
      boolean isExit,
      JsonNode scope) {}

  private enum Absent {
    NONE,
    EXPECTED,
    ACTUAL
  }

  private static Frame visitFrame(
      JsonNode expected, JsonNode actual, String path, String key, int depth, Absent absent) {
    return new Frame(expected, actual, path, key, depth, absent, false, null);
  }

  private static Frame exitFrame(JsonNode scope) {
    return new Frame(null, null, "", null, 0, Absent.NONE, true, scope);
  }

  private static final class Differ {
    private final String kind;
    private final String domain;
    private final Long tick;
    private final Long sequence;
    private final Integer ordinal;
    private final DiffLimits limits;
    private final List<StateDifference> differences = new ArrayList<>();

    /** Containers on the current DFS path (reference identity), for cycle detection. */
    private final Set<JsonNode> active = Collections.newSetFromMap(new IdentityHashMap<>());

    private int[] budget;
    private boolean clipped;

    private Differ(
        String kind, String domain, Long tick, Long sequence, Integer ordinal, DiffLimits limits) {
      this.kind = kind;
      this.domain = domain;
      this.tick = tick;
      this.sequence = sequence;
      this.ordinal = ordinal;
      this.limits = limits;
      this.budget = new int[] {limits.maxNodes()};
    }

    private DiffResult run(JsonNode expected, JsonNode actual) {
      // Explicit heap stack instead of recursion: traversal depth is bounded
      // by the caller's maxDepth budget, which must never control call-stack
      // use. Children are pushed in reverse so they pop in deterministic
      // depth-first order, identical to the former recursive walk.
      ArrayDeque<Frame> stack = new ArrayDeque<>();
      stack.push(
          visitFrame(
              expected == null ? MAPPER.getNodeFactory().nullNode() : expected,
              actual == null ? MAPPER.getNodeFactory().nullNode() : actual,
              "",
              null,
              0,
              Absent.NONE));
      while (!stack.isEmpty()) {
        Frame frame = stack.pop();
        if (frame.isExit()) {
          active.remove(frame.scope());
          continue;
        }
        if (clipped || differences.size() >= limits.maxDifferences()) {
          clipped = true;
          break;
        }
        visit(frame, stack);
      }
      return new DiffResult(differences, clipped);
    }

    private void push(
        String path, JsonNode expected, JsonNode actual, String key, boolean truncated) {
      if (differences.size() >= limits.maxDifferences()) {
        clipped = true;
        return;
      }
      Displayed shown = display(expected, key, limits.maxDisplayValueUtf8Bytes(), budget);
      Displayed shownActual = display(actual, key, limits.maxDisplayValueUtf8Bytes(), budget);
      boolean clip = truncated || shown.truncated() || shownActual.truncated();
      differences.add(
          new StateDifference(
              kind,
              domain,
              path,
              shown.value(),
              shownActual.value(),
              clip,
              tick,
              sequence,
              ordinal));
      if (shown.truncated() || shownActual.truncated()) {
        clipped = true;
      }
    }

    private void visit(Frame frame, ArrayDeque<Frame> stack) {
      JsonNode expected = frame.expected();
      JsonNode actual = frame.actual();
      String path = frame.path();
      String key = frame.key();
      int depth = frame.depth();
      if (--budget[0] < 0 || depth > limits.maxDepth()) {
        clipped = true;
        push(
            path,
            MAPPER.getNodeFactory().textNode("[clipped]"),
            MAPPER.getNodeFactory().textNode("[clipped]"),
            key,
            true);
        return;
      }
      if (frame.absent() == Absent.EXPECTED) {
        push(path, MAPPER.getNodeFactory().textNode("[absent]"), actual, key, false);
        return;
      }
      if (frame.absent() == Absent.ACTUAL) {
        push(path, expected, MAPPER.getNodeFactory().textNode("[absent]"), key, false);
        return;
      }
      // No deep-equals shortcut here: container equality is decided by the
      // iterative walk itself, so per-level work stays constant and hostile
      // nesting costs linear time. The node budget bounds total work and
      // reports truncation honestly when it is exhausted.
      if (shallowEqual(expected, actual)) {
        return;
      }
      if (expected == null
          || actual == null
          || !expected.isContainerNode()
          || !actual.isContainerNode()
          || expected.isArray() != actual.isArray()) {
        push(path, expected, actual, key, false);
        return;
      }
      // Cyclic input must clip with a stable marker, never recurse or loop:
      // revisiting a container already on the current path ends this branch.
      if (active.contains(expected) || active.contains(actual)) {
        push(
            path,
            MAPPER.getNodeFactory().textNode("[cycle]"),
            MAPPER.getNodeFactory().textNode("[cycle]"),
            key,
            true);
        return;
      }
      active.add(expected);
      stack.push(exitFrame(expected));
      if (actual != expected) {
        active.add(actual);
        stack.push(exitFrame(actual));
      }
      if (expected.isArray()) {
        if (expected.size() != actual.size()) {
          push(
              path + "/length",
              MAPPER.getNodeFactory().numberNode(expected.size()),
              MAPPER.getNodeFactory().numberNode(actual.size()),
              "length",
              false);
        }
        int[] budget = new int[] {limits.maxNodes()};
        int shared = Math.min(expected.size(), actual.size());
        if (shared > budget[0]) {
          push(
              path,
              MAPPER.getNodeFactory().textNode("[clipped]"),
              MAPPER.getNodeFactory().textNode("[clipped]"),
              key,
              true);
          clipped = true;
          return;
        }
        for (int index = shared - 1; index >= 0; index--) {
          stack.push(
              visitFrame(
                  expected.get(index),
                  actual.get(index),
                  path + "/" + index,
                  null,
                  depth + 1,
                  Absent.NONE));
        }
        return;
      }
      if (expected.size() > budget[0] || actual.size() > budget[0]) {
        push(
            path,
            MAPPER.getNodeFactory().textNode("[clipped]"),
            MAPPER.getNodeFactory().textNode("[clipped]"),
            key,
            true);
        clipped = true;
        return;
      }
      TreeSet<String> keys = new TreeSet<>();
      Iterator<String> names = expected.fieldNames();
      while (names.hasNext()) {
        keys.add(names.next());
      }
      names = actual.fieldNames();
      while (names.hasNext()) {
        keys.add(names.next());
      }
      if (keys.size() > budget[0]) {
        push(
            path,
            MAPPER.getNodeFactory().textNode("[clipped]"),
            MAPPER.getNodeFactory().textNode("[clipped]"),
            key,
            true);
        clipped = true;
        return;
      }
      List<String> ordered = new ArrayList<>(keys);
      for (int index = ordered.size() - 1; index >= 0; index--) {
        String child = ordered.get(index);
        String childPath = path + "/" + escapePointer(child);
        boolean inExpected = expected.has(child);
        boolean inActual = actual.has(child);
        if (!inExpected) {
          stack.push(
              visitFrame(null, actual.get(child), childPath, child, depth + 1, Absent.EXPECTED));
        } else if (!inActual) {
          stack.push(
              visitFrame(expected.get(child), null, childPath, child, depth + 1, Absent.ACTUAL));
        } else {
          stack.push(
              visitFrame(
                  expected.get(child),
                  actual.get(child),
                  childPath,
                  child,
                  depth + 1,
                  Absent.NONE));
        }
      }
    }
  }
}
