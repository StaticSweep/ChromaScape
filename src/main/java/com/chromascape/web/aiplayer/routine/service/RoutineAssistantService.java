package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.routine.dto.RoutineApiDtos;
import com.chromascape.web.aiplayer.routine.entity.BehaviorGraphDefinition;
import com.chromascape.web.aiplayer.routine.entity.RoutineAssistantConversation;
import com.chromascape.web.aiplayer.routine.entity.RoutineAssistantEvent;
import com.chromascape.web.aiplayer.routine.entity.RoutineAssistantProposal;
import com.chromascape.web.aiplayer.routine.repository.BehaviorGraphDefinitionRepository;
import com.chromascape.web.aiplayer.routine.repository.CurrentSliceRunRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineAssistantConversationRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineAssistantEventRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineAssistantProposalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class RoutineAssistantService {
  private final RoutineAssistantProvider provider;
  private final ObjectMapper mapper;
  private final RoutineAssistantConversationRepository conversations;
  private final RoutineAssistantEventRepository events;
  private final RoutineAssistantProposalRepository proposals;
  private final BehaviorGraphDefinitionRepository graphs;
  private final CurrentSliceRunRepository runs;
  private final BehaviorGraphService graphService;
  private final GraphSchemaValidator schemaValidator;
  private final RoutineAssistantConversationStore store;
  private final ExecutorService generationExecutor = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(16), new ThreadPoolExecutor.AbortPolicy());
  private final Map<String, Future<?>> activeGenerations = new ConcurrentHashMap<>();
  private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(1);
  private static final int SSE_PAGE_SIZE = 250;
  private static final int MAX_GENERATION_MESSAGE_BYTES = 2 * 1024 * 1024;

  public RoutineAssistantService(RoutineAssistantProvider provider, ObjectMapper mapper,
      RoutineAssistantConversationRepository conversations, RoutineAssistantEventRepository events,
      RoutineAssistantProposalRepository proposals, BehaviorGraphDefinitionRepository graphs,
      CurrentSliceRunRepository runs, BehaviorGraphService graphService,
      GraphSchemaValidator schemaValidator, RoutineAssistantConversationStore store) {
    this.provider = provider; this.mapper = mapper; this.conversations = conversations; this.events = events; this.proposals = proposals; this.graphs = graphs; this.runs = runs; this.graphService = graphService; this.schemaValidator = schemaValidator; this.store = store;
  }

  @Transactional
  public Map<String, Object> create(String runId) {
    if (runId == null || runId.isBlank()) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTITY_FIELD_MISSING", "runId is required");
    if (runs.findByRunId(runId).isEmpty()) throw error(HttpStatus.NOT_FOUND, "NOT_FOUND", "run not found");
    RoutineAssistantConversation conversation = new RoutineAssistantConversation();
    conversation.setConversationId("conv_" + UUID.randomUUID().toString().replace("-", "")); conversation.setRunId(runId); conversation.setState("OPEN"); conversations.save(conversation);
    store.appendOperatorEvent(conversation.getConversationId(), "conversation.started", write(Map.of("run_id", runId, "control_allowed", false)));
    return response(conversation);
  }

  /**
   * Returns a bounded, polling-friendly view of a conversation. The acceptance harness uses this
   * instead of scraping the resumable SSE stream, while the browser continues to use SSE for live
   * deltas. No token, unbounded event history, or mutable authority is exposed.
   */
  @Transactional(readOnly = true)
  public Map<String, Object> status(String id) {
    RoutineAssistantConversation conversation = conversation(id);
    Map<String, Object> result = new LinkedHashMap<>(response(conversation));
    proposals.findFirstByConversationIdOrderByIdDesc(id)
        .ifPresent(proposal -> result.put("latest_proposal", proposalResponse(proposal)));
    return result;
  }

  /**
   * Queues generation only after the accepted-message transaction commits. One active generation per
   * conversation is enforced by the locked store; the generation token is created and
   * {@code message.accepted} is appended inside the transaction, then the async generation is
   * submitted from an afterCommit hook.
   */
  @Transactional
  public Map<String, Object> message(String id, RoutineApiDtos.AssistantMessageRequest request) {
    if (request == null || request.message() == null || request.message().isBlank()) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "MESSAGE_REQUIRED", "message is required");
    if (request.message().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_GENERATION_MESSAGE_BYTES) {
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "MESSAGE_TOO_LARGE", "assistant message exceeds 2 MiB");
    }
    String token = store.beginGeneration(id, request.message(),
        write(Map.of("message", request.message(), "control_allowed", false)));
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() { submitGeneration(id, token, request.message()); }
      });
    } else {
      submitGeneration(id, token, request.message());
    }
    return Map.of("conversation_id", id, "status", "QUEUED", "control_allowed", false);
  }

  /**
   * Scheduled recovery: locks conversations whose generation lease expired, appends
   * {@code generation.failed} with {@code GENERATION_LEASE_EXPIRED}, clears the token/lease, and
   * permits a fresh message. A provider request whose lease expired is never resumed.
   */
  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000L)
  public void recoverExpiredGenerations() {
    try {
      store.recoverExpiredGeneration();
    } catch (Exception e) {
      // A transient recovery failure is retried on the next scan; it must not kill the scheduler.
    }
  }

  private void submitGeneration(String id, String token, String message) {
    try {
      Future<?> future = generationExecutor.submit(() -> generateProposal(id, token, message));
      activeGenerations.put(id, future);
    } catch (java.util.concurrent.RejectedExecutionException e) {
      try {
        store.failGenerationIfCurrentToken(id, token,
            write(Map.of("code", "ASSISTANT_QUEUE_FULL", "control_allowed", false)));
      } catch (Exception ignored) {
      }
    }
  }

  private void generateProposal(String id, String token, String message) {
    try {
      RoutineAssistantConversation conversation = conversation(id);
      Map<String, Object> context = new LinkedHashMap<>(); context.put("run_id", conversation.getRunId());
      latestGraphForRun(conversation.getRunId()).ifPresent(graph -> {
        context.put("graph_id", graph.getGraphId()); context.put("graph_hash", graph.getGraphHash());
      });
      long delta = store.appendGenerationEvent(id, token, "analysis.delta",
          write(Map.of("text", "Reviewing immutable capture evidence and graph semantics.", "control_allowed", false)));
      if (delta < 0) return;
      Map<String, Object> proposed = provider.propose(id, message, context);
      Map<String, Object> diff = proposed.get("diff") instanceof Map ? cast(proposed.get("diff")) : Map.of();
      validateDiff(diff);
      RoutineAssistantProposal proposal = new RoutineAssistantProposal();
      proposal.setProposalId("proposal_" + UUID.randomUUID().toString().replace("-", "")); proposal.setConversationId(id); proposal.setDiffJson(write(diff)); proposal.setState("PROPOSED");
      Map<String, Object> data = new LinkedHashMap<>(); data.put("proposal_id", proposal.getProposalId()); data.put("diff", diff); data.put("status", "PROPOSED"); data.put("control_allowed", false);
      // The store verifies the token again while holding the conversation lock, then persists the
      // proposal and both terminal events in one transaction. A stale provider result is discarded
      // before the proposal row is written.
      store.commitProposalIfCurrentToken(id, token, proposal, write(data));
    } catch (Exception e) {
      try {
        store.failGenerationIfCurrentToken(id, token, write(Map.of("code", "ASSISTANT_GENERATION_FAILED", "message", e.getMessage() == null ? "generation failed" : e.getMessage(), "control_allowed", false)));
      } catch (Exception ignored) { }
    } finally { activeGenerations.remove(id); }
  }

  @Transactional
  public Map<String, Object> stop(String id) {
    RoutineAssistantConversation conversation = conversation(id);
    String token = conversation.getGenerationToken();
    // Mark the generation cancelled and clear the token BEFORE interrupting the worker, so an
    // ignored interrupt can never create a proposal.
    store.cancelGeneration(id, token);
    Future<?> active = activeGenerations.get(id);
    if (active != null) active.cancel(true);
    store.stop(id);
    return response(conversation(id));
  }

  public void stream(String id, long after, SseEmitter emitter) {
    conversation(id);
    try {
      AtomicLong cursor = new AtomicLong(Math.max(0, after));
      replayPage(id, cursor, emitter);
      ScheduledFuture<?> poll = heartbeatExecutor.scheduleAtFixedRate(() -> {
        try { replayPage(id, cursor, emitter); }
        catch (Exception e) { emitter.complete(); }
      }, 1, 1, TimeUnit.SECONDS);
      ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(() -> {
        try { emitter.send(SseEmitter.event().comment("heartbeat")); }
        catch (Exception e) { emitter.complete(); }
      }, 15, 15, TimeUnit.SECONDS);
      emitter.onCompletion(() -> { poll.cancel(false); heartbeat.cancel(false); });
      emitter.onTimeout(() -> { poll.cancel(false); heartbeat.cancel(false); });
      emitter.onError(ignored -> { poll.cancel(false); heartbeat.cancel(false); });
    } catch (Exception e) { emitter.completeWithError(e); }
  }

  /**
   * Replays durable assistant events in bounded pages (250 events per page) so SSE replay never
   * loads an unbounded event set into memory. Events remain monotonic and durable; a page stops at
   * the most recent persisted event id.
   */
  private void replayPage(String id, AtomicLong cursor, SseEmitter emitter) throws IOException {
    List<RoutineAssistantEvent> page;
    page = events.findByConversationIdAndEventIdGreaterThanOrderByEventIdAsc(
        id, cursor.get(), org.springframework.data.domain.PageRequest.of(0, SSE_PAGE_SIZE));
    for (RoutineAssistantEvent event : page) {
      send(emitter, event);
      cursor.set(event.getEventId());
    }
  }

  private void send(SseEmitter emitter, RoutineAssistantEvent event) throws IOException {
    emitter.send(SseEmitter.event().id(String.valueOf(event.getEventId())).name(event.getType()).data(event.getDataJson()));
  }

  @Transactional
  public Map<String, Object> accept(String proposalId) {
    RoutineAssistantProposal proposal = proposals.findWithLockByProposalId(proposalId).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND", "proposal not found"));
    if ("ACCEPTED".equals(proposal.getState()) && proposal.getDraftGraphId() != null) return Map.of("proposal_id", proposalId, "status", "DRAFT_CREATED", "graph_id", proposal.getDraftGraphId(), "published", false, "approved_patch", false, "control_allowed", false);
    if (!"PROPOSED".equals(proposal.getState())) throw error(HttpStatus.CONFLICT, "PROPOSAL_ALREADY_DECIDED", "proposal is no longer pending");
    Map<String, Object> diff = read(proposal.getDiffJson()); validateDiff(diff);
    String baseGraphId = String.valueOf(diff.get("base_graph_id"));
    if (baseGraphId.isBlank() || "null".equals(baseGraphId)) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "GRAPH_BASE_REQUIRED", "proposal has no graph base");
    Map<String, Object> draft = graphService.createAssistantDraft(baseGraphId, diff);
    String draftId = graphId(draft);
    proposal.setState("ACCEPTED"); proposal.setDraftGraphId(draftId); proposal.setDecisionReason("human accepted"); proposal.setDecidedAt(Instant.now());
    long eventId = store.appendOperatorEvent(proposal.getConversationId(), "proposal.accepted", write(Map.of("draft_created", true, "graph_id", draftId, "control_allowed", false)));
    proposal.setAcceptedEventId(eventId); proposals.save(proposal);
    return Map.of("proposal_id", proposalId, "status", "DRAFT_CREATED", "graph_id", draftId, "published", false, "approved_patch", false, "control_allowed", false);
  }

  @Transactional
  public Map<String, Object> reject(String proposalId, String reason) {
    RoutineAssistantProposal proposal = proposals.findWithLockByProposalId(proposalId).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND", "proposal not found"));
    if ("REJECTED".equals(proposal.getState())) return Map.of("proposal_id", proposalId, "status", "REJECTED", "control_allowed", false);
    if (!"PROPOSED".equals(proposal.getState())) throw error(HttpStatus.CONFLICT, "PROPOSAL_ALREADY_DECIDED", "proposal is no longer pending");
    proposal.setState("REJECTED"); proposal.setDecisionReason(reason == null ? "" : reason); proposal.setDecidedAt(Instant.now());
    store.appendOperatorEvent(proposal.getConversationId(), "proposal.rejected", write(Map.of("reason", reason == null ? "" : reason, "control_allowed", false)));
    proposals.save(proposal);
    return Map.of("proposal_id", proposalId, "status", "REJECTED", "control_allowed", false);
  }

  private void validateDiff(Map<String, Object> diff) {
    try { schemaValidator.validateDiff(mapper.valueToTree(diff)); }
    catch (IllegalArgumentException exception) { throw error(HttpStatus.UNPROCESSABLE_ENTITY, "ASSISTANT_DIFF_INVALID", exception.getMessage()); }
    if (!"behavior-graph-diff/v1".equals(String.valueOf(diff.get("schema")))) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "ASSISTANT_DIFF_INVALID", "unsupported graph diff schema");
    if (!(diff.get("base_graph_id") instanceof String) || !String.valueOf(diff.get("base_graph_id")).matches("graph_[0-9a-f]{24}")) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "ASSISTANT_DIFF_INVALID", "base_graph_id is invalid");
    if (!(diff.get("base_graph_hash") instanceof String) || !String.valueOf(diff.get("base_graph_hash")).matches("sha256:[0-9a-f]{64}")) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "ASSISTANT_DIFF_INVALID", "base_graph_hash is invalid");
    if (!Boolean.FALSE.equals(diff.get("control_allowed"))) throw error(HttpStatus.FORBIDDEN, "ASSISTANT_AUTHORITY_FORBIDDEN", "control_allowed must remain false");
    Object authority = diff.get("authority");
    if (!(authority instanceof Map<?, ?> values)) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "ASSISTANT_DIFF_INVALID", "authority is required");
    for (String key : List.of("can_write_source", "can_approve_patch", "can_publish_graph", "can_activate_model", "can_dispatch_input")) if (!Boolean.FALSE.equals(values.get(key))) throw error(HttpStatus.FORBIDDEN, "ASSISTANT_AUTHORITY_FORBIDDEN", "assistant authority must remain false");
    Object operations = diff.get("operations"); if (!(operations instanceof List) || ((List<?>) operations).size() > 255) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "ASSISTANT_DIFF_INVALID", "graph diff operations are invalid");
  }

  @SuppressWarnings("unchecked") private String graphId(Map<String, Object> envelope) {
    Object definition = envelope.get("definition");
    if (definition instanceof Map<?, ?> value && value.get("graph_id") != null) return String.valueOf(value.get("graph_id"));
    if (envelope.get("graph_id") != null) return String.valueOf(envelope.get("graph_id"));
    throw error(HttpStatus.INTERNAL_SERVER_ERROR, "GRAPH_INVALID", "draft graph response has no graph id");
  }
  private RoutineAssistantConversation conversation(String id) { return conversations.findByConversationId(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND", "conversation not found")); }
  private Map<String, Object> response(RoutineAssistantConversation c) { long next = (c.getNextEventId() == null ? 0L : c.getNextEventId()) + 1L; return Map.of("conversation_id", c.getConversationId(), "run_id", c.getRunId(), "state", c.getState(), "next_event_id", next, "control_allowed", false); }
  private Map<String, Object> proposalResponse(RoutineAssistantProposal proposal) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("proposal_id", proposal.getProposalId());
    result.put("state", proposal.getState());
    result.put("draft_graph_id", proposal.getDraftGraphId());
    result.put("diff", read(proposal.getDiffJson()));
    result.put("control_allowed", false);
    return result;
  }
  private Optional<BehaviorGraphDefinition> latestGraphForRun(String runId) { return graphs.findFirstByStateAndIdentityJsonContainingOrderByCreatedAtDesc("PUBLISHED", "\"run_id\":\"" + runId + "\""); }
  @SuppressWarnings("unchecked") private Map<String, Object> read(String json) { try { return mapper.readValue(json, Map.class); } catch (Exception e) { throw error(HttpStatus.INTERNAL_SERVER_ERROR, "PERSISTED_JSON_CORRUPT", "persisted assistant diff is corrupt"); } }
  @SuppressWarnings("unchecked") private static Map<String, Object> cast(Object value) { return (Map<String, Object>) value; }
  private String write(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw error(HttpStatus.UNPROCESSABLE_ENTITY, "ASSISTANT_DIFF_INVALID", "assistant payload is invalid"); } }
  private static RoutineCaptureIngestionService.RoutineCaptureException error(HttpStatus status, String code, String message) { return new RoutineCaptureIngestionService.RoutineCaptureException(status, code, message); }
}
