package com.chromascape.web.aiplayer.routine.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity
@Table(name="routine_assistant_events", indexes=@Index(name="idx_routine_assistant_events_cursor",columnList="conversation_id,event_id",unique=true))
public class RoutineAssistantEvent {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  @Column(name="conversation_id",nullable=false,length=160) private String conversationId;
  @Column(name="event_id",nullable=false) private Long eventId;
  @Column(nullable=false,length=60) private String type;
  @JdbcTypeCode(SqlTypes.LONGVARCHAR) private String dataJson;
  @Column(nullable=false) private Instant createdAt=Instant.now();
  public Long getId(){return id;} public String getConversationId(){return conversationId;} public void setConversationId(String v){conversationId=v;} public Long getEventId(){return eventId;} public void setEventId(Long v){eventId=v;} public String getType(){return type;} public void setType(String v){type=v;} public String getDataJson(){return dataJson;} public void setDataJson(String v){dataJson=v;} public Instant getCreatedAt(){return createdAt;}
}
