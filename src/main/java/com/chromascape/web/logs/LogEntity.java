package com.chromascape.web.logs;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** JPA entity for storing log entries in the database. */
@Entity
@Table(name = "logs")
public class LogEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private LocalDateTime timestamp;

  @Column(nullable = false, length = 20)
  private String level;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String message;

  @Column(length = 100)
  private String source;

  @Column(length = 100)
  private String scriptName;

  /** Default constructor required by JPA. */
  public LogEntity() {}

  /** Constructor for creating log entries. */
  public LogEntity(
      LocalDateTime timestamp, String level, String message, String source, String scriptName) {
    this.timestamp = timestamp;
    this.level = level;
    this.message = message;
    this.source = source;
    this.scriptName = scriptName;
  }

  // Getters and Setters

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public LocalDateTime getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(LocalDateTime timestamp) {
    this.timestamp = timestamp;
  }

  public String getLevel() {
    return level;
  }

  public void setLevel(String level) {
    this.level = level;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public String getScriptName() {
    return scriptName;
  }

  public void setScriptName(String scriptName) {
    this.scriptName = scriptName;
  }
}
