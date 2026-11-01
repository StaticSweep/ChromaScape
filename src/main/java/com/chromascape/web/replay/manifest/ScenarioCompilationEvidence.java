package com.chromascape.web.replay.manifest;

import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.integration.micro6.service.Micro6CanonicalHash;
import com.chromascape.web.replay.capability.CapabilityManifest;
import com.chromascape.web.replay.conversion.ConvertedReplayAction;
import com.chromascape.web.replay.conversion.ReplayActionConversionResult;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Source audit only: later observations are never instructions to spawn initial-world entities. */
public final class ScenarioCompilationEvidence {
  private final ObjectMapper json;
  private final CanonicalProjectionService canonical;

  public ScenarioCompilationEvidence(ObjectMapper json, CanonicalProjectionService canonical) {
    this.json = json;
    this.canonical = canonical;
  }

  /** Frozen recording context is evidence, not a value that the compiler may invent or coerce. */
  public static List<String> missingIdentityFields(JsonNode context) {
    List<String> missing = new ArrayList<>();
    for (String key :
        List.of(
            "schemaVersion",
            "runId",
            "taskKey",
            "behaviorProfileId",
            "behaviorProfileVersion",
            "semanticContractDigest",
            "settingsHash",
            "graphRevision",
            "recordingId",
            "debugSessionId",
            "contextHash")) {
      JsonNode value = context == null ? null : context.get(key);
      boolean valid;
      if (key.equals("schemaVersion") || key.equals("behaviorProfileVersion")) {
        valid =
            value != null
                && value.isIntegralNumber()
                && value.canConvertToInt()
                && (key.equals("schemaVersion") ? value.asInt() == 1 : value.asInt() > 0);
      } else {
        valid = value != null && value.isTextual() && !value.asText().isBlank();
        if (valid
            && (key.equals("semanticContractDigest")
                || key.equals("settingsHash")
                || key.equals("graphRevision")
                || key.equals("contextHash"))) {
          valid = value.asText().matches("sha256:[0-9a-f]{64}");
        }
      }
      if (!valid) missing.add(key);
    }
    if (context != null && context.isObject() && context.has("contextHash")) {
      ObjectNode basis = ((ObjectNode) context).deepCopy();
      basis.remove("contextHash");
      if (!context.path("contextHash").asText().equals(Micro6CanonicalHash.sha256WithPrefix(basis))
          && !missing.contains("contextHash")) missing.add("contextHash");
    }
    return List.copyOf(missing);
  }

  /** A correctly hashed context from a different source is still not this recording's evidence. */
  public static List<String> missingIdentityFields(
      JsonNode context, String recordingId, String debugSessionId) {
    List<String> missing = new ArrayList<>(missingIdentityFields(context));
    if (context != null) {
      if (!context.path("recordingId").asText().equals(recordingId)
          && !missing.contains("recordingId")) missing.add("recordingId");
      if (!context.path("debugSessionId").asText().equals(debugSessionId)
          && !missing.contains("debugSessionId")) missing.add("debugSessionId");
    }
    return List.copyOf(missing);
  }

