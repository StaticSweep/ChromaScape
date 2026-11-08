package com.chromascape.web.gameplay.provider.runtime;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Default-off configuration for the isolated provider decision processes. */
@Component
@ConfigurationProperties(prefix = "gameplay.provider-runtime")
public final class ProviderRuntimeProperties {
  private boolean enabled;
  private String operatorKey = "";
  private Path workRoot = Path.of(".chromascape/provider-runtime-work");
  private Path transcriptRoot = Path.of(".chromascape/provider-transcripts");
  private int maxStdoutBytes = 1_048_576;
  private int maxStderrBytes = 131_072;
  private long maxTranscriptBytes = 1_048_576;
  private long processStartTimeoutMillis = 5_000;
  private long firstEventTimeoutMillis = 30_000;
  private long toolResponseTimeoutMillis = 5_000;
  private ProviderProcess claude =
      new ProviderProcess(
          List.of("claude"), List.of("claude-*", "sonnet", "opus"), List.of("ANTHROPIC_API_KEY"));
  private ProviderProcess codex =
      new ProviderProcess(
          List.of("codex"), List.of("gpt-*", "o3", "o4-*"), List.of("OPENAI_API_KEY"));
  private ProviderHttp mistral =
      new ProviderHttp(
          "https://api.mistral.ai/v1",
          List.of("mistral-*", "magistral-*"),
          List.of("MISTRAL_API_KEY"),
          1_048_576);

