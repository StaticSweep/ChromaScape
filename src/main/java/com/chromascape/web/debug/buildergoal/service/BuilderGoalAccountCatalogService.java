package com.chromascape.web.debug.buildergoal.service;

import java.time.Instant;
import java.util.Collections;
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
                      left.displayName()
                          .toLowerCase(Locale.ENGLISH)
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
        firstNonBlank(
            launcherAccount.displayName(), launcherAccount.email(), launcherAccount.accountId());
    return new AccountDefinition(
        launcherAccount.accountId(),
        launcherAccount.accountHash(),
        firstNonBlank(launcherAccount.accountName(), displayName),
        firstNonBlank(launcherAccount.launchProfileName(), displayName),
        displayName,
        launcherAccount.accountHashScheme(),
        launcherAccount.email(),
        "launcher",
        launcherAccount.enabled(),
        launcherAccount.status(),
        launcherAccount.metadata());
  }

  private Map<String, Object> toSummary(AccountDefinition account) {
    Micro6OnlineClientRegistryService.TargetClient matchedClient = findMatchingClient(account);
    boolean executorOnline = matchedClient != null;
    boolean selectedAccountEligible = isSelectedAccountEligible(account, matchedClient);
    boolean runnable = account.isRunnable();
    boolean hasEmail = account.hasEmail();
    boolean hasPassword = account.hasPassword();
    boolean launchReady = account.isLaunchReady();

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("accountId", account.accountId());
    summary.put("accountHash", account.accountHash());
    summary.put("accountName", account.accountName());
    summary.put("displayName", account.displayName());
    summary.put("launchProfileName", account.launchProfileName());
    summary.put("accountHashScheme", account.accountHashScheme());
    summary.put("email", account.email());
    summary.put("source", account.source());
    summary.put("online", executorOnline);
    summary.put("executorOnline", executorOnline);
    summary.put("selectedAccountEligible", selectedAccountEligible);
    summary.put(
        "selectedAccountEligibilityReason",
        selectedAccountEligibilityReason(account, matchedClient, selectedAccountEligible));
    summary.put("enabled", account.enabled());
    summary.put("status", account.status());
    summary.put("runnable", runnable);
    summary.put("hasEmail", hasEmail);
    summary.put("hasPassword", hasPassword);
    summary.put("launchReady", launchReady);
    summary.put("disabledReason", account.determineDisabledReason());
    summary.put("launchDisabledReason", account.determineLaunchDisabledReason());
    summary.put("launcherBaseUrl", launcherControlService.getBaseUrl());
    return summary;
  }

  private Micro6OnlineClientRegistryService.TargetClient findMatchingClient(
      AccountDefinition account) {
    return clientRegistryService
        .findOnlineClientForRecovery(
            account.accountHash(), account.accountName(), null, Instant.now().minusSeconds(45))
        .orElse(null);
  }

  private boolean isSelectedAccountEligible(
      AccountDefinition account, Micro6OnlineClientRegistryService.TargetClient client) {
    if (account == null || client == null) {
      return false;
    }
    return AccountIdentityContract.hasRequiredScheme(account.accountHashScheme())
        && AccountIdentityContract.isCanonicalHash(account.accountHash())
        && clientRegistryService.isSelectedAccountEligible(client, account.accountHash());
  }

  private String selectedAccountEligibilityReason(
      AccountDefinition account,
      Micro6OnlineClientRegistryService.TargetClient client,
      boolean eligible) {
    if (eligible) {
      return null;
    }
    if (client == null) {
      return "No matching executor identity is online";
    }
    if (!AccountIdentityContract.hasRequiredScheme(account.accountHashScheme())) {
      return "Selected account did not report account hash scheme "
          + AccountIdentityContract.HASH_SCHEME;
    }
    if (!AccountIdentityContract.isCanonicalHash(account.accountHash())) {
      return "Selected account did not report a canonical 16-hex game-account hash";
    }
    if (!clientRegistryService.isDispatchReady(client)) {
      return "Executor is online but not generically dispatch-ready";
    }
    if (!AccountIdentityContract.hasRequiredScheme(client.accountHashScheme())) {
      return "Executor did not report account hash scheme " + AccountIdentityContract.HASH_SCHEME;
    }
    if (!AccountIdentityContract.matchesCanonicalHash(
        client.accountHash(), account.accountHash())) {
      return "Executor actual account hash does not match the selected account";
    }
    if (client.requestedWorld() == null
        || client.currentWorld() == null
        || !client.requestedWorld().equals(client.currentWorld())) {
      return "Executor world identity is not confirmed";
    }
    return "Selected-account eligibility evidence is incomplete";
  }

  private static boolean isBanned(String status) {
    String normalized = normalize(status);
    return "banned".equals(normalized)
        || "temp_banned".equals(normalized)
        || "tempban".equals(normalized)
        || "temp-ban".equals(normalized)
        || "temp_ban".equals(normalized);
  }

  private static String normalize(String value) {
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
      String accountName,
      String launchProfileName,
      String displayName,
      String accountHashScheme,
      String email,
      String source,
      boolean enabled,
      String status,
      Map<String, Object> metadata) {

    public AccountDefinition {
      Objects.requireNonNull(accountId, "accountId");
      Objects.requireNonNull(displayName, "displayName");
      Objects.requireNonNull(source, "source");
      metadata =
          metadata != null ? Collections.unmodifiableMap(new LinkedHashMap<>(metadata)) : Map.of();
    }

    /** Compatibility constructor for persisted/test callers using the original account shape. */
    public AccountDefinition(
        String accountId,
        String accountHash,
        String displayName,
        String email,
        String source,
        boolean enabled,
        String status,
        Map<String, Object> metadata) {
      this(
          accountId,
          accountHash,
          displayName,
          displayName,
          displayName,
          null,
          email,
          source,
          enabled,
          status,
          metadata);
    }

    public boolean isRunnable() {
      return enabled() && !isBanned(status());
    }

    public String determineDisabledReason() {
      if (!enabled()) {
        return "Account is disabled in launcher control";
      }
      if (isBanned(status())) {
        return "Account is banned in launcher control";
      }
      return null;
    }

    public boolean hasEmail() {
      return email() != null && !email().isBlank();
    }

    /**
     * Launcher control strips every secret from the account list payload and reports only the
     * boolean {@code secret_fields} map, so a stored password is never visible as a plaintext field
     * here. The declared flag is authoritative whenever launcher control supplies it; the plaintext
     * fallback only serves fixtures and producers that still inline the value.
     */
    public boolean hasPassword() {
      Boolean declared = declaredSecretField("password");
      if (declared != null) {
        return declared;
      }
      return password() != null;
    }

    /**
     * Reads one entry of the launcher {@code secret_fields} map, returning {@code null} when
     * launcher control did not declare the field so callers can fall back rather than treating an
     * absent map as a missing secret.
     */
    private Boolean declaredSecretField(String field) {
      if (!(metadata.get("secret_fields") instanceof Map<?, ?> secretFields)) {
        return null;
      }
      Object value = secretFields.get(field);
      if (value instanceof Boolean declared) {
        return declared;
      }
      if (value instanceof String text && !text.isBlank()) {
        return Boolean.parseBoolean(text.trim());
      }
      return null;
    }

    public boolean isLaunchReady() {
      return isRunnable()
          && AccountIdentityContract.isCanonicalHash(accountHash())
          && AccountIdentityContract.hasRequiredScheme(accountHashScheme())
          && hasEmail()
          && hasPassword();
    }

    public String determineLaunchDisabledReason() {
      if (!enabled()) {
        return "Account is disabled in launcher control";
      }
      if (isBanned(status())) {
        return "Account is banned in launcher control";
      }
      if (!AccountIdentityContract.isCanonicalHash(accountHash())) {
        return "Launcher control must provide a canonical 16-hex game-account hash";
      }
      if (!AccountIdentityContract.hasRequiredScheme(accountHashScheme())) {
        return "Launcher control must provide account hash scheme "
            + AccountIdentityContract.HASH_SCHEME;
      }
      if (!hasEmail()) {
        return "Add an email to this account in launcher control";
      }
      if (!hasPassword()) {
        return "Add a password to this account in launcher control";
      }
      return null;
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