  public ObjectNode world(
      List<TickSnapshot> snapshots,
      ReplayActionConversionResult conversion,
      CapabilityManifest capability) {
    ObjectNode world = json.createObjectNode();
    TickSnapshot first = snapshots.get(0);
    world.put("initialSnapshotRef", snapshotRef(first));
    ObjectNode content = world.putObject("staticContent");
    content.put("cacheRevision", capability.xrsps().cacheRevision());
    content.put("contentRevision", capability.xrsps().contentRevision());
    content.put("sourceRef", "worker-build:" + capability.xrsps().buildCommit());
    content.put("provenance", "DERIVED");
    // This is a requirement, not an attestation. The current kernel has no native world-loading
    // or deterministic dynamic-spawn contract; observing a later entity cannot supply one.
    ArrayNode nativeBindings = world.putArray("requiredNativeBindings");
    ArrayNode targets = world.putArray("actionTargets");
    String previousRegion = region(first);
    for (TickSnapshot snapshot : snapshots) {
      String currentRegion = region(snapshot);
      if ("unknown".equals(currentRegion) || !currentRegion.equals(previousRegion)) {
        nativeBindings
            .addObject()
            .put("tick", snapshot.getTick())
            .put("kind", "REGION_OR_PLANE_TRANSITION")
            .put("stableId", previousRegion + "->" + currentRegion)
            .put("sourceRef", snapshotRef(snapshot))
            .put("provenance", "DERIVED");
      }
      previousRegion = currentRegion;
    }
    for (ConvertedReplayAction converted : conversion.actions()) {
      var action = converted.action();
      String kind =
          switch (action.getActionType()) {
            case "INTERACT_NPC", "USE_ITEM_ON_NPC" -> "NPC";
            case "INTERACT_OBJECT" -> "OBJECT";
            case "INTERACT_GROUND_ITEM" -> "GROUND_ITEM";
            case "INTERACT_WIDGET" -> "WIDGET";
            default -> null;
          };
      if (kind == null) {
        if (!List.of("WAIT_UNTIL_TICK", "WALK_TO").contains(action.getActionType())) {
          nativeBindings
              .addObject()
              .put("tick", action.getTick())
              .put("kind", "EXACT_ITEM_OR_INTERFACE_SEMANTICS")
              .put("stableId", action.getActionType())
              .put(
                  "sourceRef",
                  "debug-session:"
                      + first.getSessionId()
                      + "/action:"
                      + action.getTick()
                      + ":"
                      + action.getSequence())
              .put("provenance", "DERIVED");
        }
        continue;
      }
      Object id = action.getTarget() == null ? null : action.getTarget().get("stableId");
      String stableId = id instanceof String value ? value : "";
      TickSnapshot atTick =
          snapshots.stream()
              .filter(snapshot -> snapshot.getTick() == action.getTick())
              .findFirst()
              .orElseThrow(
                  () ->
                      ReplayException.invalid(
                          ReplayErrorCode.TICK_RANGE_INVALID,
                          "No snapshot exists at action tick " + action.getTick()));
      List<JsonNode> matches = matches(entities(atTick, kind), stableId);
      String source = snapshotRef(atTick) + "/action:" + action.getSequence();
      if (stableId.isBlank() || matches.isEmpty()) {
        throw ReplayException.invalid(
            ReplayErrorCode.ACTION_TARGET_UNRESOLVED,
            kind + " target " + stableId + " is absent at execution tick; source=" + source);
      }
      if (matches.size() != 1) {
        throw ReplayException.invalid(
            ReplayErrorCode.ACTION_AMBIGUOUS,
            kind + " target " + stableId + " is ambiguous at execution tick; source=" + source);
      }
      if ("WIDGET".equals(kind)) {
        requireWidgetIdentity(action.getArguments(), matches.get(0), source);
      }
      ObjectNode target = targets.addObject();
      target.put("tick", action.getTick());
      target.put("sequence", action.getSequence());
      target.put("kind", kind);
      target.put("stableId", stableId);
      target.put("sourceRef", source);
      target.put("provenance", "OBSERVED");
      if (matches(entities(first, kind), stableId).isEmpty()) {
        nativeBindings.add(target.deepCopy());
      }
      // A captured identifier is not proof of bank quantities, widget/container semantics,
      // jewelry charges/destinations, or exchange behavior. Until the pinned kernel publishes
      // that contract these actions remain inspectable evidence, never an execution claim.
      Object option = action.getArguments() == null ? null : action.getArguments().get("option");
      if (!"NPC".equals(kind)
          || !(option instanceof String text)
          || !List.of("net", "bait", "lure", "cage", "harpoon").contains(text)) {
        ObjectNode requirement = target.deepCopy();
        requirement.put("kind", "EXACT_BANK_WIDGET_OR_EXCHANGE_SEMANTICS");
        nativeBindings.add(requirement);
      }
    }
    return world;
  }

