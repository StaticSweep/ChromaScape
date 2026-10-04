package com.chromascape.web.replay.hash;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SC-275 fixed-slot entity identity views, mirroring {@code entity-slot-view-v2.ts}.
 *
 * <p>Slots are a derived, bounded presentation of canonical entity identities: canonical stable IDs
 * never change because of sorting, truncation, or slot reuse. The view digest binds the source
 * tick, source projection digest, selection policy, capacity, slot map, and masks, so stale views
 * reject without mutating anything.
 */
public final class EntitySlotView {

  /** Fixed slot-view schema version. */
  public static final int VIEW_VERSION = 1;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private EntitySlotView() {}

  /** One selectable entity identity with its ranking inputs. */
  public record SlotEntity(String stableId, double priority, double distance) {
    /** Unranked entity: zero priority and distance. */
    public SlotEntity(String stableId) {
      this(stableId, 0, 0);
    }
  }

  /** A bound, digest-addressed slot view. */
  public record SlotView(
      int viewVersion,
      long selectionPolicyVersion,
      long sourceTick,
      String sourceProjectionDigest,
      int capacity,
      List<String> slotMap,
      List<Boolean> occupancyMask,
      List<Boolean> legalActionMask,
      int truncatedCount,
      String viewDigest) {}

  /**
   * Builds a slot view with deterministic selection: priority descending, distance ascending,
   * stableId ascending (UTF-16). No wall-clock, locale, or insertion-order influence.
   */
  public static SlotView build(
      List<SlotEntity> entities,
      int capacity,
      long selectionPolicyVersion,
      long sourceTick,
      String sourceProjectionDigest,
      Set<String> legalIds) {
    if (capacity < 1 || capacity > 1024) {
      throw new Sc275Exception("SLOT_VIEW_INVALID", "capacity must be within [1, 1024]");
    }
    SemanticHashV2.assertDigest(sourceProjectionDigest, "sourceProjectionDigest");
    Set<String> seen = new HashSet<>();
    for (SlotEntity entity : entities) {
      if (entity.stableId() == null || entity.stableId().isEmpty()) {
        throw new Sc275Exception("SLOT_VIEW_INVALID", "entity stableId must be a non-empty string");
      }
      if (!seen.add(entity.stableId())) {
        throw new Sc275Exception(
            "SLOT_VIEW_INVALID", "duplicate entity stableId " + entity.stableId());
      }
    }
    List<SlotEntity> ranked = new ArrayList<>(entities);
    ranked.sort(
        Comparator.comparingDouble(SlotEntity::priority)
            .reversed()
            .thenComparingDouble(SlotEntity::distance)
            .thenComparing(SlotEntity::stableId));
    List<SlotEntity> selected = ranked.subList(0, Math.min(capacity, ranked.size()));
    List<String> slotMap = new ArrayList<>();
    for (SlotEntity entity : selected) {
      slotMap.add(entity.stableId());
    }
    while (slotMap.size() < capacity) {
      slotMap.add(null);
    }
    Set<String> legal = legalIds != null ? legalIds : new HashSet<>();
    if (legalIds == null) {
      for (String id : slotMap) {
        if (id != null) {
          legal.add(id);
        }
      }
    }
    List<Boolean> occupancy = new ArrayList<>();
    List<Boolean> legalMask = new ArrayList<>();
    for (String id : slotMap) {
      occupancy.add(id != null);
      legalMask.add(id != null && legal.contains(id));
    }
    ObjectNode envelope = MAPPER.createObjectNode();
    envelope.put("tag", "sc275-slotview-v1");
    envelope.put("viewVersion", VIEW_VERSION);
    envelope.put("selectionPolicyVersion", selectionPolicyVersion);
    envelope.put("sourceTick", sourceTick);
    envelope.put("sourceProjectionDigest", sourceProjectionDigest);
    envelope.put("capacity", capacity);
    ArrayNode slots = MAPPER.createArrayNode();
    for (String id : slotMap) {
      if (id == null) {
        slots.addNull();
      } else {
        slots.add(id);
      }
    }
    envelope.set("slotMap", slots);
    ArrayNode occupancyNode = MAPPER.createArrayNode();
    for (Boolean bit : occupancy) {
      occupancyNode.add(bit);
    }
    envelope.set("occupancyMask", occupancyNode);
    ArrayNode legalNode = MAPPER.createArrayNode();
    for (Boolean bit : legalMask) {
      legalNode.add(bit);
    }
    envelope.set("legalActionMask", legalNode);
    envelope.put("truncatedCount", Math.max(0, ranked.size() - capacity));
    String digest =
        "sha256:" + SemanticHashV2.sha256Hex(CausalCanonicalJson.canonicalize(envelope));
    return new SlotView(
        VIEW_VERSION,
        selectionPolicyVersion,
        sourceTick,
        sourceProjectionDigest,
        capacity,
        Collections.unmodifiableList(new ArrayList<>(slotMap)),
        List.copyOf(occupancy),
        List.copyOf(legalMask),
        Math.max(0, ranked.size() - capacity),
        digest);
  }

