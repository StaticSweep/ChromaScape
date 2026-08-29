package com.chromascape.web.aiplayer.routine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "routine_assistant_proposals")
public class RoutineAssistantProposal {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Version private Long version;
  @Column(name = "proposal_id", nullable = false, unique = true, length = 160) private String proposalId;
  @Column(name = "conversation_id", nullable = false, length = 160) private String conversationId;
  @Column(columnDefinition = "text") private String diffJson;
  @Column(nullable = false, length = 30) private String state = "PROPOSED";
  @Column(name = "draft_graph_id", length = 160, unique = true) private String draftGraphId;
  @Column(name = "accepted_event_id") private Long acceptedEventId;
  private String decisionReason;
  private Instant decidedAt;

  public Long getId() { return id; }
  public Long getVersion() { return version; }
  public String getProposalId() { return proposalId; }
  public void setProposalId(String value) { proposalId = value; }
  public String getConversationId() { return conversationId; }
  public void setConversationId(String value) { conversationId = value; }
  public String getDiffJson() { return diffJson; }
  public void setDiffJson(String value) { diffJson = value; }
  public String getState() { return state; }
  public void setState(String value) { state = value; }
  public String getDraftGraphId() { return draftGraphId; }
  public void setDraftGraphId(String value) { draftGraphId = value; }
  public Long getAcceptedEventId() { return acceptedEventId; }
  public void setAcceptedEventId(Long value) { acceptedEventId = value; }
  public String getDecisionReason() { return decisionReason; }
  public void setDecisionReason(String value) { decisionReason = value; }
  public Instant getDecidedAt() { return decidedAt; }
  public void setDecidedAt(Instant value) { decidedAt = value; }
}