  private String region(TickSnapshot snapshot) {
    JsonNode state = snapshot.getPlayerState();
    if (state == null) return "unknown";
    JsonNode position = state.path("position");
    if (!position.isObject()) position = state.path("worldPoint");
    if (!position.isObject()) position = state;
    JsonNode x = position.has("x") ? position.path("x") : position.path("worldX");
    JsonNode y =
        position.has("y")
            ? position.path("y")
            : position.has("z") ? position.path("z") : position.path("worldY");
    if (!x.canConvertToInt() || !y.canConvertToInt() || !position.path("plane").canConvertToInt()) {
      return "unknown";
    }
    return (x.asInt() >> 6) + ":" + (y.asInt() >> 6) + ":" + position.path("plane").asInt();
  }

  private void requireWidgetIdentity(
      Map<String, Object> arguments, JsonNode widget, String source) {
    Object id = arguments == null ? null : arguments.get("widgetId");
    if (!(id instanceof Number number)
        || number.doubleValue() != number.intValue()
        || !widget.path("widgetId").isIntegralNumber()
        || !widget.path("widgetId").canConvertToInt()
        || widget.path("widgetId").asInt() != number.intValue()) {
      throw ReplayException.invalid(
          ReplayErrorCode.ACTION_AMBIGUOUS,
          "Widget action lacks an exact captured widget identity; source=" + source);
    }
    if (arguments.containsKey("widgetIndex")) {
      Object index = arguments.get("widgetIndex");
      JsonNode captured =
          widget.has("widgetIndex") ? widget.path("widgetIndex") : widget.path("index");
      if (!(index instanceof Number indexNumber)
          || indexNumber.doubleValue() != indexNumber.intValue()
          || !captured.isIntegralNumber()
          || !captured.canConvertToInt()
          || captured.asInt() != indexNumber.intValue()
          || widget.has("widgetIndex")
              && widget.has("index")
              && (!widget.path("index").isIntegralNumber()
                  || !widget.path("index").canConvertToInt()
                  || widget.path("index").asInt() != indexNumber.intValue())) {
        throw ReplayException.invalid(
            ReplayErrorCode.ACTION_AMBIGUOUS,
            "Widget action lacks an exact unambiguous captured widget index; source=" + source);
      }
    }
  }

  private List<JsonNode> matches(JsonNode entities, String stableId) {
    if (entities == null || !entities.isArray() || stableId.isBlank()) return List.of();
    return java.util.stream.StreamSupport.stream(entities.spliterator(), false)
        .filter(entity -> stableId.equals(entity.path("stableId").asText()))
        .toList();
  }

  private JsonNode entities(TickSnapshot snapshot, String kind) {
    return switch (kind) {
      case "NPC" -> snapshot.getNpcs();
      case "OBJECT" -> snapshot.getGameObjects();
      case "GROUND_ITEM" -> snapshot.getGroundItems();
      case "WIDGET" -> snapshot.getWidgets();
      default -> null;
    };
  }

  String snapshotRef(TickSnapshot snapshot) {
    ObjectNode evidence = json.createObjectNode();
    evidence.put("sessionId", snapshot.getSessionId());
    evidence.put("tick", snapshot.getTick());
    evidence.set("playerState", snapshot.getPlayerState());
    evidence.set("npcs", snapshot.getNpcs());
    evidence.set("objects", snapshot.getGameObjects());
    evidence.set("groundItems", snapshot.getGroundItems());
    evidence.set("widgets", snapshot.getWidgets());
    evidence.set("inventory", snapshot.getInventory());
    evidence.set("equipment", snapshot.getEquipment());
    evidence.set("quest", snapshot.getQuest());
    evidence.set("questHelper", snapshot.getQuestHelper());
    evidence.set("vars", snapshot.getVars());
    evidence.set("threats", snapshot.getThreats());
    evidence.set("projectiles", snapshot.getProjectiles());
    evidence.set("cooldowns", snapshot.getCooldowns());
    evidence.set("dialogue", snapshot.getDialogue());
    evidence.set("spells", snapshot.getSpells());
    evidence.set("groundObjects", snapshot.getGroundObjects());
    evidence.set("players", snapshot.getPlayers());
    return "snapshot:" + canonical.sha256Hex(canonical.canonicalJsonBytes(evidence));
  }

