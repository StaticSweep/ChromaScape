package com.chromascape.web.scripts;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** JPA entity for storing script lifecycle events (START/STOP) in the database. */
@Entity
@Table(name = "script_events")
public class ScriptEventEntity {

  public enum EventType {
    START,
    STOP
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 20)
  @Enumerated(EnumType.STRING)
  private EventType eventType;

  @Column(nullable = false, length = 100)
  private String scriptName;

  @Column(nullable = false)
  private LocalDateTime timestamp;

  /** Default constructor required by JPA. */
  public ScriptEventEntity() {}

  /** Constructor for creating script events. */
  public ScriptEventEntity(EventType eventType, String scriptName, LocalDateTime timestamp) {
    this.eventType = eventType;
    this.scriptName = scriptName;
    this.timestamp = timestamp;
  }

  // Getters and Setters

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public EventType getEventType() {
    return eventType;
  }

  public void setEventType(EventType eventType) {
    this.eventType = eventType;
  }

  public String getScriptName() {
    return scriptName;
  }

  public void setScriptName(String scriptName) {
    this.scriptName = scriptName;
  }

  public LocalDateTime getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(LocalDateTime timestamp) {
    this.timestamp = timestamp;
  }
}
