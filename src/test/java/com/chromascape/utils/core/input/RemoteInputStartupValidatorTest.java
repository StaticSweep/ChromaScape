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
import com.chromascape.utils.core.input.remoteinput.RemoteInputInterface;
import com.chromascape.utils.core.input.remoteinput.RemoteInputProperties;
import com.chromascape.utils.core.screen.window.ProcessManagerFactory;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

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
    when(provider.load()).thenReturn(mock(RemoteInputInterface.class));

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
  void enabledOnLinuxWithMissingArtifactFailsStartupWithTheStableDiagnostic() {
    RemoteInputBindingProvider provider = mock(RemoteInputBindingProvider.class);
    RemoteInputArtifactException missing =
        new RemoteInputArtifactException(
            RemoteInputDiagnostic.MISSING_LIBRARY,
            (Path) null,
            "linux-x86_64 ELF 64-bit LSB shared object",
            "libRemoteInput.so was not found under the application root");
    when(provider.load()).thenThrow(missing);

    RemoteInputStartupValidator validator =
        new RemoteInputStartupValidator(provider, true, () -> ProcessManagerFactory.Platform.LINUX);

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> validator.verifyAttachmentReadiness());

    assertTrue(thrown.getMessage().contains("MISSING_LIBRARY"), thrown.getMessage());
    assertEquals(missing, thrown.getCause());
  }

  @Test
  void enabledOnLinuxWithNullBindingFailsStartupWithTheStableDiagnostic() {
    RemoteInputBindingProvider provider = mock(RemoteInputBindingProvider.class);
    when(provider.load()).thenReturn(null);

    RemoteInputStartupValidator validator =
        new RemoteInputStartupValidator(provider, true, () -> ProcessManagerFactory.Platform.LINUX);

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> validator.verifyAttachmentReadiness());

    assertTrue(
        thrown.getMessage().contains(RemoteInputDiagnostic.LOAD_FAILED.name()),
        "null binding must be reported as LOAD_FAILED: " + thrown.getMessage());
    assertTrue(thrown.getMessage().contains("returned no binding"));
  }

  @Test
  void enabledOnLinuxWithAValidBindingPassesAndLoadsExactlyOnce() {
    RemoteInputBindingProvider provider = mock(RemoteInputBindingProvider.class);
    when(provider.load()).thenReturn(mock(RemoteInputInterface.class));

    RemoteInputStartupValidator validator =
        new RemoteInputStartupValidator(provider, true, () -> ProcessManagerFactory.Platform.LINUX);

    validator.verifyAttachmentReadiness();

    verify(provider).load();
  }

  @Test
  void disabledOnLinuxNeverLoadsEvenWhenAValidBindingExists() {
    RemoteInputBindingProvider provider = mock(RemoteInputBindingProvider.class);
    when(provider.load()).thenReturn(mock(RemoteInputInterface.class));

    new RemoteInputStartupValidator(provider, false, () -> ProcessManagerFactory.Platform.LINUX)
        .verifyAttachmentReadiness();

    verifyNoInteractions(provider);
  }

  @Test
  void unsupportedPlatformWithExplicitOverrideIsIgnored() {
    RemoteInputBindingProvider provider = mock(RemoteInputBindingProvider.class);

    new RemoteInputStartupValidator(
            provider, true, () -> ProcessManagerFactory.Platform.UNSUPPORTED)
        .verifyAttachmentReadiness();

    verifyNoInteractions(provider);
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void productionConstructorReadsTheTypedPropertiesFlag() {
    RemoteInputBindingProvider provider = mock(RemoteInputBindingProvider.class);
    when(provider.load()).thenReturn(mock(RemoteInputInterface.class));
    RemoteInputProperties disabled = new RemoteInputProperties();
    RemoteInputProperties enabled = new RemoteInputProperties();
    enabled.setAttachmentEnabled(true);

    new RemoteInputStartupValidator(provider, disabled).verifyAttachmentReadiness();
    verifyNoInteractions(provider);

    new RemoteInputStartupValidator(provider, enabled).verifyAttachmentReadiness();
    verify(provider).load();
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
