package com.chromascape.utils.core.runtime.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProfileManagerTest {
  @Test
  void hostNativeProfileAlwaysResolvesBelowTheProvidedHome() {
    Path home = Path.of("root", "operator-home");

    Path result = ProfileManager.resolveHostNativeProfileDir(home.toString());

    assertThat(result).isEqualTo(home.resolve(".runelite").resolve("profiles2"));
  }
}
