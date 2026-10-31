package com.chromascape.web.aiplayer.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CatalogTaskScopeNormalizationTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void retainsExplicitFishingScopeInProfileCatalogSnapshot() throws Exception {
    var raw =
        json.readTree(
            "{\"catalog_task_scope\":[\"FISHING_TRAINING\"],\"tasks\":[{\"task_key\":\"FISHING_TRAINING\"}]}");
    var normalized = CatalogNormalizer.normalize(raw, true);
    assertEquals(raw.path("catalog_task_scope"), normalized.path("catalog_task_scope"));
    assertEquals(1, normalized.path("tasks").size());
  }

  @Test
  void refusesMismatchedDuplicateAndMalformedScopeClaims() throws Exception {
    for (String scope :
        new String[] {
          "[\"MINING_TRAINING\"]",
          "[\"FISHING_TRAINING\",\"FISHING_TRAINING\"]",
          "\"FISHING_TRAINING\""
        }) {
      var raw =
          json.readTree(
              "{\"catalog_task_scope\":"
                  + scope
                  + ",\"tasks\":[{\"task_key\":\"FISHING_TRAINING\"}]}");
      assertThrows(CatalogProblem.class, () -> CatalogNormalizer.normalize(raw, true));
    }
  }

  @Test
  void legacyUnscopedCatalogsKeepTheirContract() throws Exception {
    var normalized =
        CatalogNormalizer.normalize(
            json.readTree("{\"tasks\":[{\"task_key\":\"FISHING_TRAINING\"}]}"), true);
    assertFalse(normalized.has("catalog_task_scope"));
  }
}
