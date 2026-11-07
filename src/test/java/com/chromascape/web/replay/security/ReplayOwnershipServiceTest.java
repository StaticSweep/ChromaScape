package com.chromascape.web.replay.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.replay.curation.entity.CurationRole;
import com.chromascape.web.replay.curation.entity.CurationState;
import com.chromascape.web.replay.curation.entity.RecordingCuration;
import com.chromascape.web.replay.curation.repository.RecordingCurationRepository;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.manifest.ScenarioManifestV2Compiler;
import com.chromascape.web.replay.manifest.entity.ScenarioManifest;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReplayOwnershipServiceTest {
  private final ObjectMapper json = new ObjectMapper();
  private final CanonicalProjectionService canonical = new CanonicalProjectionService();
  private final RecordingCurationRepository curations = mock(RecordingCurationRepository.class);
  private final ReplayOwnershipService ownership = new ReplayOwnershipService(curations);

  private RecordingCuration row(String owner) {
    var row = new RecordingCuration();
    row.setId(UUID.randomUUID());
    row.setRecordingId("recording-private");
    row.setDebugSessionId("session-private");
    row.setCreatedBy(owner);
    row.setVersion(3);
    row.setState(CurationState.PUBLISHED);
    row.setRole(CurationRole.HELD_OUT);
    row.setContentJson(json.createObjectNode().put("private-fixture", "test-only"));
    row.setContentChecksum(checksum((ObjectNode) row.getContentJson()));
    when(curations.findById(row.getId())).thenReturn(Optional.of(row));
    return row;
  }

  private String checksum(ObjectNode value) {
    return canonical.sha256Hex(canonical.canonicalJsonBytes(value));
  }

  private ScenarioManifest manifest(RecordingCuration conversion, RecordingCuration source) {
    ObjectNode selected = json.createObjectNode();
    selected
        .put("id", source.getId().toString())
        .put("version", source.getVersion())
        .put("role", source.getRole().name())
        .put("checksum", source.getContentChecksum())
        .put("canonicalContentChecksum", checksum((ObjectNode) source.getContentJson()));
    selected.set("contentSnapshot", source.getContentJson().deepCopy());
    var content = json.createObjectNode().put("schema", "recording-conversion/v1");
    content.set("sourceCuration", selected);
    conversion.setContentJson(content);
    conversion.setContentChecksum(checksum(content));
    var manifest = new ScenarioManifest();
    manifest.setCurationId(conversion.getId());
    manifest.setRecordingId(conversion.getRecordingId());
    manifest.setDebugSessionId(conversion.getDebugSessionId());
    ObjectNode document = json.createObjectNode();
    document.putObject("curation").set("sourceCuration", selected.deepCopy());
    manifest.setManifestJson(document);
    return manifest;
  }

  @Test
  void conversionManifestRetainsExactlyThePublishedSourceOwnerWithoutChangingTheConverterRow() {
    var source = row("owner");
    var conversion = row(ScenarioManifestV2Compiler.CONVERSION_CREATED_BY);
    var manifest = manifest(conversion, source);
    assertThat(ownership.requireOwnedManifest(manifest, "owner")).isSameAs(manifest);
    assertThat(conversion.getCreatedBy())
        .isEqualTo(ScenarioManifestV2Compiler.CONVERSION_CREATED_BY);
    assertThat(conversion.getRole()).isEqualTo(CurationRole.HELD_OUT);
    refuse(manifest, "other");
    refuse(manifest, null);
    refuse(manifest, "");
    refuse(manifest, ScenarioManifestV2Compiler.CONVERSION_CREATED_BY);
  }

  @Test
  void missingForeignUnpublishedOrChangedSourceCannotGrantManifestAccess() {
    for (int variant = 0; variant < 9; variant++) {
      var source = row("owner");
      var conversion = row(ScenarioManifestV2Compiler.CONVERSION_CREATED_BY);
      var manifest = manifest(conversion, source);
      switch (variant) {
        case 0 -> when(curations.findById(source.getId())).thenReturn(Optional.empty());
        case 1 -> source.setCreatedBy("other");
        case 2 -> source.setState(CurationState.DRAFT);
        case 3 -> source.setRole(CurationRole.TRAIN);
        case 4 -> source.setRecordingId("another-recording");
        case 5 -> source.setDebugSessionId("another-session");
        case 6 -> ((ObjectNode) source.getContentJson()).put("changed", true);
        case 7 -> ((ObjectNode) manifest.getManifestJson().path("curation").path("sourceCuration"))
            .put("id", UUID.randomUUID().toString());
        default -> ((ObjectNode) conversion.getContentJson()).put("changed", true);
      }
      refuse(manifest, "owner");
    }
  }

  @Test
  void missingOrMalformedSourcePinNeverGrantsConverterIdentityAccess() {
    for (int variant = 0; variant < 3; variant++) {
      var source = row("owner");
      var conversion = row(ScenarioManifestV2Compiler.CONVERSION_CREATED_BY);
      var manifest = manifest(conversion, source);
      var selected = (ObjectNode) conversion.getContentJson().path("sourceCuration");
      if (variant == 0) ((ObjectNode) conversion.getContentJson()).remove("sourceCuration");
      else if (variant == 1) selected.put("id", "not-a-uuid");
      else selected.put("canonicalContentChecksum", "sha256:" + "0".repeat(64));
      conversion.setContentChecksum(checksum((ObjectNode) conversion.getContentJson()));
      ((ObjectNode) manifest.getManifestJson().path("curation"))
          .set("sourceCuration", conversion.getContentJson().path("sourceCuration").deepCopy());
      refuse(manifest, "owner");
    }
  }

  @Test
  void originalOperatorManifestOwnershipIsUnchanged() {
    var source = row("owner");
    var manifest = new ScenarioManifest();
    manifest.setCurationId(source.getId());
    assertThat(ownership.requireOwnedManifest(manifest, "owner")).isSameAs(manifest);
    refuse(manifest, "other");
    refuse(manifest, null);
  }

  private void refuse(ScenarioManifest manifest, String owner) {
    var rejected =
        assertThrows(ReplayException.class, () -> ownership.requireOwnedManifest(manifest, owner));
    assertThat(rejected.getHttpStatus().value()).isEqualTo(404);
    assertThat(rejected.getMessage()).isEqualTo("Manifest not found");
  }
}
