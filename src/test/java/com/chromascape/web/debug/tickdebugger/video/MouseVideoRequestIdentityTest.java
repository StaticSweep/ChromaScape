package com.chromascape.web.debug.tickdebugger.video;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class MouseVideoRequestIdentityTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private MouseVideoImportMetadata metadata(String origin) {
    return new MouseVideoImportMetadata(
        "req", origin, "fishing", "fishing", "skilling", "catherby");
  }

  @Test
  void identicalContentAndMetadataProduceTheSameIdentity() {
    String first =
        MouseVideoRequestIdentity.forUpload(
            mapper, "owner-1", metadata("HUMAN"), "sha256:abc", 100L);
    String second =
        MouseVideoRequestIdentity.forUpload(
            mapper, "owner-1", metadata("HUMAN"), "sha256:abc", 100L);
    assertThat(first).isEqualTo(second);
  }

  @Test
  void differentBytesConflictForTheSameRequest() {
    String first =
        MouseVideoRequestIdentity.forUpload(
            mapper, "owner-1", metadata("HUMAN"), "sha256:abc", 100L);
    String different =
        MouseVideoRequestIdentity.forUpload(
            mapper, "owner-1", metadata("HUMAN"), "sha256:def", 100L);
    String differentLength =
        MouseVideoRequestIdentity.forUpload(
            mapper, "owner-1", metadata("HUMAN"), "sha256:abc", 101L);
    assertThat(first).isNotEqualTo(different);
    assertThat(first).isNotEqualTo(differentLength);
  }

  @Test
  void differentMetadataOrOwnerConflicts() {
    String base =
        MouseVideoRequestIdentity.forUpload(
            mapper, "owner-1", metadata("HUMAN"), "sha256:abc", 100L);
    assertThat(base)
        .isNotEqualTo(
            MouseVideoRequestIdentity.forUpload(
                mapper, "owner-2", metadata("HUMAN"), "sha256:abc", 100L));
    assertThat(base)
        .isNotEqualTo(
            MouseVideoRequestIdentity.forUpload(
                mapper, "owner-1", metadata("GENERATED"), "sha256:abc", 100L));
  }

  @Test
  void youtubeIdentityBindsTheCanonicalProviderId() {
    String a =
        MouseVideoRequestIdentity.forYouTube(mapper, "owner-1", metadata("HUMAN"), "dQw4w9WgXcQ");
    String b =
        MouseVideoRequestIdentity.forYouTube(mapper, "owner-1", metadata("HUMAN"), "AAAAAAAAAAA");
    assertThat(a).isNotEqualTo(b);
  }
}