  ObjectNode audit(
      ObjectNode document, JsonNode context, List<TickSnapshot> snapshots, ObjectNode world) {
    ObjectNode audit = json.createObjectNode();
    audit.set("world", world);
    if (context != null) audit.set("behaviorRunContext", context);
    ObjectNode fields = audit.putObject("fieldProvenance");
    describe(document, "", fields, document, snapshots);
    if (context != null) {
      describe(context, "/compilationEvidence/behaviorRunContext", fields, document, snapshots);
    }
    describe(world, "/compilationEvidence/world", fields, document, snapshots);
    return audit;
  }

  private void describe(
      JsonNode value,
      String path,
      ObjectNode fields,
      ObjectNode document,
      List<TickSnapshot> snapshots) {
    ObjectNode source = fields.putObject(path);
    String provenance = "DERIVED";
    String reference = "compiler:" + ScenarioManifestV2Compiler.COMPILER_VERSION;
    if (path.startsWith("/compilationEvidence/behaviorRunContext")) {
      provenance = "OBSERVED";
      reference =
          "recording:"
              + document.path("recording").path("recordingId").asText()
              + "/runContext"
              + path.substring("/compilationEvidence/behaviorRunContext".length());
    } else if (path.startsWith("/initialState")) {
      reference = snapshotRef(snapshots.get(0));
      provenance =
          path.contains("/position") || path.endsWith("/spotType") ? "DERIVED" : "OBSERVED";
      if (path.equals("/initialState/collisionRevision")
          && capturedCollisionRevision(snapshots.get(0)) == null) {
        // The compiler may supply this from the pinned content identity; name that source instead
        // of presenting a derived revision as captured evidence.
        provenance = "DERIVED";
        reference =
            "worker-build:"
                + document.path("runtime").path("xrspsBuildCommit").asText()
                + "/contentRevision:"
                + document.path("runtime").path("contentRevision").asText();
      }
      JsonNode selected = document.path("curation").path("sourceCuration");
      String pointer = path.substring("/initialState".length());
      String suppliedPointer = suppliedPointer(document, pointer);
      if (suppliedPointer != null) {
        provenance = "OPERATOR_SUPPLIED";
        reference =
            "curation:"
                + selected.path("id").asText()
                + "/content/initialStateOverrides"
                + suppliedPointer;
      }
    } else if (path.startsWith("/execution")) {
      reference =
          "compiler:" + ScenarioManifestV2Compiler.COMPILER_VERSION + "/execution-limits/v1";
      provenance = path.equals("/execution/maximumTicks") ? "DERIVED" : "DEFAULTED";
    } else if (path.equals("/termination") || path.startsWith("/termination/")) {
      JsonNode selected = document.path("curation").path("sourceCuration");
      if (selected.path("contentSnapshot").has("termination")) {
        provenance = "OPERATOR_SUPPLIED";
        reference = "curation:" + selected.path("id").asText() + "/content" + path;
      } else {
        provenance = "DEFAULTED";
        reference =
            "compiler:" + ScenarioManifestV2Compiler.COMPILER_VERSION + "/termination-policy/v1";
      }
    } else if (path.startsWith("/expectations")) {
      reference = snapshotRef(snapshots.get(snapshots.size() - 1));
    } else if (path.startsWith("/actions/")) {
      int index = Integer.parseInt(path.substring("/actions/".length()).split("/")[0]);
      JsonNode action = document.path("actions").get(index);
      reference =
          "curation:"
              + document.path("curation").path("curationId").asText()
              + "/action:"
              + action.path("sourceActionTick").asLong()
              + ":"
              + action.path("sourceActionSequence").asInt();
      provenance =
          switch (action.path("provenance").asText()) {
            case "CAPTURED" -> "OBSERVED";
            case "OPERATOR_SUPPLIED" -> "OPERATOR_SUPPLIED";
            case "DEFAULTED" -> "DEFAULTED";
            default -> "DERIVED";
          };
      if ("OPERATOR_SUPPLIED".equals(provenance)) {
        reference = action.path("provenanceReason").asText();
      }
      if (path.endsWith("/mappingVersion")) {
        provenance = "DERIVED";
        reference = "compiler:" + ScenarioManifestV2Compiler.COMPILER_VERSION;
      }
    } else if (path.startsWith("/runtime")) {
      reference = "worker-build:" + document.path("runtime").path("xrspsBuildCommit").asText();
    } else if (path.startsWith("/recording")) {
      reference = "recording:" + document.path("recording").path("recordingChecksum").asText();
    } else if (path.startsWith("/curation")) {
      reference =
          "curation:"
              + document.path("curation").path("curationId").asText()
              + ":"
              + document.path("curation").path("checksum").asText();
    } else if (path.startsWith("/conversion")) {
      reference = "conversion:" + document.path("conversion").path("conversionChecksum").asText();
    } else if (path.startsWith("/determinism") && !path.endsWith("/seed")
        || path.startsWith("/tolerances")
        || path.equals("/controlAllowed")) {
      provenance = "DEFAULTED";
    }
    source.put("provenance", provenance);
    source.put("sourceRef", reference);
    source.put("outputPointer", path);
    if (value.isObject()) {
      value
          .fields()
          .forEachRemaining(
              field ->
                  describe(
                      field.getValue(),
                      path + "/" + field.getKey().replace("~", "~0").replace("/", "~1"),
                      fields,
                      document,
                      snapshots));
    } else if (value.isArray()) {
      for (int index = 0; index < value.size(); index++) {
        describe(value.get(index), path + "/" + index, fields, document, snapshots);
      }
    }
  }

