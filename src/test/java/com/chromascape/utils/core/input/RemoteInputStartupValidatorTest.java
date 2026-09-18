package com.chromascape.utils.core.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.chromascape.utils.core.input.remoteinput.RemoteInputArtifactException;
import com.chromascape.utils.core.input.remoteinput.RemoteInputBindingProvider;
import com.chromascape.utils.core.input.remoteinput.RemoteInputDiagnostic;
import com.chromascape.utils.core.screen.window.ProcessManagerFactory;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RemoteInputStartupValidatorTest {

  @Test
  void defaultRemainsDisabledAndNeverTouchesTheBindingProvider() {
    RemoteInputBindingProvider provider = mock(RemoteInputBindingProvider.class);

    RemoteInputStartupValidator validator =
        new RemoteInputStartupValidator(
            provider, false, () -> ProcessManagerFactory.Platform.LINUX);

    validator.verifyAttachmentReadiness();

    verifyNoInteractions(provider);
  }

  @Test
  void explicitOverrideLoadsTheBindingAtStartup() {
    RemoteInputBindingProvider provider = mock(RemoteInputBindingProvider.class);

    RemoteInputStartupValidator validator =
        new RemoteInputStartupValidator(provider, true, () -> ProcessManagerFactory.Platform.LINUX);

    validator.verifyAttachmentReadiness();

    verify(provider).load();
  }

  @Test
  void enabledOnLinuxWithUnloadableLibraryFailsStartupClearly() {
    RemoteInputBindingProvider provider = mock(RemoteInputBindingProvider.class);
    RemoteInputArtifactException failure =
        new RemoteInputArtifactException(
            RemoteInputDiagnostic.LOAD_FAILED,
            (Path) null,
            "linux-x86_64 ELF 64-bit LSB shared object",
            "libExample.so: cannot open shared object file");
    when(provider.load()).thenThrow(failure);

    RemoteInputStartupValidator validator =
        new RemoteInputStartupValidator(provider, true, () -> ProcessManagerFactory.Platform.LINUX);

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> validator.verifyAttachmentReadiness());

    assertTrue(
        thrown.getMessage().contains("RemoteInput attachment is enabled"),
        "message must name the enabled attachment: " + thrown.getMessage());
    assertTrue(
        thrown.getMessage().contains("LOAD_FAILED"),
        "message must carry the stable diagnostic: " + thrown.getMessage());
    assertEquals(failure, thrown.getCause());
  }

  @Test
  void explicitOverrideOnNonLinuxPlatformIsReportedAndIgnored() {
    RemoteInputBindingProvider provider = mock(RemoteInputBindingProvider.class);

    RemoteInputStartupValidator validator =
        new RemoteInputStartupValidator(
            provider, true, () -> ProcessManagerFactory.Platform.WINDOWS);

    validator.verifyAttachmentReadiness();

    verifyNoInteractions(provider);
  }
}
