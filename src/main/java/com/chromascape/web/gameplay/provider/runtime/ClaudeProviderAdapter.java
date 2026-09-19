package com.chromascape.web.gameplay.provider.runtime;

import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Tool-free noninteractive Claude process adapter. */
@Component
public final class ClaudeProviderAdapter extends AbstractProcessProviderAdapter {
  public ClaudeProviderAdapter(
      ProviderRuntimeProperties properties, SafeProviderProcessRunner runner) {
    super(properties.getClaude(), runner);
  }

  @Override
  public Provider provider() {
    return Provider.CLAUDE;
  }

  @Override
  public ProviderTurnResult startTurn(ProviderInvocation invocation) {
    List<String> command = new ArrayList<>(configuration.getCommand());
    command.addAll(
        List.of(
            "--print",
            "--safe-mode",
            "--restricted",
            "--tools",
            "",
            "--strict-mcp-config",
            "--mcp-config",
            "{\"mcpServers\":{}}",
            "--disable-slash-commands",
            "--no-session-persistence",
            "--output-format",
            "json",
            "--json-schema",
            ProviderProposalSchema.JSON,
            "--model",
            invocation.modelId()));
    return run(invocation, command, Map.of(), output -> output);
  }
}