  /** Returns the captured collision revision, or null when the recording never observed one. */
  private static String capturedCollisionRevision(TickSnapshot snapshot) {
    JsonNode state = snapshot == null ? null : snapshot.getPlayerState();
    if (state == null || !state.isObject()) return null;
    JsonNode value =
        state.path("collisionRevision").isTextual()
            ? state.path("collisionRevision")
            : state.path("world").path("collisionRevision");
    return value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
  }

  /** Slot/entity normalization must not point an output index at a different fixture entry. */
  private String suppliedPointer(ObjectNode document, String pointer) {
    JsonNode overrides =
        document
            .path("curation")
            .path("sourceCuration")
            .path("contentSnapshot")
            .path("initialStateOverrides");
    if (pointer.isEmpty()) return null;
    String sourcePointer = pointer;
    for (String array : List.of("/player/inventory", "/player/equipment", "/npcs")) {
      if (!pointer.startsWith(array + "/") || !overrides.at(array).isArray()) continue;
      String suffix = pointer.substring(array.length() + 1);
      int separator = suffix.indexOf('/');
      int index = Integer.parseInt(separator < 0 ? suffix : suffix.substring(0, separator));
      JsonNode output = document.path("initialState").at(array).path(index);
      String identity = array.equals("/npcs") ? "stableId" : "slot";
      JsonNode entries = overrides.at(array);
      for (int sourceIndex = 0; sourceIndex < entries.size(); sourceIndex++) {
        if (output.has(identity)
            && output.get(identity).equals(entries.get(sourceIndex).get(identity))) {
          sourcePointer =
              array + "/" + sourceIndex + (separator < 0 ? "" : suffix.substring(separator));
          break;
        }
      }
    }
    return overrides.at(sourcePointer).isMissingNode() ? null : sourcePointer;
  }
}
