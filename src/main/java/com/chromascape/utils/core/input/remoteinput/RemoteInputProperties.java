package com.chromascape.utils.core.input.remoteinput;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Typed configuration for the Linux RemoteInput attachment under {@code chromascape.remoteinput}.
 *
 * <p>Every property maps onto an environment variable through Spring's relaxed binding and accepts
 * Spring duration syntax such as {@code 30s} or {@code 250ms}:
 *
 * <table>
 * <caption>Bounded attachment properties</caption>
 * <tr><th>Property / environment variable</th><th>Default</th><th>Allowed</th></tr>
 * <tr><td>{@code attachment-enabled} / {@code CHROMASCAPE_REMOTEINPUT_ATTACHMENT_ENABLED}</td>
 *     <td>{@code false}</td><td></td></tr>
 * <tr><td>{@code inject-timeout} / {@code CHROMASCAPE_REMOTEINPUT_INJECT_TIMEOUT}</td>
 *     <td>30 s</td><td>1–60 s</td></tr>
 * <tr><td>{@code pair-timeout} / {@code CHROMASCAPE_REMOTEINPUT_PAIR_TIMEOUT}</td>
 *     <td>10 s</td><td>1–30 s</td></tr>
 * <tr><td>{@code native-call-timeout} / {@code CHROMASCAPE_REMOTEINPUT_NATIVE_CALL_TIMEOUT}</td>
 *     <td>2 s</td><td>250 ms–10 s</td></tr>
 * <tr><td>{@code release-timeout} / {@code CHROMASCAPE_REMOTEINPUT_RELEASE_TIMEOUT}</td>
 *     <td>3 s</td><td>250 ms–10 s</td></tr>
 * <tr><td>{@code validation-interval} / {@code CHROMASCAPE_REMOTEINPUT_VALIDATION_INTERVAL}</td>
 *     <td>250 ms</td><td>25 ms–1 s</td></tr>
 * <tr><td>{@code max-clients} / {@code CHROMASCAPE_REMOTEINPUT_MAX_CLIENTS}</td>
 *     <td>4096</td><td>1–4096</td></tr>
 * </table>
 *
 * <p>Out-of-range values fail application startup: each setter rejects its own range while the bean
 * is bound, and {@link #validate()} rejects a polling interval longer than the injection deadline.
 * Unknown keys under the prefix (for example the pre-existing {@code application-root} and {@code
 * library} artifact settings consumed elsewhere) are left untouched. {@link #toSessionOptions()}
 * produces the single validated {@link RemoteInputSessionOptions} the session factory uses.
 */
@Configuration
@ConfigurationProperties(prefix = "chromascape.remoteinput")
public class RemoteInputProperties {

  /** Shortest total attachment deadline an operator may configure. */
  public static final Duration MIN_INJECT_TIMEOUT = Duration.ofSeconds(1);

  /** Shortest pairing deadline an operator may configure. */
  public static final Duration MIN_PAIR_TIMEOUT = Duration.ofSeconds(1);

  /** Shortest single native call deadline an operator may configure. */
  public static final Duration MIN_NATIVE_CALL_TIMEOUT = Duration.ofMillis(250);

  /** Shortest release deadline an operator may configure. */
  public static final Duration MIN_RELEASE_TIMEOUT = Duration.ofMillis(250);

  /** Shortest client-poll interval an operator may configure. */
  public static final Duration MIN_VALIDATION_INTERVAL = Duration.ofMillis(25);

  private boolean attachmentEnabled = RemoteInputSessionOptions.DEFAULT.attachmentEnabled();
  private Duration injectTimeout = RemoteInputSessionOptions.DEFAULT.injectTimeout();
  private Duration pairTimeout = RemoteInputSessionOptions.DEFAULT.pairTimeout();
  private Duration nativeCallTimeout = RemoteInputSessionOptions.DEFAULT.nativeCallTimeout();
  private Duration releaseTimeout = RemoteInputSessionOptions.DEFAULT.releaseTimeout();
  private Duration validationInterval = RemoteInputSessionOptions.DEFAULT.validationInterval();
  private int maxClients = RemoteInputSessionOptions.DEFAULT.maxClients();

  /** Whether Linux RemoteInput attachment is enabled; defaults to {@code false}. */
  public boolean isAttachmentEnabled() {
    return attachmentEnabled;
  }

  public void setAttachmentEnabled(boolean attachmentEnabled) {
    this.attachmentEnabled = attachmentEnabled;
  }

  /** Total attachment deadline shared by the injection call and every client poll. */
  public Duration getInjectTimeout() {
    return injectTimeout;
  }

  public void setInjectTimeout(Duration injectTimeout) {
    this.injectTimeout =
        requireRange(
            injectTimeout,
            MIN_INJECT_TIMEOUT,
            RemoteInputSessionOptions.MAX_INJECT_TIMEOUT,
            "inject-timeout");
  }

  /** Deadline for the native pairing call. */
  public Duration getPairTimeout() {
    return pairTimeout;
  }

  public void setPairTimeout(Duration pairTimeout) {
    this.pairTimeout =
        requireRange(
            pairTimeout,
            MIN_PAIR_TIMEOUT,
            RemoteInputSessionOptions.MAX_PAIR_TIMEOUT,
            "pair-timeout");
  }

  /** Deadline for a single ordinary native call. */
  public Duration getNativeCallTimeout() {
    return nativeCallTimeout;
  }

  public void setNativeCallTimeout(Duration nativeCallTimeout) {
    this.nativeCallTimeout =
        requireRange(
            nativeCallTimeout,
            MIN_NATIVE_CALL_TIMEOUT,
            RemoteInputSessionOptions.MAX_NATIVE_CALL_TIMEOUT,
            "native-call-timeout");
  }

  /** Deadline for releasing held input and the native target. */
  public Duration getReleaseTimeout() {
    return releaseTimeout;
  }

  public void setReleaseTimeout(Duration releaseTimeout) {
    this.releaseTimeout =
        requireRange(
            releaseTimeout,
            MIN_RELEASE_TIMEOUT,
            RemoteInputSessionOptions.MAX_RELEASE_TIMEOUT,
            "release-timeout");
  }

  /** Interval between client polls while waiting for the injected client to register. */
  public Duration getValidationInterval() {
    return validationInterval;
  }

  public void setValidationInterval(Duration validationInterval) {
    this.validationInterval =
        requireRange(
            validationInterval,
            MIN_VALIDATION_INTERVAL,
            RemoteInputSessionOptions.MAX_VALIDATION_INTERVAL,
            "validation-interval");
  }

  /** Largest native client count treated as plausible during enumeration. */
  public int getMaxClients() {
    return maxClients;
  }

  /** Sets the plausible client-count bound; rejects values outside 1–4096. */
  public void setMaxClients(int maxClients) {
    if (maxClients < 1 || maxClients > RemoteInputSessionOptions.MAX_CLIENTS) {
      throw new IllegalArgumentException(
          "chromascape.remoteinput.max-clients must be between 1 and "
              + RemoteInputSessionOptions.MAX_CLIENTS
              + " but was "
              + maxClients);
    }
    this.maxClients = maxClients;
  }

  /**
   * Rejects cross-field combinations that individual setters cannot see, so an invalid
   * configuration aborts startup rather than the first attachment.
   */
  @PostConstruct
  public void validate() {
    toSessionOptions();
  }

  /** Builds the single validated options instance used for every session. */
  public RemoteInputSessionOptions toSessionOptions() {
    try {
      return new RemoteInputSessionOptions(
          attachmentEnabled,
          injectTimeout,
          pairTimeout,
          nativeCallTimeout,
          releaseTimeout,
          validationInterval,
          maxClients);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(
          "chromascape.remoteinput configuration is invalid: " + invalid.getMessage(), invalid);
    }
  }

  private static Duration requireRange(Duration value, Duration min, Duration max, String name) {
    if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
      throw new IllegalArgumentException(
          "chromascape.remoteinput."
              + name
              + " must be between "
              + min
              + " and "
              + max
              + " but was "
              + value);
    }
    return value;
  }
}
