package com.chromascape.web.debug.buildergoal.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class BuilderGoalAccountCatalogService {

  private static final Logger log = LoggerFactory.getLogger(BuilderGoalAccountCatalogService.class);

  private final BuilderGoalLauncherControlService launcherControlService;
  private final Micro6OnlineClientRegistryService clientRegistryService;
  private final long refreshSeconds;
  private final AtomicReference<List<AccountDefinition>> cache = new AtomicReference<>(List.of());
  private volatile Instant lastRefresh = Instant.EPOCH;

  public BuilderGoalAccountCatalogService(
      BuilderGoalLauncherControlService launcherControlService,
      Micro6OnlineClientRegistryService clientRegistryService,
      @Value("${builder-goal.launcher.accounts-refresh-seconds:30}") long refreshSeconds) {
    this.launcherControlService = launcherControlService;
    this.clientRegistryService = clientRegistryService;
    this.refreshSeconds = Math.max(1L, refreshSeconds);
  }

  public List<Map<String, Object>> listAccounts() {
    return getAccounts(false).stream().map(this::toSummary).toList();
  }

  public Optional<AccountDefinition> findAccount(String accountId) {
    if (accountId == null || accountId.isBlank()) {
      return Optional.empty();
    }
    return getAccounts(false).stream()
        .filter(account -> account.accountId().equals(accountId.trim()))
        .findFirst();
  }

  private List<AccountDefinition> getAccounts(boolean forceRefresh) {
    if (!forceRefresh
        && !cache.get().isEmpty()
        && Instant.now().isBefore(lastRefresh.plusSeconds(refreshSeconds))) {
      return cache.get();
    }

    try {
      List<AccountDefinition> accounts =
          launcherControlService.listAccounts().stream()
              .map(this::toAccountDefinition)
              .sorted(
                  (left, right) ->
                      left.displayName().toLowerCase(Locale.ENGLISH)
                          .compareTo(right.displayName().toLowerCase(Locale.ENGLISH)))
              .toList();
      cache.set(accounts);
      lastRefresh = Instant.now();
      return accounts;
    } catch (Exception e) {
      log.warn("Failed to load launcher-backed builder-goal accounts: {}", e.getMessage());
      return cache.get();
    }
  }

  private AccountDefinition toAccountDefinition(
      BuilderGoalLauncherControlService.LauncherAccount launcherAccount) {
    String displayName =
        firstNonBlank(launcherAccount.displayName(), launcherAccount.email(), launcherAccount.accountId());
    return new AccountDefinition(
        launcherAccount.accountId(),
        launcherAccount.accountHash(),
        displayName,
        launcherAccount.email(),
        "launcher",
        launcherAccount.enabled(),
        launcherAccount.status(),
        launcherAccount.metadata());
  }

  private Map<String, Object> toSummary(AccountDefinition account) {
    Micro6OnlineClientRegistryService.TargetClient matchedClient = findMatchingClient(account);
    boolean online = matchedClient != null;
    boolean enabled = account.enabled();
    boolean banned = isBanned(account.status());
    boolean runnable = enabled && !banned;

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("accountId", account.accountId());
    summary.put("accountHash", account.accountHash());
    summary.put("accountName", account.displayName());
    summary.put("displayName", account.displayName());
    summary.put("email", account.email());
    summary.put("source", account.source());
    summary.put("online", online);
    summary.put("enabled", enabled);
    summary.put("status", account.status());
    summary.put("runnable", runnable);
    summary.put("disabledReason", determineDisabledReason(account));
    summary.put("launcherBaseUrl", launcherControlService.getBaseUrl());
    return summary;
  }

  private Micro6OnlineClientRegistryService.TargetClient findMatchingClient(AccountDefinition account) {
    return clientRegistryService
        .findRunnableClientForRecovery(
            account.accountHash(), account.displayName(), null, Instant.now().minusSeconds(45))
        .orElse(null);
  }

  private String determineDisabledReason(AccountDefinition account) {
    if (!account.enabled()) {
      return "Account is disabled in launcher control";
    }
    if (isBanned(account.status())) {
      return "Account is banned in launcher control";
    }
    return null;
  }

  private boolean isBanned(String status) {
    String normalized = normalize(status);
    return "banned".equals(normalized)
        || "temp_banned".equals(normalized)
        || "tempban".equals(normalized)
        || "temp-ban".equals(normalized)
        || "temp_ban".equals(normalized);
  }

  private String normalize(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim().toLowerCase(Locale.ENGLISH);
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return null;
  }

  public record AccountDefinition(
      String accountId,
      String accountHash,
      String displayName,
      String email,
      String source,
      boolean enabled,
      String status,
      Map<String, Object> metadata) {

    public AccountDefinition {
      Objects.requireNonNull(accountId, "accountId");
      Objects.requireNonNull(displayName, "displayName");
      Objects.requireNonNull(source, "source");
      metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public String password() {
      Object value = metadata.get("password");
      if (value == null) {
        return null;
      }
      String text = value.toString().trim();
      return text.isEmpty() ? null : text;
    }
  }
}
