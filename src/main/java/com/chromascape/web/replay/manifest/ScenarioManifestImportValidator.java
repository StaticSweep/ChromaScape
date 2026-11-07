package com.chromascape.web.replay.manifest;

import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;

/**
 * First-stage gate for scenario-manifest imports (SC-274).
 *
 * <p>Every scenario document loaded from bytes passes this validator before any semantic handling:
 * the byte size is bounded, the bytes must parse as a JSON object, and the document must satisfy
 * the checked-in {@code scenario-manifest-v2} contract. Action and identity boundaries are closed;
 * captured state remains inert JSON, never polymorphic code or a remote resource. This validator
 * performs no I/O beyond the classpath schema, interprets no field as code, and resolves no
 * reference over the network — the schema is self-contained (local {@code #/$defs/…} references
 * only), so code or network routes cannot enter through an import.
 */
public final class ScenarioManifestImportValidator {
  public static final String RESOURCE = "contracts/replay/scenario-manifest-v2.schema.json";

  /**
   * Largest accepted import. Compiled scenario manifests are small JSON documents (kilobytes); a
   * 256 KiB ceiling leaves ample headroom while failing closed on oversized payloads.
   */
  public static final int MAX_MANIFEST_BYTES = 256 * 1024;

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(64)
                          .maxStringLength(MAX_MANIFEST_BYTES)
                          .maxNumberLength(128)
                          .build())
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamWriteConstraints(
                      StreamWriteConstraints.builder().maxNestingDepth(64).build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final JsonSchema SCHEMA = load();

  private ScenarioManifestImportValidator() {}

  /**
   * Validates raw import bytes and returns the parsed document.
   *
   * @throws ReplayException with {@code PAYLOAD_TOO_LARGE} when the size bound is exceeded, or a
   *     stable {@code VALIDATION_ERROR} when the bytes are not a schema-satisfying JSON object
   */
  public static JsonNode validate(byte[] content) {
    if (content == null || content.length == 0) {
      throw ReplayException.badRequest(
          ReplayErrorCode.VALIDATION_ERROR, "scenario manifest import is empty");
    }
    if (content.length > MAX_MANIFEST_BYTES) {
      throw new ReplayException(
          ReplayErrorCode.VALIDATION_ERROR,
          HttpStatus.PAYLOAD_TOO_LARGE,
          false,
          "scenario manifest import exceeds the maximum size",
          "Reduce the manifest to " + MAX_MANIFEST_BYTES + " bytes or fewer",
          null,
          null);
    }
    JsonNode document;
    try {
      document = OBJECT_MAPPER.readTree(content);
    } catch (IOException exception) {
      throw ReplayException.badRequest(
          ReplayErrorCode.VALIDATION_ERROR, "scenario manifest import is not valid JSON");
    }
    validate(document);
    return document;
  }

  /** Validates an already-parsed document against the checked-in contract. */
  public static void validate(JsonNode document) {
    if (document == null || !document.isObject()) {
      throw ReplayException.badRequest(
          ReplayErrorCode.VALIDATION_ERROR, "scenario manifest import must be a JSON object");
    }
    try {
      OBJECT_MAPPER.writeValue(new SizeBound(), document);
    } catch (IOException exception) {
      for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
        if (cause instanceof SizeExceeded) {
          throw new ReplayException(
              ReplayErrorCode.VALIDATION_ERROR,
              HttpStatus.PAYLOAD_TOO_LARGE,
              false,
              "scenario manifest import exceeds the maximum size",
              null);
        }
      }
      throw ReplayException.badRequest(
          ReplayErrorCode.VALIDATION_ERROR, "scenario manifest import is not valid JSON");
    }
    // Bound recursive policy shape before handing it to the recursive schema validator.
    TerminationPolicyContract.validateManifest(document);
    Set<ValidationMessage> violations = SCHEMA.validate(document);
    if (violations != null && !violations.isEmpty()) {
      // Stable failure code only; schema paths and document content stay out of the message.
      throw ReplayException.invalid(
          ReplayErrorCode.VALIDATION_ERROR,
          "scenario manifest import does not satisfy the v2 schema");
    }
    for (JsonNode action : document.path("actions")) {
      JsonNode input = action.path("input");
      if ("exchange-offer".equals(input.path("kind").asText())
          && input.path("quantity").asLong() * input.path("price").asLong() > Integer.MAX_VALUE) {
        throw ReplayException.invalid(
            ReplayErrorCode.VALIDATION_ERROR,
            "Exchange offer value exceeds the exact runtime bound");
      }
    }
    ExecutionLimitContract.validate(document);
    NativeNpcBindingContract.validateManifest(document);
    NativeBankStateContract.validateInitialState(document.path("initialState"));
    JsonNode selected = document.path("curation").path("sourceCuration");
    if (!selected.isMissingNode()) {
      var selectedCanonical = new CanonicalProjectionService();
      if (!selected
              .path("canonicalContentChecksum")
              .asText()
              .equals(
                  selectedCanonical.sha256Hex(
                      selectedCanonical.canonicalJsonBytes(selected.path("contentSnapshot"))))
          || !selected
              .path("role")
              .asText()
              .equals(document.path("curation").path("datasetRole").asText())) {
        throw ReplayException.invalid(
            ReplayErrorCode.VALIDATION_ERROR,
            "Selected curation content or dataset role does not match its immutable pin");
      }
    }
    var basis = ((com.fasterxml.jackson.databind.node.ObjectNode) document).deepCopy();
    basis.remove("manifestChecksum");
    var canonical = new CanonicalProjectionService();
    String expected = canonical.sha256Hex(canonical.canonicalJsonBytes(basis));
    if (!expected.equals(document.path("manifestChecksum").asText())) {
      throw ReplayException.invalid(
          ReplayErrorCode.VALIDATION_ERROR,
          "scenario manifest checksum does not match its content");
    }
  }

  /** Validate again at the execution boundary; database flags cannot override source gaps. */
  public static void requireSimulationAllowed(JsonNode document) {
    validate(document);
    if (TerminationPolicyContract.nonempty(document)) {
      throw ReplayException.invalid(
          ReplayErrorCode.SIM_INITIAL_STATE_INCOMPLETE,
          "TERMINATION_POLICY_UNSUPPORTED: native exact-boundary evaluation is not yet qualified");
    }
    if (!document.path("compilationEvidence").isObject()
        || NativeNpcBindingContract.hasNativeWorld(document.path("initialState"))
        || !ScenarioCompilationEvidence.missingIdentityFields(
                document.path("compilationEvidence").path("behaviorRunContext"),
                document.path("recording").path("recordingId").asText(),
                document.path("recording").path("debugSessionId").asText())
            .isEmpty()
        || !hasProvenance(
            document, "", document.path("compilationEvidence").path("fieldProvenance"))
        || !document.path("conversion").path("gaps").isEmpty()
        || !document
            .path("compilationEvidence")
            .path("world")
            .path("requiredNativeBindings")
            .isEmpty()) {
      throw ReplayException.invalid(
          ReplayErrorCode.SIM_INITIAL_STATE_INCOMPLETE,
          "Scenario has unresolved source gaps or lacks the source audit; recompile before causal"
              + " simulation");
    }
  }

  private static boolean hasProvenance(JsonNode value, String pointer, JsonNode index) {
    if (pointer.equals("/compilationEvidence/fieldProvenance")) return true;
    if (!pointer.equals("/compilationEvidence") && !index.has(pointer)) return false;
    if (value.isObject()) {
      var fields = value.fields();
      while (fields.hasNext()) {
        var field = fields.next();
        if (!hasProvenance(
            field.getValue(),
            pointer + "/" + field.getKey().replace("~", "~0").replace("/", "~1"),
            index)) return false;
      }
    } else if (value.isArray()) {
      for (int i = 0; i < value.size(); i++) {
        if (!hasProvenance(value.get(i), pointer + "/" + i, index)) return false;
      }
    }
    return true;
  }

  private static final class SizeExceeded extends IOException {}

  /** Count encoded bytes without allocating an unbounded intermediate byte array. */
  private static final class SizeBound extends OutputStream {
    private int count;

    @Override
    public void write(int value) throws IOException {
      if (++count > MAX_MANIFEST_BYTES) throw new SizeExceeded();
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      if (length > MAX_MANIFEST_BYTES - count) throw new SizeExceeded();
      count += length;
    }
  }

  private static JsonSchema load() {
    try {
      JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
      try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
        return factory.getSchema(input);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("unable to load scenario manifest schema", exception);
    }
  }
}
