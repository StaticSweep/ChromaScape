package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.routine.dto.RoutineApiDtos;
import com.chromascape.web.aiplayer.routine.entity.BehaviorGraphDefinition;
import com.chromascape.web.aiplayer.routine.entity.RoutineAssistantConversation;
import com.chromascape.web.aiplayer.routine.entity.RoutineAssistantEvent;
import com.chromascape.web.aiplayer.routine.entity.RoutineAssistantProposal;
import com.chromascape.web.aiplayer.routine.repository.BehaviorGraphDefinitionRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineAssistantConversationRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineAssistantEventRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineAssistantProposalRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoutineAssistantService {
  private final RoutineAssistantProvider provider;
  private final ObjectMapper mapper;
  private final RoutineAssistantConversationRepository conversations;
  private final RoutineAssistantEventRepository events;
  private final RoutineAssistantProposalRepository proposals;
  private final BehaviorGraphDefinitionRepository graphs;
  private final BehaviorGraphService graphService;

  public RoutineAssistantService(RoutineAssistantProvider provider, ObjectMapper mapper,
      RoutineAssistantConversationRepository conversations, RoutineAssistantEventRepository events,
      RoutineAssistantProposalRepository proposals, BehaviorGraphDefinitionRepository graphs,
      BehaviorGraphService graphService) {
    this.provider = provider; this.mapper = mapper; this.conversations = conversations; this.events = events; this.proposals = proposals; this.graphs = graphs; this.graphService = graphService;
  }

  @Transactional
  public Map<String, Object> create(String runId) {
    if (runId == null || runId.isBlank()) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTITY_FIELD_MISSING", "runId is required");
    RoutineAssistantConversation conversation = new RoutineAssistantConversation();
    conversation.setConversationId("conv_" + UUID.randomUUID().toString().replace("-", "")); conversation.setRunId(runId); conversation.setState("OPEN"); conversations.save(conversation);
    event(conversation, "conversation.started", Map.of("run_id", runId));
    return response(conversation);
  }

  @Transactional
  public Map<String, Object> message(String id, RoutineApiDtos.AssistantMessageRequest request) {
    RoutineAssistantConversation conversation = conversation(id);
    if (!"OPEN".equals(conversation.getState())) throw error(HttpStatus.CONFLICT, "CONVERSATION_STOPPED", "conversation is not accepting messages");
    if (request == null || request.message() == null || request.message().isBlank()) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "MESSAGE_REQUIRED", "message is required");
    event(conversation, "message.accepted", Map.of("message", request.message()));
    Map<String, Object> context = new LinkedHashMap<>(); context.put("run_id", conversation.getRunId());
    latestGraphForRun(conversation.getRunId()).ifPresent(graph -> context.put("graph_id", graph.getGraphId()));
    Map<String, Object> proposed = provider.propose(conversation.getConversationId(), request.message(), context);
    Map<String, Object> diff = proposed.get("diff") instanceof Map ? (Map<String, Object>) proposed.get("diff") : Map.of();
    validateDiff(diff);
    RoutineAssistantProposal proposal = new RoutineAssistantProposal();
    proposal.setProposalId("proposal_" + UUID.randomUUID().toString().replace("-", "")); proposal.setConversationId(id); proposal.setDiffJson(write(diff)); proposal.setState("PROPOSED"); proposals.save(proposal);
    Map<String, Object> proposalData = new LinkedHashMap<>(); proposalData.put("proposal_id", proposal.getProposalId()); proposalData.put("diff", diff); proposalData.put("status", "PROPOSED"); proposalData.put("control_allowed", false);
    event(conversation, "proposal.created", proposalData); event(conversation, "stream.complete", Map.of());
    return proposalData;
  }

  @Transactional
  public Map<String, Object> stop(String id) {
    RoutineAssistantConversation conversation = conversation(id);
    if (!"STOPPED".equals(conversation.getState())) { conversation.setState("STOPPED"); conversations.save(conversation); event(conversation, "generation.stopped", Map.of()); }
    return response(conversation);
  }

  @Transactional(readOnly = true)
  public String sse(String id, long after) {
    conversation(id);
    StringBuilder output = new StringBuilder();
    for (RoutineAssistantEvent event : events.findByConversationIdAndEventIdGreaterThanOrderByEventIdAsc(id, Math.max(0, after))) {
      output.append("id: ").append(event.getEventId()).append('\n').append("event: ").append(event.getType()).append('\n').append("data: ").append(event.getDataJson()).append("\n\n");
    }
    return output.toString();
  }

  @Transactional
  public Map<String, Object> accept(String proposalId) {
    RoutineAssistantProposal proposal = proposals.findByProposalId(proposalId).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND", "proposal not found"));
    if (!"PROPOSED".equals(proposal.getState())) throw error(HttpStatus.CONFLICT, "PROPOSAL_ALREADY_DECIDED", "proposal is no longer pending");
    Map<String, Object> diff = read(proposal.getDiffJson());
    validateDiff(diff);
    String baseGraphId = String.valueOf(diff.get("base_graph_id"));
    if (baseGraphId == null || "null".equals(baseGraphId) || baseGraphId.isBlank()) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "GRAPH_BASE_REQUIRED", "proposal has no graph base");
    Map<String, Object> draft = graphService.createAssistantDraft(baseGraphId, diff);
    proposal.setState("ACCEPTED"); proposal.setDecisionReason("human accepted"); proposal.setDecidedAt(Instant.now()); proposals.save(proposal);
    RoutineAssistantConversation conversation = conversation(proposal.getConversationId()); event(conversation, "proposal.accepted", Map.of("draft_created", true, "graph_id", draft.get("graph_id")));
    return Map.of("proposal_id", proposalId, "status", "DRAFT_CREATED", "graph_id", draft.get("graph_id"), "published", false, "approved_patch", false, "control_allowed", false);
  }

  @Transactional
  public Map<String, Object> reject(String proposalId, String reason) {
    RoutineAssistantProposal proposal = proposals.findByProposalId(proposalId).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND", "proposal not found"));
    if (!"PROPOSED".equals(proposal.getState())) throw error(HttpStatus.CONFLICT, "PROPOSAL_ALREADY_DECIDED", "proposal is no longer pending");
    proposal.setState("REJECTED"); proposal.setDecisionReason(reason == null ? "" : reason); proposal.setDecidedAt(Instant.now()); proposals.save(proposal);
    RoutineAssistantConversation conversation = conversation(proposal.getConversationId()); event(conversation, "proposal.rejected", Map.of("reason", reason == null ? "" : reason));
    return Map.of("proposal_id", proposalId, "status", "REJECTED", "control_allowed", false);
  }

  private void validateDiff(Map<String, Object> diff) {
    if (!"behavior-graph-diff/v1".equals(String.valueOf(diff.get("schema")))) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "ASSISTANT_DIFF_INVALID", "unsupported graph diff schema");
    Object operations = diff.get("operations"); if (!(operations instanceof List) || ((List<?>) operations).size() > 255) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "ASSISTANT_DIFF_INVALID", "graph diff operations are invalid");
    Object authority = diff.get("authority"); if (!(authority instanceof Map)) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "ASSISTANT_DIFF_INVALID", "assistant authority declaration is required");
    for (String key : List.of("can_write_source", "can_approve_patch", "can_publish_graph", "can_activate_model", "can_dispatch_input")) if (!Boolean.FALSE.equals(((Map<?, ?>) authority).get(key))) throw error(HttpStatus.FORBIDDEN, "ASSISTANT_AUTHORITY_FORBIDDEN", "assistant authority must remain false");
  }

  private void event(RoutineAssistantConversation conversation, String type, Map<String, Object> data) {
    long next = events.findTopByConversationIdOrderByEventIdDesc(conversation.getConversationId()).map(RoutineAssistantEvent::getEventId).orElse(0L) + 1;
    RoutineAssistantEvent event = new RoutineAssistantEvent(); event.setConversationId(conversation.getConversationId()); event.setEventId(next); event.setType(type); event.setDataJson(write(data)); events.save(event);
  }
  private RoutineAssistantConversation conversation(String id) { return conversations.findByConversationId(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND", "conversation not found")); }
  private Map<String, Object> response(RoutineAssistantConversation c) { long next = events.findTopByConversationIdOrderByEventIdDesc(c.getConversationId()).map(RoutineAssistantEvent::getEventId).orElse(0L) + 1; return Map.of("conversation_id", c.getConversationId(), "run_id", c.getRunId(), "state", c.getState(), "next_event_id", next); }
  private java.util.Optional<BehaviorGraphDefinition> latestGraphForRun(String runId) { return graphs.findFirstByStateAndIdentityJsonContainingOrderByCreatedAtDesc("PUBLISHED", "\"run_id\":\"" + runId + "\""); }
  @SuppressWarnings("unchecked") private Map<String, Object> read(String json) { try { return mapper.readValue(json, Map.class); } catch (Exception e) { throw error(HttpStatus.UNPROCESSABLE_ENTITY, "ASSISTANT_DIFF_INVALID", "persisted assistant diff is invalid"); } }
  private String write(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw error(HttpStatus.UNPROCESSABLE_ENTITY, "ASSISTANT_DIFF_INVALID", "assistant payload is invalid"); } }
  private static RoutineCaptureIngestionService.RoutineCaptureException error(HttpStatus status, String code, String message) { return new RoutineCaptureIngestionService.RoutineCaptureException(status, code, message); }
}
