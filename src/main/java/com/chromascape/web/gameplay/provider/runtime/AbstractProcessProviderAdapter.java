package com.chromascape.web.gameplay.provider.runtime;

import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.FailureCode;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.ProviderReadiness;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.ProviderUsage;
import com.chromascape.web.gameplay.provider.runtime.SafeProviderProcessRunner.ProcessResult;
import java.util.List;
import java.util.Map;

abstract class AbstractProcessProviderAdapter implements ProviderAdapter {
  protected final ProviderRuntimeProperties.ProviderProcess configuration;
  protected final SafeProviderProcessRunner runner;

  AbstractProcessProviderAdapter(
      ProviderRuntimeProperties.ProviderProcess configuration, SafeProviderProcessRunner runner) {
    this.configuration = configuration;
    this.runner = runner;
  }

  @Override
  public ProviderReadiness readiness() {
    SafeProviderProcessRunner.ProbeResult result =
        runner.probe(configuration.getCommand(), configuration.getCredentialEnvironmentNames());
    return new ProviderReadiness(
        result.available(),
        configuration.getCommand().get(0),
        result.version(),
        result.failureCode());
  }

  protected ProviderTurnResult run(
      ProviderInvocation invocation,
      List<String> arguments,
      Map<String, String> stagedFiles,
      OutputExtractor extractor) {
    ProcessResult result =
        runner.run(
            invocation.runtimeTurnId(),
            arguments,
            configuration.getCredentialEnvironmentNames(),
            invocation.prompt(),
            invocation.deadline(),
            stagedFiles);
    String providerSessionRef =
        provider().name().toLowerCase() + ":" + invocation.runtimeSessionId();
    String providerTurnRef = provider().name().toLowerCase() + ":" + invocation.runtimeTurnId();
    String raw =
        result.terminal() && result.failureCode() == FailureCode.NONE
            ? extractor.extract(result.stdout())
            : null;
    ProviderUsage usage = new ProviderUsage(null, null, 0, result.elapsedMillis(), false);
    return new ProviderTurnResult(
        result.submitted(),
        result.terminal(),
        providerSessionRef,
        providerTurnRef,
        raw,
        result.stdout() + "\n[stderr]\n" + result.stderr(),
        usage,
        result.failureCode(),
        List.of("process-exit:" + result.exitCode()));
  }

  @Override
  public ReconciliationResult reconcile(String providerSessionRef, String providerTurnRef) {
    return new ReconciliationResult(false, null, null, FailureCode.NEEDS_RECONCILIATION);
  }

  @Override
  public CancelResult cancel(String providerSessionRef, String providerTurnRef, String reason) {
    String prefix = provider().name().toLowerCase() + ":";
    boolean requested =
        providerTurnRef != null
            && providerTurnRef.startsWith(prefix)
            && runner.cancel(providerTurnRef.substring(prefix.length()));
    return new CancelResult(
        false, requested ? FailureCode.CANCELLED : FailureCode.NEEDS_RECONCILIATION);
  }

  @FunctionalInterface
  protected interface OutputExtractor {
    String extract(String output);
  }
}
