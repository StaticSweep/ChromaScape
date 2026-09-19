package com.chromascape.utils.core.input;

import com.chromascape.utils.core.input.remoteinput.RemoteInputArtifactException;
import com.chromascape.utils.core.input.remoteinput.RemoteInputBindingProvider;
import com.chromascape.utils.core.input.remoteinput.RemoteInputDiagnostic;
import com.chromascape.utils.core.input.remoteinput.RemoteInputProperties;
import com.chromascape.utils.core.screen.window.ProcessManagerFactory;
import jakarta.annotation.PostConstruct;
import java.util.Objects;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Fail-fast startup check for the opt-in Linux RemoteInput attachment.
 *
 * <p>Attachment stays <b>disabled by default</b>: {@code
 * chromascape.remoteinput.attachment-enabled} (which the {@code
 * CHROMASCAPE_REMOTEINPUT_ATTACHMENT_ENABLED} environment variable sets through Spring's relaxed
 * binding onto {@link RemoteInputProperties}) defaults to {@code false}, and this validator never
 * touches the native binding provider in that case. Only an explicit {@code true} enables it, and
 * the lifecycle wrapper deliberately never overwrites an operator-provided value.
 *
 * <p>When attachment is explicitly enabled on Linux, the audited SC-1662 native artifact is
 * resolved, verified and loaded <em>at startup</em>. A library that cannot be loaded must abort
 * startup with a clear error instead of deferring the failure to the first session: an operator who
 * asked for attachment should never reach a half-working discovery-only state. The error preserves
 * the stable {@link RemoteInputArtifactException} diagnostic ({@code LOAD_FAILED}, {@code
 * MISSING_SYMBOL}, ...) so automation can branch on the category. A provider that returns {@code
 * null} instead of throwing is reported as {@code LOAD_FAILED} for the same reason.
 *
 * <p>On Windows the RemoteInput artifact does not apply (input goes through KInput), so an explicit
 * {@code true} is reported as ignored rather than fatal. Loading happens at most once; the provider
 * caches the binding, so session opens reuse this load.
 */
@Component
public final class RemoteInputStartupValidator {

  private static final Logger logger = LogManager.getLogger(RemoteInputStartupValidator.class);

  private final RemoteInputBindingProvider bindingProvider;
  private final boolean enabled;
  private final Supplier<ProcessManagerFactory.Platform> platform;

  /** Creates the production validator from the typed configuration (disabled by default). */
  @Autowired
  public RemoteInputStartupValidator(
      RemoteInputBindingProvider bindingProvider, RemoteInputProperties properties) {
    this(bindingProvider, properties.isAttachmentEnabled(), ProcessManagerFactory::currentPlatform);
  }

  /** Creates a validator over an injected platform source for tests. */
  RemoteInputStartupValidator(
      RemoteInputBindingProvider bindingProvider,
      boolean enabled,
      Supplier<ProcessManagerFactory.Platform> platform) {
    this.bindingProvider = Objects.requireNonNull(bindingProvider, "bindingProvider");
    this.enabled = enabled;
    this.platform = Objects.requireNonNull(platform, "platform");
  }

  /**
   * Verifies attachment readiness at startup when, and only when, attachment was explicitly
   * enabled.
   *
   * @throws IllegalStateException when attachment is enabled on Linux but the native library cannot
   *     be resolved, verified, or loaded
   */
  @PostConstruct
  public void verifyAttachmentReadiness() {
    if (!enabled) {
      logger.info("RemoteInput attachment is disabled (default); discovery remains read-only");
      return;
    }
    ProcessManagerFactory.Platform current = platform.get();
    if (current != ProcessManagerFactory.Platform.LINUX) {
      logger.warn(
          "RemoteInput attachment is enabled but the platform is {}; RemoteInput applies to "
              + "Linux only, so the flag is ignored on this host",
          current);
      return;
    }
    logger.info("RemoteInput attachment is enabled; verifying the native artifact at startup");
    try {
      if (bindingProvider.load() == null) {
        throw new IllegalStateException(
            "RemoteInput attachment is enabled but the native library cannot be loaded ("
                + RemoteInputDiagnostic.LOAD_FAILED
                + "): the binding provider returned no binding");
      }
    } catch (RemoteInputArtifactException failure) {
      throw new IllegalStateException(
          "RemoteInput attachment is enabled but the native library cannot be loaded "
              + "("
              + failure.diagnostic()
              + "): "
              + failure.getMessage(),
          failure);
    }
    logger.info("RemoteInput native artifact resolved, verified and loaded at startup");
  }
}
