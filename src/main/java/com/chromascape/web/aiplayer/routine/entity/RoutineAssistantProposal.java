package com.chromascape.web.aiplayer.routine.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="routine_assistant_proposals")
public class RoutineAssistantProposal {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  @Column(name="proposal_id",nullable=false,unique=true,length=160) private String proposalId;
  @Column(name="conversation_id",nullable=false,length=160) private String conversationId;
  @Lob private String diffJson;
  @Column(nullable=false,length=30) private String state="PROPOSED";
  private String decisionReason; private Instant decidedAt;
  public Long getId(){return id;} public String getProposalId(){return proposalId;} public void setProposalId(String v){proposalId=v;} public String getConversationId(){return conversationId;} public void setConversationId(String v){conversationId=v;} public String getDiffJson(){return diffJson;} public void setDiffJson(String v){diffJson=v;} public String getState(){return state;} public void setState(String v){state=v;} public String getDecisionReason(){return decisionReason;} public void setDecisionReason(String v){decisionReason=v;} public Instant getDecidedAt(){return decidedAt;} public void setDecidedAt(Instant v){decidedAt=v;}
}
