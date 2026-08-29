package com.chromascape.web.aiplayer.routine.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity
@Table(name="routine_assistant_conversations")
public class RoutineAssistantConversation {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  @Version private Long version;
  @Column(name="conversation_id",nullable=false,unique=true,length=160) private String conversationId;
  @Column(name="run_id",nullable=false,length=160) private String runId;
  @Column(nullable=false,length=30) private String state="OPEN";
  @Column(name="next_event_id", nullable=false) private Long nextEventId=0L;
  @Column(name="generation_token", length=80) private String generationToken;
  @Column(name="generation_state", length=20) private String generationState;
  @Column(name="generation_lease_expires_at") private Instant generationLeaseExpiresAt;
  @JdbcTypeCode(SqlTypes.LONGVARCHAR) @Column(name="generation_message") private String generationMessage;
  @Column(name="generation_attempts", nullable=false) private int generationAttempts = 0;
  @Column(nullable=false) private Instant createdAt=Instant.now();
  public Long getId(){return id;} public String getConversationId(){return conversationId;} public void setConversationId(String v){conversationId=v;} public String getRunId(){return runId;} public void setRunId(String v){runId=v;} public String getState(){return state;} public void setState(String v){state=v;} public Long getNextEventId(){return nextEventId;} public void setNextEventId(Long v){nextEventId=v;} public String getGenerationToken(){return generationToken;} public void setGenerationToken(String v){generationToken=v;} public String getGenerationState(){return generationState;} public void setGenerationState(String v){generationState=v;} public Instant getGenerationLeaseExpiresAt(){return generationLeaseExpiresAt;} public void setGenerationLeaseExpiresAt(Instant v){generationLeaseExpiresAt=v;} public String getGenerationMessage(){return generationMessage;} public void setGenerationMessage(String v){generationMessage=v;} public int getGenerationAttempts(){return generationAttempts;} public void setGenerationAttempts(int v){generationAttempts=v;} public Instant getCreatedAt(){return createdAt;}
}
