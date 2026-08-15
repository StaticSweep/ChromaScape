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
  private final ExecutorService generationExecutor = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(16), new ThreadPoolExecutor.AbortPolicy());
  private final Map<String, Future<?>> activeGenerations = new ConcurrentHashMap<>();
  private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(1);

  public RoutineAssistantService(RoutineAssistantProvider provider, ObjectMapper mapper,
      RoutineAssistantConversationRepository conversations, RoutineAssistantEventRepository events,
      RoutineAssistantProposalRepository proposals, BehaviorGraphDefinitionRepository graphs,
      CurrentSliceRunRepository runs, BehaviorGraphService graphService) {
    this.provider = provider; this.mapper = mapper; this.conversations = conversations; this.events = events; this.proposals = proposals; this.graphs = graphs; this.runs = runs; this.graphService = graphService;
  }

  @Transactional
  public Map<String, Object> create(String runId) {
    if (runId == null || runId.isBlank()) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTITY_FIELD_MISSING", "runId is required");
    if (runs.findByRunId(runId).isEmpty()) throw error(HttpStatus.NOT_FOUND, "NOT_FOUND", "run not found");
    RoutineAssistantConversation conversation = new RoutineAssistantConversation();
    conversation.setConversationId("conv_" + UUID.randomUUID().toString().replace("-", "")); conversation.setRunId(runId); conversation.setState("OPEN"); conversations.save(conversation);
    event(conversation, "conversation.started", Map.of("run_id", runId, "control_allowed", false));
    return response(conversation);
  }

  @Transactional
  public Map<String, Object> message(String id, RoutineApiDtos.AssistantMessageRequest request) {
    RoutineAssistantConversation conversation = conversation(id);
    if (!"OPEN".equals(conversation.getState())) throw error(HttpStatus.CONFLICT, "CONVERSATION_STOPPED", "conversation is not accepting messages");
    if (request == null || request.message() == null || request.message().isBlank()) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "MESSAGE_REQUIRED", "message is required");
    event(conversation, "message.accepted", Map.of("message", request.message(), "control_allowed", false));
    try {
      Future<?> future = generationExecutor.submit(() -> generateProposal(id, request.message()));
      activeGenerations.put(id, future);
    } catch (java.util.concurrent.RejectedExecutionException e) {
      event(conversation, "generation.failed", Map.of("code", "ASSISTANT_QUEUE_FULL", "control_allowed", false));
      throw error(HttpStatus.CONFLICT, "ASSISTANT_QUEUE_FULL", "assistant generation queue is full");
    }
    return Map.of("conversation_id", id, "status", "QUEUED", "control_allowed", false);
  }

  private void generateProposal(String id, String message) {
    RoutineAssistantConversation conversation;
    try {
      conversation = conversation(id);
      Map<String, Object> context = new LinkedHashMap<>(); context.put("run_id", conversation.getRunId());
      latestGraphForRun(conversation.getRunId()).ifPresent(graph -> {
        context.put("graph_id", graph.getGraphId()); context.put("graph_hash", graph.getGraphHash());
      });
      event(conversation, "analysis.delta", Map.of("text", "Reviewing immutable capture evidence and graph semantics.", "control_allowed", false));
      if (!"OPEN".equals(conversation.getState())) throw error(HttpStatus.CONFLICT, "CONVERSATION_STOPPED", "generation was stopped");
      Map<String, Object> proposed = provider.propose(id, message, context);
      Map<String, Object> diff = proposed.get("diff") instanceof Map ? cast(proposed.get("diff")) : Map.of();
      validateDiff(diff);
      RoutineAssistantProposal proposal = new RoutineAssistantProposal();
      proposal.setProposalId("proposal_" + UUID.randomUUID().toString().replace("-", "")); proposal.setConversationId(id); proposal.setDiffJson(write(diff)); proposal.setState("PROPOSED"); proposals.save(proposal);
      Map<String, Object> data = new LinkedHashMap<>(); data.put("proposal_id", proposal.getProposalId()); data.put("diff", diff); data.put("status", "PROPOSED"); data.put("control_allowed", false);
      event(conversation, "proposal.created", data); event(conversation, "stream.complete", Map.of("control_allowed", false));
    } catch (Exception e) {
      try { conversation = conversation(id); event(conversation, "generation.failed", Map.of("code", "ASSISTANT_GENERATION_FAILED", "message", e.getMessage() == null ? "generation failed" : e.getMessage(), "control_allowed", false)); event(conversation, "stream.complete", Map.of("control_allowed", false)); }
      catch (Exception ignored) { }
    } finally { activeGenerations.remove(id); }
  }

  @Transactional
  public Map<String, Object> stop(String id) {
    RoutineAssistantConversation conversation = conversation(id);
    Future<?> active = activeGenerations.remove(id);
    if (active != null) active.cancel(true);
    if (!"STOPPED".equals(conversation.getState())) { conversation.setState("STOPPED"); conversations.save(conversation); event(conversation, "generation.stopped", Map.of("control_allowed", false)); }
    return response(conversation);
  }

  public void stream(String id, long after, SseEmitter emitter) {
    conversation(id);
    try {
      AtomicLong cursor = new AtomicLong(Math.max(0, after));
      for (RoutineAssistantEvent event : events.findByConversationIdAndEventIdGreaterThanOrderByEventIdAsc(id, cursor.get())) { send(emitter, event); cursor.set(event.getEventId()); }
      ScheduledFuture<?> poll = heartbeatExecutor.scheduleAtFixedRate(() -> {
        try { for (RoutineAssistantEvent event : events.findByConversationIdAndEventIdGreaterThanOrderByEventIdAsc(id, cursor.get())) { send(emitter, event); cursor.set(event.getEventId()); } }
        catch (Exception e) { emitter.complete(); }
      }, 1, 1, TimeUnit.SECONDS);
      ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(() -> {
        try { emitter.send(SseEmitter.event().comment("heartbeat")); }
        catch (Exception e) { emitter.complete(); }
      }, 15, 15, TimeUnit.SECONDS);
      emitter.onCompletion(() -> { poll.cancel(false); heartbeat.cancel(false); });
      emitter.onTimeout(() -> { poll.cancel(false); heartbeat.cancel(false); });
    } catch (Exception e) { emitter.completeWithError(e); }
  }

  private void send(SseEmitter emitter, RoutineAssistantEvent event) throws IOException {
    emitter.send(SseEmitter.event().id(String.valueOf(event.getEventId())).name(event.getType()).data(event.getDataJson()));
  }

  @Transactional
  public Map<String, Object> accept(String proposalId) {
    RoutineAssistantProposal proposal = proposals.findByProposalId(proposalId).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND", "proposal not found"));
    if (!"PROPOSED".equals(proposal.getState())) throw error(HttpStatus.CONFLICT, "PROPOSAL_ALREADY_DECIDED", "proposal is no longer pending");
    Map<String, Object> diff = read(proposal.getDiffJson()); validateDiff(diff);
    String baseGraphId = String.valueOf(diff.get("base_graph_id"));
    if (baseGraphId.isBlank() || "null".equals(baseGraphId)) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "GRAPH_BASE_REQUIRED", "proposal has no graph base");
    Map<String, Object> draft = graphService.createAssistantDraft(baseGraphId, diff);
    proposal.setState("ACCEPTED"); proposal.setDecisionReason("human accepted"); proposal.setDecidedAt(Instant.now()); proposals.save(proposal);
    RoutineAssistantConversation conversation = conversation(proposal.getConversationId()); event(conversation, "proposal.accepted", Map.of("draft_created", true, "graph_id", draft.get("graph_id"), "control_allowed", false));
    return Map.of("proposal_id", proposalId, "status", "DRAFT_CREATED", "graph_id", draft.get("graph_id"), "published", false, "approved_patch", false, "control_allowed", false);
  }

  @Transactional
  public Map<String, Object> reject(String proposalId, String reason) {
    RoutineAssistantProposal proposal = proposals.findByProposalId(proposalId).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND", "proposal not found"));
    if (!"PROPOSED".equals(proposal.getState())) throw error(HttpStatus.CONFLICT, "PROPOSAL_ALREADY_DECIDED", "proposal is no longer pending");
    proposal.setState("REJECTED"); proposal.setDecisionReason(reason == null ? "" : reason); proposal.setDecidedAt(Instant.now()); proposals.save(proposal);
    RoutineAssistantConversation conversation = conversation(proposal.getConversationId()); event(conversation, "proposal.rejected", Map.of("reason", reason == null ? "" : reason, "control_allowed", false));
    return Map.of("proposal_id", proposalId, "status", "REJECTED", "control_allowed", false);
  }

  private void validateDiff(Map<String, Object> diff) {
    if (!"behavior-graph-diff/v1".equals(String.valueOf(diff.get("schema")))) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "ASSISTANT_DIFF_INVALID", "unsupported graph diff schema");
    if (!(diff.get("base_graph_id") instanceof String) || !String.valueOf(diff.get("base_graph_id")).matches("graph_[0-9a-f]{24}")) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "ASSISTANT_DIFF_INVALID", "base_graph_id is invalid");
    if (!(diff.get("base_graph_hash") instanceof String) || !String.valueOf(diff.get("base_graph_hash")).matches("sha256:[0-9a-f]{64}")) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "ASSISTANT_DIFF_INVALID", "base_graph_hash is invalid");
    if (!Boolean.FALSE.equals(diff.get("control_allowed"))) throw error(HttpStatus.FORBIDDEN, "ASSISTANT_AUTHORITY_FORBIDDEN", "control_allowed must remain false");
    Object authority = diff.get("authority");
    if (!(authority instanceof Map<?, ?> values)) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "ASSISTANT_DIFF_INVALID", "authority is required");
    for (String key : List.of("can_write_source", "can_approve_patch", "can_publish_graph", "can_activate_model", "can_dispatch_input")) if (!Boolean.FALSE.equals(values.get(key))) throw error(HttpStatus.FORBIDDEN, "ASSISTANT_AUTHORITY_FORBIDDEN", "assistant authority must remain false");
    Object operations = diff.get("operations"); if (!(operations instanceof List) || ((List<?>) operations).size() > 255) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "ASSISTANT_DIFF_INVALID", "graph diff operations are invalid");
  }

  private synchronized void event(RoutineAssistantConversation conversation, String type, Map<String, Object> data) {
    long next = conversation.getNextEventId() == null ? 1L : conversation.getNextEventId() + 1L;
    conversation.setNextEventId(next); conversations.save(conversation);
    RoutineAssistantEvent event = new RoutineAssistantEvent(); event.setConversationId(conversation.getConversationId()); event.setEventId(next); event.setType(type); event.setDataJson(write(data)); events.save(event);
  }
  private RoutineAssistantConversation conversation(String id) { return conversations.findByConversationId(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND", "conversation not found")); }
  private Map<String, Object> response(RoutineAssistantConversation c) { long next = (c.getNextEventId() == null ? 0L : c.getNextEventId()) + 1L; return Map.of("conversation_id", c.getConversationId(), "run_id", c.getRunId(), "state", c.getState(), "next_event_id", next, "control_allowed", false); }
  private Optional<BehaviorGraphDefinition> latestGraphForRun(String runId) { return graphs.findFirstByStateAndIdentityJsonContainingOrderByCreatedAtDesc("PUBLISHED", "\"run_id\":\"" + runId + "\""); }
  @SuppressWarnings("unchecked") private Map<String, Object> read(String json) { try { return mapper.readValue(json, Map.class); } catch (Exception e) { throw error(HttpStatus.INTERNAL_SERVER_ERROR, "PERSISTED_JSON_CORRUPT", "persisted assistant diff is corrupt"); } }
  @SuppressWarnings("unchecked") private static Map<String, Object> cast(Object value) { return (Map<String, Object>) value; }
  private String write(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw error(HttpStatus.UNPROCESSABLE_ENTITY, "ASSISTANT_DIFF_INVALID", "assistant payload is invalid"); } }
  private static RoutineCaptureIngestionService.RoutineCaptureException error(HttpStatus status, String code, String message) { return new RoutineCaptureIngestionService.RoutineCaptureException(status, code, message); }
}
