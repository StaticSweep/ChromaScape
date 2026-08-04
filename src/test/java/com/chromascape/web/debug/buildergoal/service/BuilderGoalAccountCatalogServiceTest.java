package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.buildergoal.service.BuilderGoalAccountCatalogService.AccountDefinition;
import com.chromascape.web.debug.buildergoal.service.BuilderGoalLauncherControlService.LauncherAccount;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BuilderGoalAccountCatalogServiceTest {

  @Mock private BuilderGoalLauncherControlService launcherControlService;
  @Mock private Micro6OnlineClientRegistryService clientRegistryService;

  private BuilderGoalAccountCatalogService service;

  @BeforeEach
  void setUp() {
    service = new BuilderGoalAccountCatalogService(launcherControlService, clientRegistryService, 30L);
  }

  private AccountDefinition account(
      String id,
      String email,
      boolean enabled,
      String status,
      Map<String, Object> metadata) {
    return new AccountDefinition(
        id,
        id,
        id,
        email,
        "launcher",
        enabled,
        status,
        metadata == null ? Map.of() : metadata);
  }

  @Test
  void accountDefinition_withCompleteCredentialsIsLaunchReady() {
    AccountDefinition account =
        account("hash-1", "user@example.test", true, "ready", Map.of("password", "s3cret"));

    assertThat(account.isRunnable()).isTrue();
    assertThat(account.hasEmail()).isTrue();
    assertThat(account.hasPassword()).isTrue();
    assertThat(account.isLaunchReady()).isTrue();
    assertThat(account.determineLaunchDisabledReason()).isNull();
  }

  @Test
  void accountDefinition_missingEmailIsNotLaunchReady() {
    AccountDefinition account =
        account("hash-1", null, true, "ready", Map.of("password", "s3cret"));

    assertThat(account.isRunnable()).isTrue();
    assertThat(account.hasEmail()).isFalse();
    assertThat(account.hasPassword()).isTrue();
    assertThat(account.isLaunchReady()).isFalse();
    assertThat(account.determineLaunchDisabledReason())
        .isEqualTo("Add an email to this account in launcher control");
  }

  @Test
  void accountDefinition_blankEmailIsNotLaunchReady() {
    AccountDefinition account =
        account("hash-1", "   ", true, "ready", Map.of("password", "s3cret"));

    assertThat(account.hasEmail()).isFalse();
    assertThat(account.isLaunchReady()).isFalse();
    assertThat(account.determineLaunchDisabledReason())
        .isEqualTo("Add an email to this account in launcher control");
  }

  @Test
  void accountDefinition_missingPasswordIsNotLaunchReady() {
    AccountDefinition account =
        account("hash-1", "user@example.test", true, "ready", Map.of());

    assertThat(account.hasEmail()).isTrue();
    assertThat(account.hasPassword()).isFalse();
    assertThat(account.isLaunchReady()).isFalse();
    assertThat(account.determineLaunchDisabledReason())
        .isEqualTo("Add a password to this account in launcher control");
  }

  @Test
  void accountDefinition_blankPasswordIsNotLaunchReady() {
    AccountDefinition account =
        account("hash-1", "user@example.test", true, "ready", Map.of("password", "   "));

    assertThat(account.hasPassword()).isFalse();
    assertThat(account.isLaunchReady()).isFalse();
    assertThat(account.determineLaunchDisabledReason())
        .isEqualTo("Add a password to this account in launcher control");
  }

  @Test
  void accountDefinition_disabledAccountIsNotRunnableAndNotLaunchReady() {
    AccountDefinition account =
        account("hash-1", "user@example.test", false, "ready", Map.of("password", "s3cret"));

    assertThat(account.isRunnable()).isFalse();
    assertThat(account.isLaunchReady()).isFalse();
    assertThat(account.determineLaunchDisabledReason())
        .isEqualTo("Account is disabled in launcher control");
  }

  @Test
  void accountDefinition_bannedAccountIsNotRunnableAndNotLaunchReady() {
    AccountDefinition account =
        account("hash-1", "user@example.test", true, "banned", Map.of("password", "s3cret"));

    assertThat(account.isRunnable()).isFalse();
    assertThat(account.isLaunchReady()).isFalse();
    assertThat(account.determineLaunchDisabledReason())
        .isEqualTo("Account is banned in launcher control");
  }

  @Test
  void accountDefinition_tempBannedStatusCountsAsBanned() {
    AccountDefinition account =
        account("hash-1", "user@example.test", true, "temp_banned", Map.of("password", "s3cret"));

    assertThat(account.isRunnable()).isFalse();
    assertThat(account.determineLaunchDisabledReason())
        .isEqualTo("Account is banned in launcher control");
  }

  @Test
  void accountDefinition_disabledReasonTakesPrecedenceOverMissingCredentials() {
    AccountDefinition account =
        account("hash-1", null, false, "banned", Map.of());

    assertThat(account.isLaunchReady()).isFalse();
    assertThat(account.determineLaunchDisabledReason())
        .isEqualTo("Account is disabled in launcher control");
  }

  @Test
  void accountDefinition_missingEmailTakesPrecedenceOverMissingPassword() {
    AccountDefinition account =
        account("hash-1", null, true, "ready", Map.of());

    assertThat(account.determineLaunchDisabledReason())
        .isEqualTo("Add an email to this account in launcher control");
  }

  @Test
  void listAccounts_retainsOnlineAliasAndAddsExecutorFields() {
    LauncherAccount launcherAccount =
        new LauncherAccount(
            "hash-1",
            "hash-1",
            "bigGreenMeat",
            "user@example.test",
            "ready",
            true,
            Map.of("password", "s3cret"));
    Micro6OnlineClientRegistryService.TargetClient targetClient =
        new Micro6OnlineClientRegistryService.TargetClient(
            "client-1",
            "bigGreenMeat",
            "hash-1",
            "http://127.0.0.1:8098",
            "0.0.0.0:8098",
            "micro6",
            "accountbuilder",
            "IDLE",
            null,
            Instant.now());
    when(launcherControlService.listAccounts()).thenReturn(List.of(launcherAccount));
    when(launcherControlService.getBaseUrl()).thenReturn("http://192.168.1.6:8084");
    when(clientRegistryService.findRunnableClientForRecovery(
            eq("hash-1"), eq("bigGreenMeat"), isNull(), any()))
        .thenReturn(Optional.of(targetClient));

    Map<String, Object> summary = service.listAccounts().get(0);

    assertThat(summary.get("accountId")).isEqualTo("hash-1");
    assertThat(summary.get("online")).isEqualTo(true);
    assertThat(summary.get("executorOnline")).isEqualTo(true);
    assertThat(summary.get("runnable")).isEqualTo(true);
    assertThat(summary.get("hasEmail")).isEqualTo(true);
    assertThat(summary.get("hasPassword")).isEqualTo(true);
    assertThat(summary.get("launchReady")).isEqualTo(true);
    assertThat(summary.get("disabledReason")).isNull();
    assertThat(summary.get("launchDisabledReason")).isNull();
    assertThat(summary.get("online")).isEqualTo(summary.get("executorOnline"));
  }

  @Test
  void listAccounts_reportsSetupNeededWhenCredentialsAreMissing() {
    LauncherAccount launcherAccount =
        new LauncherAccount(
            "3720a9a3b5c9f57b",
            "3720a9a3b5c9f57b",
            "udasuAvuco",
            null,
            "ready",
            true,
            Map.of());
    when(launcherControlService.listAccounts()).thenReturn(List.of(launcherAccount));
    when(launcherControlService.getBaseUrl()).thenReturn("http://192.168.1.6:8084");

    Map<String, Object> summary = service.listAccounts().get(0);

    assertThat(summary.get("runnable")).isEqualTo(true);
    assertThat(summary.get("hasEmail")).isEqualTo(false);
    assertThat(summary.get("hasPassword")).isEqualTo(false);
    assertThat(summary.get("launchReady")).isEqualTo(false);
    assertThat(summary.get("disabledReason")).isNull();
    assertThat(summary.get("launchDisabledReason"))
        .isEqualTo("Add an email to this account in launcher control");
  }

  @Test
  void accountDefinition_determineDisabledReasonReturnsNullForRunnableAccount() {
    AccountDefinition account =
        account("hash-1", "user@example.test", true, "ready", Map.of("password", "s3cret"));

    assertThat(account.determineDisabledReason()).isNull();
    assertThat(account.isRunnable()).isTrue();
  }

  @Test
  void accountDefinition_determineDisabledReasonReportsDisabled() {
    AccountDefinition account =
        account("hash-1", "user@example.test", false, "ready", Map.of("password", "s3cret"));

    assertThat(account.determineDisabledReason())
        .isEqualTo("Account is disabled in launcher control");
    assertThat(account.isRunnable()).isFalse();
  }

  @Test
  void accountDefinition_determineDisabledReasonReportsBanned() {
    AccountDefinition account =
        account("hash-1", "user@example.test", true, "banned", Map.of("password", "s3cret"));

    assertThat(account.determineDisabledReason())
        .isEqualTo("Account is banned in launcher control");
    assertThat(account.isRunnable()).isFalse();
  }

  @Test
  void listAccounts_summaryDisabledReasonUsesDetermineDisabledReason() {
    LauncherAccount launcherAccount =
        new LauncherAccount(
            "hash-1",
            "hash-1",
            "bigGreenMeat",
            "user@example.test",
            "banned",
            true,
            Map.of("password", "s3cret"));
    when(launcherControlService.listAccounts()).thenReturn(List.of(launcherAccount));
    when(launcherControlService.getBaseUrl()).thenReturn("http://192.168.1.6:8084");

    Map<String, Object> summary = service.listAccounts().get(0);

    assertThat(summary.get("runnable")).isEqualTo(false);
    assertThat(summary.get("disabledReason"))
        .isEqualTo("Account is banned in launcher control");
    assertThat(summary.get("launchDisabledReason"))
        .isEqualTo("Account is banned in launcher control");
    assertThat(summary).doesNotContainKey("password");
  }

  @Test
  void listAccounts_onlineAliasAlwaysMatchesExecutorOnline() {
    LauncherAccount launcherAccount =
        new LauncherAccount(
            "hash-1",
            "hash-1",
            "bigGreenMeat",
            "user@example.test",
            "ready",
            true,
            Map.of("password", "s3cret"));
    when(launcherControlService.listAccounts()).thenReturn(List.of(launcherAccount));
    when(launcherControlService.getBaseUrl()).thenReturn("http://192.168.1.6:8084");

    Map<String, Object> summary = service.listAccounts().get(0);

    assertThat(summary.get("online")).isEqualTo(summary.get("executorOnline"));
    assertThat(summary.get("online")).isEqualTo(false);
  }

  @Test
  void listAccounts_neverDisclosesPassword() {
    LauncherAccount launcherAccount =
        new LauncherAccount(
            "hash-1",
            "hash-1",
            "bigGreenMeat",
            "user@example.test",
            "ready",
            true,
            Map.of("password", "hunter2-secret"));
    when(launcherControlService.listAccounts()).thenReturn(List.of(launcherAccount));
    when(launcherControlService.getBaseUrl()).thenReturn("http://192.168.1.6:8084");

    Map<String, Object> summary = service.listAccounts().get(0);

    assertThat(summary).doesNotContainKey("password");
    assertThat(summary.get("launchDisabledReason")).isNull();
    String rendered = summary.toString();
    assertThat(rendered).doesNotContain("hunter2-secret");
  }

  @Test
  void accountDefinition_acceptsNullValuedMetadataFields() {
    Map<String, Object> metadata = new java.util.LinkedHashMap<>();
    metadata.put("runtime", null);
    metadata.put("hostname", "CZC8518C27");
    AccountDefinition account = account("hash-1", "user@example.test", true, "ready", metadata);

    assertThat(account.metadata()).containsKey("runtime");
    assertThat(account.metadata().get("runtime")).isNull();
    assertThat(account.metadata().get("hostname")).isEqualTo("CZC8518C27");
  }

  @Test
  void accountDefinition_metadataSnapshotIsImmutable() {
    Map<String, Object> metadata = new java.util.LinkedHashMap<>();
    metadata.put("runtime", null);
    AccountDefinition account = account("hash-1", "user@example.test", true, "ready", metadata);

    assertThatThrownBy(() -> account.metadata().put("runtime", "mutated"))
        .isInstanceOf(UnsupportedOperationException.class);
    // The caller-owned map remains untouched by the snapshot.
    metadata.put("runtime", "mutated");
    assertThat(account.metadata().get("runtime")).isNull();
  }

  @Test
  void listAccounts_reportsOfflineWhenNoMatchingExecutorHeartbeat() {
    LauncherAccount launcherAccount =
        new LauncherAccount(
            "hash-1",
            "hash-1",
            "bigGreenMeat",
            "user@example.test",
            "ready",
            true,
            Map.of("password", "s3cret"));
    when(launcherControlService.listAccounts()).thenReturn(List.of(launcherAccount));
    when(launcherControlService.getBaseUrl()).thenReturn("http://192.168.1.6:8084");

    Map<String, Object> summary = service.listAccounts().get(0);

    assertThat(summary.get("online")).isEqualTo(false);
    assertThat(summary.get("executorOnline")).isEqualTo(false);
  }
}
