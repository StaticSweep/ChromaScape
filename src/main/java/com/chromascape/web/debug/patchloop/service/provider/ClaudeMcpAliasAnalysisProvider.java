package com.chromascape.web.debug.patchloop.service.provider;

import com.chromascape.web.debug.patchloop.service.provider.mcp.McpConfigProperties;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpTransportClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Legacy alias provider bean for {@code analysis.provider=claude-mcp}.
 *
 * <p>Delegates all behavior to {@link ClaudeMcpAnalysisProvider} while preserving the legacy
 * provider name for backward compatibility.
 */
@Service
@ConditionalOnProperty(name = "analysis.provider", havingValue = "claude-mcp")
public class ClaudeMcpAliasAnalysisProvider extends ClaudeMcpAnalysisProvider {

  public ClaudeMcpAliasAnalysisProvider(McpTransportClient transport, McpConfigProperties config) {
    super(transport, config, "claude-mcp");
  }
}
