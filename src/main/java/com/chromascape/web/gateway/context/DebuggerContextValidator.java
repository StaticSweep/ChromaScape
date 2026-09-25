package com.chromascape.web.gateway.context;

import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Maps current-slice identity into the stricter debugger correlation contract. */
@Component
public class DebuggerContextValidator {

  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}");

  public DebuggerContext map(
      CurrentSliceIdentity identity,
      String accountId,
      String debugSessionId,
      String patchIterationId,
      DebuggerContext.Mode mode,
      Long selectedTick,
      long contextRevision) {
    Objects.requireNonNull(identity, "current-slice identity is required");
    identity.validate();
    CurrentSliceIdentity.Run run = identity.run();
    CurrentSliceIdentity.Client client = identity.client();
    CurrentSliceIdentity.Capture capture = identity.capture();
    if (run == null) {
      throw new IllegalArgumentException("current-slice run identity is required");
    }
    String buildId = client.clientBuildId();
    String agentId =
        hasText(run.executorInstanceId()) ? run.executorInstanceId() : client.clientId();
    String recordingId =
        mode == DebuggerContext.Mode.live || capture == null ? null : capture.recordingId();
    return validate(
        new DebuggerContext(
            DebuggerContext.SCHEMA_VERSION,
            accountId,
            client.clientId(),
            agentId,
            run.runId(),
            debugSessionId,
            recordingId,
            patchIterationId,
            buildId,
            mode,
            selectedTick,
            contextRevision));
  }

  public DebuggerContext validate(DebuggerContext context) {
    Objects.requireNonNull(context, "debugger context is required");
    if (!DebuggerContext.SCHEMA_VERSION.equals(context.schemaVersion())) {
      throw new IllegalArgumentException("schemaVersion must be debugger-context/v1");
    }
    identifier(context.accountId(), "accountId");
    identifier(context.clientId(), "clientId");
    identifier(context.agentId(), "agentId");
    identifier(context.runId(), "runId");
    identifier(context.debugSessionId(), "debugSessionId");
    identifier(context.patchIterationId(), "patchIterationId");
    identifier(context.buildId(), "buildId");
    if (context.contextRevision() < 1) {
      throw new IllegalArgumentException("contextRevision must be positive");
    }
    if (context.selectedTick() != null && context.selectedTick() < 0) {
      throw new IllegalArgumentException("selectedTick must be non-negative");
    }
    if (context.mode() == null) {
      throw new IllegalArgumentException("mode is required");
    }
    if (context.mode() == DebuggerContext.Mode.live && context.recordingId() != null) {
      throw new IllegalArgumentException("live context cannot carry recordingId");
    }
    if (context.mode() != DebuggerContext.Mode.live) {
      identifier(context.recordingId(), "recordingId");
    }
    if (context.mode() == DebuggerContext.Mode.replay && context.selectedTick() == null) {
      throw new IllegalArgumentException("replay context requires selectedTick");
    }
    return context;
  }

  public void requireSameContext(DebuggerContext expected, DebuggerContext received) {
    validate(expected);
    validate(received);
    if (!expected.equals(received)) {
      throw new IllegalArgumentException("debugger context mismatch");
    }
  }

  private static void identifier(String value, String field) {
    if (value == null || !IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " is missing or invalid");
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