  @PostConstruct
  void validate() {
    if (maxStdoutBytes < 4096 || maxStderrBytes < 4096) {
      throw new IllegalStateException("Provider output limits must be at least 4096 bytes");
    }
    if (maxTranscriptBytes < 4096) {
      throw new IllegalStateException(
          "The provider transcript size limit must be at least 4096 bytes");
    }
    if (processStartTimeoutMillis <= 0
        || firstEventTimeoutMillis <= 0
        || toolResponseTimeoutMillis <= 0) {
      throw new IllegalStateException("Provider timeout limits must be positive");
    }
    if (enabled && (operatorKey == null || operatorKey.length() < 32)) {
      throw new IllegalStateException(
          "gameplay.provider-runtime.operator-key must contain at least 32 characters when"
              + " enabled");
    }
    claude.validate("claude");
    codex.validate("codex");
    mistral.validate("mistral");
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getOperatorKey() {
    return operatorKey;
  }

  public void setOperatorKey(String operatorKey) {
    this.operatorKey = operatorKey;
  }

  public Path getWorkRoot() {
    return workRoot;
  }

  public void setWorkRoot(Path workRoot) {
    this.workRoot = workRoot;
  }

  public Path getTranscriptRoot() {
    return transcriptRoot;
  }

  public void setTranscriptRoot(Path transcriptRoot) {
    this.transcriptRoot = transcriptRoot;
  }

  public int getMaxStdoutBytes() {
    return maxStdoutBytes;
  }

  public void setMaxStdoutBytes(int maxStdoutBytes) {
    this.maxStdoutBytes = maxStdoutBytes;
  }

  public int getMaxStderrBytes() {
    return maxStderrBytes;
  }

  public long getProcessStartTimeoutMillis() {
    return processStartTimeoutMillis;
  }

  public void setProcessStartTimeoutMillis(long processStartTimeoutMillis) {
    this.processStartTimeoutMillis = processStartTimeoutMillis;
  }

  public long getFirstEventTimeoutMillis() {
    return firstEventTimeoutMillis;
  }

  public void setFirstEventTimeoutMillis(long firstEventTimeoutMillis) {
    this.firstEventTimeoutMillis = firstEventTimeoutMillis;
  }

  public long getToolResponseTimeoutMillis() {
    return toolResponseTimeoutMillis;
  }

  public void setToolResponseTimeoutMillis(long toolResponseTimeoutMillis) {
    this.toolResponseTimeoutMillis = toolResponseTimeoutMillis;
  }

  public void setMaxStderrBytes(int maxStderrBytes) {
    this.maxStderrBytes = maxStderrBytes;
  }

  public long getMaxTranscriptBytes() {
    return maxTranscriptBytes;
  }

  public void setMaxTranscriptBytes(long maxTranscriptBytes) {
    this.maxTranscriptBytes = maxTranscriptBytes;
  }

  public ProviderProcess getClaude() {
    return claude;
  }

  public void setClaude(ProviderProcess claude) {
    this.claude = claude;
  }

  public ProviderProcess getCodex() {
    return codex;
  }

  public void setCodex(ProviderProcess codex) {
    this.codex = codex;
  }

  public ProviderHttp getMistral() {
    return mistral;
  }

  public void setMistral(ProviderHttp mistral) {
    this.mistral = mistral;
  }

  public static final class ProviderProcess {
    private List<String> command = new ArrayList<>();
    private List<String> allowedModels = new ArrayList<>();
    private List<String> credentialEnvironmentNames = new ArrayList<>();

    public ProviderProcess() {}

    ProviderProcess(
        List<String> command, List<String> allowedModels, List<String> credentialEnvironmentNames) {
      this.command = new ArrayList<>(command);
      this.allowedModels = new ArrayList<>(allowedModels);
      this.credentialEnvironmentNames = new ArrayList<>(credentialEnvironmentNames);
    }

    void validate(String provider) {
      if (command == null || command.isEmpty() || command.stream().anyMatch(String::isBlank)) {
        throw new IllegalStateException(provider + " command must be a direct argument array");
      }
      if (allowedModels == null || allowedModels.isEmpty()) {
        throw new IllegalStateException(provider + " requires at least one allowed model pattern");
      }
      if (credentialEnvironmentNames == null
          || credentialEnvironmentNames.stream()
              .anyMatch(value -> !value.matches("[A-Z][A-Z0-9_]{1,63}"))) {
        throw new IllegalStateException(provider + " credential environment names are invalid");
      }
    }

    public List<String> getCommand() {
      return List.copyOf(command);
    }

    public void setCommand(List<String> command) {
      this.command = new ArrayList<>(command);
    }

    public List<String> getAllowedModels() {
      return List.copyOf(allowedModels);
    }

    public void setAllowedModels(List<String> allowedModels) {
      this.allowedModels = new ArrayList<>(allowedModels);
    }

    public List<String> getCredentialEnvironmentNames() {
      return List.copyOf(credentialEnvironmentNames);
    }

    public void setCredentialEnvironmentNames(List<String> credentialEnvironmentNames) {
      this.credentialEnvironmentNames = new ArrayList<>(credentialEnvironmentNames);
    }

    public boolean allowsModel(String model) {
      return model != null
          && allowedModels.stream()
              .anyMatch(
                  pattern ->
                      model.matches(
                          "^"
                              + java.util.regex.Pattern.quote(pattern).replace("*", "\\E.*\\Q")
                              + "$"));
    }
  }

  /** Direct HTTPS endpoint configuration for the Mistral chat-completions provider. */
  public static final class ProviderHttp {
    private String baseUrl = "";
    private List<String> allowedModels = new ArrayList<>();
    private List<String> credentialEnvironmentNames = new ArrayList<>();
    private int maxResponseBytes = 1_048_576;

    /** Creates an empty configuration for property binding. */
    public ProviderHttp() {}

    /** Creates a fully specified configuration. */
    public ProviderHttp(
        String baseUrl,
        List<String> allowedModels,
        List<String> credentialEnvironmentNames,
        int maxResponseBytes) {
      this.baseUrl = baseUrl;
      this.allowedModels = new ArrayList<>(allowedModels);
      this.credentialEnvironmentNames = new ArrayList<>(credentialEnvironmentNames);
      this.maxResponseBytes = maxResponseBytes;
    }

    void validate(String provider) {
      if (baseUrl == null || baseUrl.isBlank() || !baseUrl.startsWith("https://")) {
        throw new IllegalStateException(provider + " base URL must be an https URL");
      }
      if (allowedModels == null || allowedModels.isEmpty()) {
        throw new IllegalStateException(provider + " requires at least one allowed model pattern");
      }
      if (credentialEnvironmentNames == null
          || credentialEnvironmentNames.isEmpty()
          || credentialEnvironmentNames.stream().anyMatch(String::isBlank)) {
        throw new IllegalStateException(provider + " credential environment names are invalid");
      }
      if (maxResponseBytes < 4096) {
        throw new IllegalStateException(provider + " response limit must be at least 4096 bytes");
      }
    }

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public List<String> getAllowedModels() {
      return List.copyOf(allowedModels);
    }

    public void setAllowedModels(List<String> allowedModels) {
      this.allowedModels = new ArrayList<>(allowedModels);
    }

    public List<String> getCredentialEnvironmentNames() {
      return List.copyOf(credentialEnvironmentNames);
    }

    public void setCredentialEnvironmentNames(List<String> credentialEnvironmentNames) {
      this.credentialEnvironmentNames = new ArrayList<>(credentialEnvironmentNames);
    }

    public int getMaxResponseBytes() {
      return maxResponseBytes;
    }

    public void setMaxResponseBytes(int maxResponseBytes) {
      this.maxResponseBytes = maxResponseBytes;
    }

    /** Returns whether the model id matches one of the configured patterns. */
    public boolean allowsModel(String model) {
      return model != null
          && allowedModels.stream()
              .anyMatch(
                  pattern ->
                      model.matches(
                          "^"
                              + java.util.regex.Pattern.quote(pattern).replace("*", "\\E.*\\Q")
                              + "$"));
    }
  }
}