  /**
   * Resolves a slot to its canonical stable ID. Unknown, stale, empty, and masked slots fail with a
   * stable reason and never mutate state.
   */
  public static String resolve(
      SlotView view, long expectedTick, int slot, String expectedDigest, Set<String> presentIds) {
    if (view.viewVersion() != VIEW_VERSION) {
      throw new Sc275Exception("SLOT_VIEW_STALE", "unknown slot view version");
    }
    SemanticHashV2.assertDigest(expectedDigest, "expectedDigest");
    if (!expectedDigest.equals(view.sourceProjectionDigest())
        || expectedTick != view.sourceTick()) {
      throw new Sc275Exception(
          "SLOT_VIEW_STALE", "slot view does not describe the current projection");
    }
    recomputeViewDigest(view);
    if (slot < 0 || slot >= view.capacity() || slot >= view.slotMap().size()) {
      throw new Sc275Exception("SLOT_VIEW_STALE", "slot " + slot + " is outside the view");
    }
    String stableId = view.slotMap().get(slot);
    if (stableId == null || !view.occupancyMask().get(slot)) {
      throw new Sc275Exception("SLOT_EMPTY", "slot " + slot + " is empty");
    }
    if (!view.legalActionMask().get(slot)) {
      throw new Sc275Exception("SLOT_ACTION_MASKED", "slot " + slot + " is not legal for action");
    }
    if (presentIds == null || !presentIds.contains(stableId)) {
      throw new Sc275Exception("SLOT_EMPTY", "entity " + stableId + " is no longer present");
    }
    return stableId;
  }

  /** Recomputes and compares the view digest; internal tampering rejects as stale. */
  private static void recomputeViewDigest(SlotView view) {
    ObjectNode envelope = MAPPER.createObjectNode();
    envelope.put("tag", "sc275-slotview-v1");
    envelope.put("viewVersion", view.viewVersion());
    envelope.put("selectionPolicyVersion", view.selectionPolicyVersion());
    envelope.put("sourceTick", view.sourceTick());
    envelope.put("sourceProjectionDigest", view.sourceProjectionDigest());
    envelope.put("capacity", view.capacity());
    ArrayNode slots = MAPPER.createArrayNode();
    for (String id : view.slotMap()) {
      if (id == null) {
        slots.addNull();
      } else {
        slots.add(id);
      }
    }
    envelope.set("slotMap", slots);
    ArrayNode occupancyNode = MAPPER.createArrayNode();
    for (Boolean bit : view.occupancyMask()) {
      occupancyNode.add(bit);
    }
    envelope.set("occupancyMask", occupancyNode);
    ArrayNode legalNode = MAPPER.createArrayNode();
    for (Boolean bit : view.legalActionMask()) {
      legalNode.add(bit);
    }
    envelope.set("legalActionMask", legalNode);
    envelope.put("truncatedCount", view.truncatedCount());
    String recomputed =
        "sha256:" + SemanticHashV2.sha256Hex(CausalCanonicalJson.canonicalize(envelope));
    if (!recomputed.equals(view.viewDigest())) {
      throw new Sc275Exception("SLOT_VIEW_STALE", "slot view digest does not match its content");
    }
  }
}
