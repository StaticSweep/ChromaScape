package com.chromascape.web.gameplay.contract.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;

/**
 * Opaque SC-1429 v2 eligibility result that can only be constructed by deterministic replay. The
 * factory schema-validates every input, runs {@link GameplayV2EligibilityFilter} for the same
 * catalog, observation and planning input at the result's claimed {@code evaluatedAt} (which must
 * itself be plausible relative to {@code now}), verifies the catalog's declared digest against its
 * own bytes, and only seals the result when every value/status entry matches the recomputation. A
 * fabricated but structurally plausible {@code ELIGIBLE} can therefore never obtain a sealed
 * handle, and provider adapters that accept only sealed handles cannot be invoked without replay.
 *
 * <p>The seal is catalog-bound: the canonical catalog digest, observation digest, planning-input
 * digest and eligibility-result digest are captured at seal time. {@link
 * com.chromascape.web.gameplay.provider.v2.GameplayV2ProviderAdapter#adapt(
 * GameplayV2ValidatedEligibility, JsonNode, String, JsonNode)} rejects a catalog that does not
 * reproduce the bound digest, so a sealed result cannot be replayed against a substituted catalog.
 *
 * <p>The seal is also immutable: inputs are defensively deep-copied at seal time and {@link
 * #result()} returns a fresh deep copy on every call, so mutating the caller's original object or
 * the returned handle can never fabricate a sealed result. Snapshot-at-boundary semantics: every
 * mutable {@code JsonNode} input is deep-copied immediately on {@code seal} entry and all
 * validation, replay, digest calculation and storage run exclusively against those snapshots.
 */
public final class GameplayV2ValidatedEligibility {
  private static final ObjectMapper SEAL_MAPPER = new ObjectMapper();

  private final JsonNode eligibilityResult;
  private final String catalogDigest;
  private final String observationDigest;
  private final String planningInputDigest;
  private final String eligibilityResultDigest;

  private GameplayV2ValidatedEligibility(
      JsonNode eligibilityResult,
      String catalogDigest,
      String observationDigest,
      String planningInputDigest,
      String eligibilityResultDigest) {
    this.eligibilityResult = eligibilityResult;
    this.catalogDigest = catalogDigest;
    this.observationDigest = observationDigest;
    this.planningInputDigest = planningInputDigest;
    this.eligibilityResultDigest = eligibilityResultDigest;
  }

  /** The replay-validated eligibility result. A fresh deep copy; mutating it is harmless. */
  public JsonNode result() {
    return eligibilityResult.deepCopy();
  }

  /** Canonical catalog digest this seal was bound to at seal time. */
  public String catalogDigest() {
    return catalogDigest;
  }

  /** Canonical observation digest captured at seal time. */
  public String observationDigest() {
    return observationDigest;
  }

  /** Canonical planning-input digest captured at seal time. */
  public String planningInputDigest() {
    return planningInputDigest;
  }

  /** Canonical eligibility-result digest captured at seal time. */
  public String eligibilityResultDigest() {
    return eligibilityResultDigest;
  }

  /**
   * Replays deterministic eligibility and seals a deep copy of the supplied result. Schema
   * validation runs first on the catalog, observation, planning input and eligibility result
   * (unknown closed-schema properties rejected); the catalog's declared {@code catalogDigest} is
   * recomputed from its own bytes and must match; the claimed {@code evaluatedAt} must be
   * plausible: never in the future relative to {@code now}, never before the observation capture,
   * and never beyond the observation {@code maxAgeMs}.
   *
   * @throws GameplayV2ContractValidator.GameplayV2ContractViolation with {@code IDENTITY_MISMATCH}
   *     when the supplied result is not exactly what the deterministic filter produces for these
   *     inputs, with {@code INVALID_*} when an input fails schema validation, or with {@code
   *     NODE_CATALOG_MISMATCH} when the supplied catalog is passed for substitution checks.
   */
  public static GameplayV2ValidatedEligibility seal(
      GameplayV2ContractValidator validator,
      JsonNode catalog,
      JsonNode observation,
      JsonNode planningInput,
      JsonNode eligibilityResult,
      Instant now) {
    if (validator == null) {
      throw new GameplayV2ContractValidator.GameplayV2ContractViolation(
          "IDENTITY_MISMATCH", "Eligibility sealing requires the shared contract validator");
    }
    if (catalog == null
        || observation == null
        || planningInput == null
        || eligibilityResult == null
        || now == null) {
      throw new GameplayV2ContractValidator.GameplayV2ContractViolation(
          "IDENTITY_MISMATCH", "Eligibility sealing requires catalog, observation, planning input");
    }
    // Snapshot-at-boundary: deep-copy every mutable caller input on entry and operate
    // exclusively on the snapshots below, so a concurrent caller mutation can never
    // alter bytes after replay but before the final digest/copy.
    JsonNode catalogSnapshot = catalog.deepCopy();
    JsonNode observationSnapshot = observation.deepCopy();
    JsonNode planningSnapshot = planningInput.deepCopy();
    JsonNode resultSnapshot = eligibilityResult.deepCopy();
    validator.validateWithRules("immutableTaskCatalogSnapshot", catalogSnapshot);
    validator.validateWithRules("semanticObservation", observationSnapshot);
    validator.validateWithRules("planningInput", planningSnapshot);
    validator.validateWithRules("eligibilityResult", resultSnapshot);
    String replay =
        GameplayV2EnvelopeRules.checkEligibilityReplay(
            catalogSnapshot, observationSnapshot, planningSnapshot, resultSnapshot, now);
    if (replay != null) {
      throw new GameplayV2ContractValidator.GameplayV2ContractViolation(
          "IDENTITY_MISMATCH", "Eligibility result does not match deterministic filter replay");
    }
    String recomputedCatalog = GameplayV2EnvelopeRules.catalogDigestOf(catalogSnapshot);
    if (!recomputedCatalog.equals(catalogSnapshot.path("catalogDigest").asText())) {
      throw new GameplayV2ContractValidator.GameplayV2ContractViolation(
          "IDENTITY_MISMATCH", "Catalog digest does not match catalog bytes");
    }
    String observationDigest =
        GameplayV2EligibilityFilter.canonicalDigest(SEAL_MAPPER, observationSnapshot);
    String planningDigest =
        GameplayV2EligibilityFilter.canonicalDigest(SEAL_MAPPER, planningSnapshot);
    String resultDigest = GameplayV2EligibilityFilter.canonicalDigest(SEAL_MAPPER, resultSnapshot);
    return new GameplayV2ValidatedEligibility(
        resultSnapshot.deepCopy(),
        recomputedCatalog,
        observationDigest,
        planningDigest,
        resultDigest);
  }

  /**
   * Verifies that the supplied catalog reproduces this seal's bound catalog digest. Returns {@code
   * null} when bound, otherwise the stable {@code NODE_CATALOG_MISMATCH} failure code.
   */
  public String checkCatalogBinding(JsonNode catalog) {
    if (catalog == null || catalog.isMissingNode() || catalog.isNull() || !catalog.isObject()) {
      return "NODE_CATALOG_MISMATCH";
    }
    // Snapshot once so the declared-digest read and the recomputation observe the
    // same bytes; a non-object can never reach the digest cast below.
    JsonNode snapshot = catalog.deepCopy();
    String recomputed = GameplayV2EnvelopeRules.catalogDigestOf(snapshot);
    if (!catalogDigest.equals(snapshot.path("catalogDigest").asText())
        || !catalogDigest.equals(recomputed)) {
      return "NODE_CATALOG_MISMATCH";
    }
    return null;
  }
}
