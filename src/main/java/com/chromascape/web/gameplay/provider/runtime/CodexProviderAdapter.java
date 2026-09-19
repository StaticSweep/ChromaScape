package com.chromascape.web.gameplay.provider.runtime;

import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Ephemeral Codex adapter with agent shell/apps/browser/computer features disabled. */
@Component
public final class CodexProviderAdapter extends AbstractProcessProviderAdapter {
  private static final List<String> DISABLED_FEATURES =
      List.of(
          "shell_tool",
          "apps",
          "browser_use",
          "browser_use_external",
          "computer_use",
          "image_generation",
          "multi_agent",
          "hooks",
          "skill_search");

  public CodexProviderAdapter(
      ProviderRuntimeProperties properties, SafeProviderProcessRunner runner) {
    super(properties.getCodex(), runner);
  }

  @Override
  public Provider provider() {
    return Provider.CODEX;
  }

  @Override
  public ProviderTurnResult startTurn(ProviderInvocation invocation) {
    List<String> command = new ArrayList<>(configuration.getCommand());
    command.addAll(
        List.of(
            "exec",
            "--ephemeral",
            "--ignore-user-config",
            "--ignore-rules",
            "--sandbox",
            "read-only",
            "--skip-git-repo-check",
            "--color",
            "never",
            "--model",
            invocation.modelId(),
            "--output-schema",
            "${WORK_DIR}/output-schema.json"));
    for (String feature : DISABLED_FEATURES) {
      command.add("--disable");
      command.add(feature);
    }
    command.add("-");
    return run(
        invocation,
        command,
        Map.of("output-schema.json", ProviderProposalSchema.JSON),
        output -> output);
  }
}
