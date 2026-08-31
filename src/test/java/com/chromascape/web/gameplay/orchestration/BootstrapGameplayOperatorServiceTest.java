package com.chromascape.web.gameplay.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.gameplay.authority.HumanGameplayAuthorityService;
import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContractValidator;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.Outcome;
import com.chromascape.web.gameplay.orchestration.BootstrapGameplayOperatorService.ApprovalRequest;
import com.chromascape.web.gameplay.orchestration.BootstrapGameplayOperatorService.DispatchRequest;
import com.chromascape.web.gameplay.orchestration.BootstrapGameplayOperatorService.OperatorException;
import com.chromascape.web.gameplay.orchestration.BootstrapGameplayOperatorService.ProposalRequest;
import com.chromascape.web.gameplay.provider.ClaudeGameplayProviderAdapter;
import com.chromascape.web.gameplay.provider.CodexGameplayProviderAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BootstrapGameplayOperatorServiceTest {
  private static final String AUTHORITY_SECRET = "authority-test-secret-at-least-32-bytes";
  private static final String OPERATOR_KEY = "operator-test-key-at-least-32-bytes";

  @TempDir Path temporaryDirectory;

  private ObjectMapper objectMapper;
  private JsonNode fixtures;
  private BootstrapGameplayOperatorService service;
  private FakeGateway gateway;

  @BeforeEach
  void setUp() throws Exception {
    objectMapper =
        JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    fixtures =
        objectMapper.readTree(
            getClass()
                .getClassLoader()
                .getResourceAsStream("contracts/gameplay/sc-1429-golden-fixtures-v1.json"));
    CanonicalGameplayJson canonicalJson = new CanonicalGameplayJson(objectMapper);
    GameplayContractValidator validator = new GameplayContractValidator(objectMapper);
    HumanGameplayAuthorityService authorityService =
        new HumanGameplayAuthorityService(canonicalJson, AUTHORITY_SECRET);
    gateway = new FakeGateway();
    BootstrapGameplayOrchestrator orchestrator =
        new BootstrapGameplayOrchestrator(
            validator,
            canonicalJson,
            gateway,
            new BootstrapEvidenceStore(objectMapper, canonicalJson, temporaryDirectory));
    service =
        new BootstrapGameplayOperatorService(
            canonicalJson,
            authorityService,
            orchestrator,
            List.of(
                new ClaudeGameplayProviderAdapter(validator),
                new CodexGameplayProviderAdapter(validator)),
            Clock.systemUTC(),
            true,
            OPERATOR_KEY);
  }

  @Test
  void previewApproveAndDispatchConsumeOneExactHumanApproval() {
    ProposalRequest draft = proposal(envelopeWithFutureDeadline());
    var preview = service.preview(OPERATOR_KEY, draft);
    var approval =
        service.approve(
            OPERATOR_KEY,
            new ApprovalRequest(
                draft,
                "operator-sc1498",
                Instant.now().plus(5, ChronoUnit.MINUTES),
                preview.scopeDigest(),
                true));
    ProposalRequest approved = proposal(withAuthority(draft.envelope(), approval.authorityId()));

    var receipt =
        service.dispatch(OPERATOR_KEY, new DispatchRequest(approval.authorityId(), approved));

    assertThat(receipt.outcome()).isEqualTo(Outcome.CONFIRMED);
    assertThat(gateway.dispatches).hasValue(1);
    assertThat(temporaryDirectory.resolve("recording-sc1498-001.sc-1498.json")).exists();
    assertThatThrownBy(
            () ->
                service.dispatch(
                    OPERATOR_KEY, new DispatchRequest(approval.authorityId(), approved)))
        .isInstanceOf(OperatorException.class)
        .hasMessageContaining("already used");
  }

  @Test
  void approvalRequiresHumanConfirmationOfTheCurrentDigest() {
    ProposalRequest draft = proposal(envelopeWithFutureDeadline());
    var preview = service.preview(OPERATOR_KEY, draft);

    assertThatThrownBy(
            () ->
                service.approve(
                    OPERATOR_KEY,
                    new ApprovalRequest(
                        draft,
                        "operator-sc1498",
                        Instant.now().plus(5, ChronoUnit.MINUTES),
                        preview.scopeDigest(),
                        false)))
        .isInstanceOf(OperatorException.class)
        .hasMessageContaining("explicit human confirmation");
    assertThatThrownBy(
            () ->
                service.approve(
                    OPERATOR_KEY,
                    new ApprovalRequest(
                        draft,
                        "operator-sc1498",
                        Instant.now().plus(5, ChronoUnit.MINUTES),
                        "sha256:" + "0".repeat(64),
                        true)))
        .isInstanceOf(OperatorException.class)
        .hasMessageContaining("does not match");
  }

  @Test
  void operatorAuthenticationAndStopFailClosed() {
    assertThatThrownBy(() -> service.preview("wrong-key", proposal(envelopeWithFutureDeadline())))
        .isInstanceOf(OperatorException.class)
        .hasMessageContaining("valid SC-1498 operator key");
    assertThatThrownBy(
            () ->
                service.stop(
                    OPERATOR_KEY,
                    new BootstrapGameplayOperatorService.StopRequest(
                        "request-not-active", "operator requested stop", true)))
        .isInstanceOf(BootstrapGameplayOrchestrator.GameplayProposalException.class)
        .hasMessageContaining("not active");
    assertThat(gateway.stops).hasValue(0);
  }

  private ProposalRequest proposal(ExecutionEnvelope envelope) {
    return new ProposalRequest(
        fixtures.path("observation").deepCopy(),
        "claude-fixture-v1",
        fixtures.path("claudeProposal").deepCopy(),
        envelope);
  }

  private ExecutionEnvelope envelopeWithFutureDeadline() {
    ObjectNode node = fixtures.path("executionEnvelope").deepCopy();
    node.put("deadline", Instant.now().plus(10, ChronoUnit.MINUTES).toString());
    return objectMapper.convertValue(node, ExecutionEnvelope.class);
  }

  private ExecutionEnvelope withAuthority(ExecutionEnvelope envelope, String authorityId) {
    ObjectNode node = objectMapper.valueToTree(envelope);
    node.put("authorityId", authorityId);
    return objectMapper.convertValue(node, ExecutionEnvelope.class);
  }

  private static final class FakeGateway implements AccountBuilderGameplayGateway {
    private final AtomicInteger dispatches = new AtomicInteger();
    private final AtomicInteger stops = new AtomicInteger();

    @Override
    public DispatchResult dispatch(
        ExecutionEnvelope envelope,
        com.chromascape.web.gameplay.contract.GameplayContracts.HumanAuthority authority) {
      dispatches.incrementAndGet();
      return new DispatchResult(Outcome.CONFIRMED, null, true, true, List.of("micro6:terminal"));
    }

    @Override
    public DispatchResult stop(String requestId, String reason, boolean killSwitch) {
      stops.incrementAndGet();
      return new DispatchResult(Outcome.STOPPED, null, true, true, List.of("micro6:stop"));
    }
  }
}
