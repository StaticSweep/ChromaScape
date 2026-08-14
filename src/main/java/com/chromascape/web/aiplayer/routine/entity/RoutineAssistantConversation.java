package com.chromascape.web.aiplayer.routine.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="routine_assistant_conversations")
public class RoutineAssistantConversation {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  @Column(name="conversation_id",nullable=false,unique=true,length=160) private String conversationId;
  @Column(name="run_id",nullable=false,length=160) private String runId;
  @Column(nullable=false,length=30) private String state="OPEN";
  @Column(nullable=false) private Instant createdAt=Instant.now();
  public Long getId(){return id;} public String getConversationId(){return conversationId;} public void setConversationId(String v){conversationId=v;} public String getRunId(){return runId;} public void setRunId(String v){runId=v;} public String getState(){return state;} public void setState(String v){state=v;} public Instant getCreatedAt(){return createdAt;}
}
