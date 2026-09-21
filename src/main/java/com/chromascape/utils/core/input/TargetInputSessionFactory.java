package com.chromascape.utils.core.input;

import com.chromascape.utils.core.input.remoteinput.Kinput;
import com.chromascape.utils.core.input.remoteinput.KinputBackend;
import com.chromascape.utils.core.input.remoteinput.KinputTargetInputSession;
import com.chromascape.utils.core.input.remoteinput.RemoteInputArtifactException;
import com.chromascape.utils.core.input.remoteinput.RemoteInputAttachmentCoordinator;
import com.chromascape.utils.core.input.remoteinput.RemoteInputBindingProvider;
import com.chromascape.utils.core.input.remoteinput.RemoteInputDiagnostic;
import com.chromascape.utils.core.input.remoteinput.RemoteInputInterface;
import com.chromascape.utils.core.input.remoteinput.RemoteInputSession;
import com.chromascape.utils.core.input.remoteinput.RemoteInputSessionOptions;
import com.chromascape.utils.core.input.remoteinput.SelectionFencing;
import com.chromascape.utils.core.screen.window.ProcessIdentity;
import com.chromascape.utils.core.screen.window.ProcessManager;
import com.chromascape.utils.core.screen.window.ProcessManagerFactory;
import com.chromascape.utils.core.screen.window.TargetDiscoveryService;
import com.chromascape.utils.core.screen.window.WindowInfo;
import com.chromascape.utils.core.screen.window.WindowSelectionService;
import java.awt.Rectangle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Explicit, side-effect-free input-session factory for the selected target. */
@Component
public final class TargetInputSessionFactory {

  private static final RemoteInputAttachmentCoordinator PROCESS_ATTACHMENTS =
      new RemoteInputAttachmentCoordinator();

  /** Creates a Windows KInput backend without exposing DLL loading to tests. */
  @FunctionalInterface
  public interface KinputBackendFactory {
    /** Creates a backend for the selected process id. */
    KinputBackend create(int pid);
  }

  private final TargetDiscoveryService discovery;
  private final WindowSelectionService selection;
  private final ProcessManagerFactory processManagers;
  private final RemoteInputBindingProvider bindingProvider;
  private final RemoteInputAttachmentCoordinator coordinator;
  private final RemoteInputSessionOptions options;
  private final KinputBackendFactory kinputBackends;

  /** Creates the production factory. */
  @Autowired
  public TargetInputSessionFactory(
      TargetDiscoveryService discovery,
      WindowSelectionService selection,
      ProcessManagerFactory processManagers,
      RemoteInputBindingProvider bindingProvider,
      @Value("${chromascape.remoteinput.attachment-enabled:false}") boolean enabled) {
    this(discovery, selection, processManagers, bindingProvider, optionsFor(enabled), Kinput::new);
  }

  /** Creates a factory with injected options and Windows backend for tests. */
  public TargetInputSessionFactory(
      TargetDiscoveryService discovery,
      WindowSelectionService selection,
      ProcessManagerFactory processManagers,
      RemoteInputBindingProvider bindingProvider,
      RemoteInputSessionOptions options,
      KinputBackendFactory kinputBackends) {
    this.discovery = discovery;
    this.selection = selection;
    this.processManagers = processManagers;
    this.bindingProvider = bindingProvider;
    this.coordinator = PROCESS_ATTACHMENTS;
    this.options = options;
    this.kinputBackends = kinputBackends;
  }

  private static RemoteInputSessionOptions optionsFor(boolean enabled) {
    RemoteInputSessionOptions defaults = RemoteInputSessionOptions.DEFAULT;
    return new RemoteInputSessionOptions(
        enabled,
        defaults.injectTimeout(),
        defaults.pairTimeout(),
        defaults.nativeCallTimeout(),
        defaults.releaseTimeout(),
        defaults.validationInterval(),
        defaults.maxClients());
  }

  /** Opens one fenced session for the explicit current selection. */
  public TargetInputSession openSelectedSession() {
    if (!selection.hasSelection()) {
      throw new InputSessionException(
          InputSessionDiagnostic.NO_SELECTION,
          null,
          selection.getSelectedGeneration(),
          "An explicit target selection is required");
    }
    return switch (ProcessManagerFactory.currentPlatform()) {
      case LINUX -> openLinuxSession();
      case WINDOWS -> openWindowsSession();
      case UNSUPPORTED -> throw new InputSessionException(
          InputSessionDiagnostic.UNSUPPORTED_PLATFORM,
          selection.getSelectedIdentity(),
          selection.getSelectedGeneration(),
          "Input is unsupported on this platform");
    };
  }

  private TargetInputSession openLinuxSession() {
    if (!options.attachmentEnabled()) {
      throw new InputSessionException(
          InputSessionDiagnostic.ATTACHMENT_DISABLED,
          selection.getSelectedIdentity(),
          selection.getSelectedGeneration(),
          "RemoteInput attachment is disabled");
    }
    return RemoteInputSession.open(
        discovery, selection, processManagers, loadBinding(), coordinator, options);
  }

  private RemoteInputInterface loadBinding() {
    try {
      RemoteInputInterface binding = bindingProvider.load();
      if (binding == null) {
        throw new InputSessionException(
            InputSessionDiagnostic.LOAD_FAILED,
            selection.getSelectedIdentity(),
            selection.getSelectedGeneration(),
            "RemoteInput binding provider returned no binding");
      }
      return binding;
    } catch (RemoteInputArtifactException failure) {
      InputSessionDiagnostic diagnostic =
          failure.diagnostic() == RemoteInputDiagnostic.LOAD_FAILED
                  || failure.diagnostic() == RemoteInputDiagnostic.MISSING_SYMBOL
              ? InputSessionDiagnostic.LOAD_FAILED
              : InputSessionDiagnostic.ARTIFACT_UNAVAILABLE;
      throw new InputSessionException(
          diagnostic,
          selection.getSelectedIdentity(),
          selection.getSelectedGeneration(),
          failure.getMessage(),
          failure);
    }
  }

  private TargetInputSession openWindowsSession() {
    WindowInfo window = selection.getSelectedWindow();
    if (window == null || window.getProcessIdentity() == null) {
      throw new InputSessionException(
          InputSessionDiagnostic.IDENTITY_UNAVAILABLE,
          null,
          selection.getSelectedGeneration(),
          "Selected Windows target has no process identity");
    }

    ProcessIdentity identity = window.getProcessIdentity();
    long generation = selection.getSelectedGeneration();
    ProcessManager processManager = processManagers.getProcessManager();
    SelectionFencing.requireValid(selection, processManager, identity, generation);
    RemoteInputAttachmentCoordinator.Lease lease = coordinator.acquire(identity);
    KinputBackend backend = null;
    try {
      Rectangle bounds = window.getBounds();
      int width = bounds == null ? 1 : Math.max(1, bounds.width);
      int height = bounds == null ? 1 : Math.max(1, bounds.height);
      backend = kinputBackends.create(window.getPid());
      SelectionFencing.requireValid(selection, processManager, identity, generation);
      return new KinputTargetInputSession(
          backend, identity, generation, width, height, selection, processManager, lease);
    } catch (RuntimeException failure) {
      boolean destroyed = backend == null;
      if (backend != null) {
        try {
          backend.destroy();
          destroyed = true;
        } catch (RuntimeException cleanupFailure) {
          failure.addSuppressed(cleanupFailure);
        }
      }
      if (destroyed) {
        lease.close();
      }
      throw failure;
    }
  }
}
