package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.routine.entity.RoutineAssistantConversation;
import com.chromascape.web.aiplayer.routine.entity.RoutineAssistantEvent;
import com.chromascape.web.aiplayer.routine.entity.RoutineAssistantProposal;
import com.chromascape.web.aiplayer.routine.repository.RoutineAssistantConversationRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineAssistantEventRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineAssistantProposalRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sole transactional writer for assistant conversation state, cursor allocation, and events. */
@Service
public class RoutineAssistantConversationStore {
  private static final Duration GENERATION_LEASE = Duration.ofMinutes(5);
  private final RoutineAssistantConversationRepository conversations;
  private final RoutineAssistantEventRepository events;
  private final RoutineAssistantProposalRepository proposals;

  public RoutineAssistantConversationStore(
      RoutineAssistantConversationRepository conversations,
      RoutineAssistantEventRepository events,
      RoutineAssistantProposalRepository proposals) {
    this.conversations = conversations;
    this.events = events;
    this.proposals = proposals;
  }

  @Transactional
  public String beginGeneration(String id, String message, String dataJson) {
    RoutineAssistantConversation conversation = locked(id);
    if (!"OPEN".equals(conversation.getState())) throw conflict("CONVERSATION_STOPPED", "conversation is not accepting messages");
    if (conversation.getGenerationToken() != null) throw conflict("GENERATION_ACTIVE", "only one active generation per conversation");
    String token = UUID.randomUUID().toString();
    conversation.setGenerationToken(token); conversation.setGenerationState("GENERATING");
    conversation.setGenerationLeaseExpiresAt(Instant.now().plus(GENERATION_LEASE)); conversation.setGenerationMessage(message);
    conversation.setGenerationAttempts(conversation.getGenerationAttempts() + 1); conversations.save(conversation);
    append(conversation, "message.accepted", dataJson);
    return token;
  }

  /** Atomically validates the provider token, persists the proposal, emits both terminal events, and clears the lease. */
  @Transactional
  public boolean commitProposalIfCurrentToken(
      String id, String token, RoutineAssistantProposal proposal, String proposalEventJson) {
    RoutineAssistantConversation conversation = currentGeneration(id, token);
    if (conversation == null) return false;
    proposals.save(proposal);
    append(conversation, "proposal.created", proposalEventJson);
    append(conversation, "stream.complete", "{\"control_allowed\":false}");
    clearGeneration(conversation);
    return true;
  }

  @Transactional
  public boolean failGenerationIfCurrentToken(String id, String token, String failureJson) {
    RoutineAssistantConversation conversation = currentGeneration(id, token);
    if (conversation == null) return false;
    append(conversation, "generation.failed", failureJson);
    append(conversation, "stream.complete", "{\"control_allowed\":false}");
    clearGeneration(conversation);
    return true;
  }

  @Transactional
  public boolean completeGenerationIfCurrentToken(String id, String token) {
    RoutineAssistantConversation conversation = currentGeneration(id, token);
    if (conversation == null) return false;
    clearGeneration(conversation);
    return true;
  }

  /** Appends an operator event using the same locked cursor allocator as provider events. */
  @Transactional
  public long appendOperatorEvent(String id, String type, String dataJson) {
    return append(locked(id), type, dataJson);
  }

  /** Recovery locks each candidate again before changing it, preventing stale scanner writes. */
  @Transactional
  public int recoverExpiredLeases() {
    int recovered = 0;
    Instant now = Instant.now();
    for (RoutineAssistantConversation candidate : conversations.findExpiredGenerations(now)) {
      RoutineAssistantConversation conversation = locked(candidate.getConversationId());
      if (conversation.getGenerationToken() == null || !"GENERATING".equals(conversation.getGenerationState())
          || conversation.getGenerationLeaseExpiresAt() == null || conversation.getGenerationLeaseExpiresAt().isAfter(now)) continue;
      append(conversation, "generation.failed", "{\"code\":\"GENERATION_LEASE_EXPIRED\",\"message\":\"generation lease expired before completion\",\"control_allowed\":false}");
      append(conversation, "stream.complete", "{\"control_allowed\":false}");
      clearGeneration(conversation); recovered++;
    }
    return recovered;
  }

  /**
   * Specification-named recovery entry point. The lease scanner keeps the plural implementation
   * above for compatibility with existing callers, while this method makes the single-generation
   * recovery contract explicit for scheduled and test callers.
   */
  @Transactional
  public int recoverExpiredGeneration() {
    return recoverExpiredLeases();
  }

  @Transactional
  public long appendEvent(String id, String token, String type, String dataJson) {
    RoutineAssistantConversation conversation = currentGeneration(id, token);
    return conversation == null ? -1L : append(conversation, type, dataJson);
  }

  /** Specification-named alias for token-gated provider event append. */
  @Transactional
  public long appendGenerationEvent(String id, String token, String type, String dataJson) {
    return appendEvent(id, token, type, dataJson);
  }

  @Transactional
  public boolean cancelGeneration(String id, String token) {
    RoutineAssistantConversation conversation = locked(id);
    if (token == null || !token.equals(conversation.getGenerationToken())) return false;
    clearGeneration(conversation); conversation.setGenerationState("CANCELLED"); conversations.save(conversation); return true;
  }

  @Transactional
  public void stop(String id) {
    RoutineAssistantConversation conversation = locked(id);
    clearGeneration(conversation);
    if (!"STOPPED".equals(conversation.getState())) {
      conversation.setState("STOPPED"); append(conversation, "generation.stopped", "{\"control_allowed\":false}");
    }
    conversations.save(conversation);
  }

  @Transactional(readOnly = true)
  public long nextEventId(String id) { RoutineAssistantConversation c = locked(id); return c.getNextEventId() == null ? 0L : c.getNextEventId(); }

  private RoutineAssistantConversation currentGeneration(String id, String token) {
    RoutineAssistantConversation conversation = locked(id);
    if (token == null || !token.equals(conversation.getGenerationToken()) || !"GENERATING".equals(conversation.getGenerationState())
        || !"OPEN".equals(conversation.getState()) || conversation.getGenerationLeaseExpiresAt() == null
        || conversation.getGenerationLeaseExpiresAt().isBefore(Instant.now())) return null;
    return conversation;
  }

  private void clearGeneration(RoutineAssistantConversation conversation) {
    conversation.setGenerationToken(null); conversation.setGenerationState(null); conversation.setGenerationLeaseExpiresAt(null); conversations.save(conversation);
  }

  private long append(RoutineAssistantConversation conversation, String type, String dataJson) {
    long next = conversation.getNextEventId() == null ? 1L : conversation.getNextEventId() + 1L;
    conversation.setNextEventId(next); conversations.save(conversation);
    RoutineAssistantEvent event = new RoutineAssistantEvent(); event.setConversationId(conversation.getConversationId()); event.setEventId(next); event.setType(type); event.setDataJson(dataJson); events.save(event); return next;
  }

  private RoutineAssistantConversation locked(String id) { return conversations.findWithLockByConversationId(id).orElseThrow(() -> conflict("NOT_FOUND", "conversation not found")); }
  private static RoutineCaptureIngestionService.RoutineCaptureException conflict(String code, String message) { return new RoutineCaptureIngestionService.RoutineCaptureException(HttpStatus.CONFLICT, code, message); }
}
