package com.chromascape.web.aiplayer.routine.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "behavior_graph_drafts")
public class BehaviorGraphDraft {
  @Id private String draftId;

  @Column(nullable = false, unique = true)
  private String graphId;

  private String baseGraphId;

  @Column(nullable = false)
  private String taskKey;

  @Column(nullable = false)
  private String profileId;

  @Column(nullable = false)
  private String profileVersion;

  @Column(nullable = false)
  private long revision;

  @Column(nullable = false)
  private String definitionSchema;

  @Column(nullable = false, columnDefinition = "text")
  private String definitionJson;

  @Column(nullable = false, length = 71)
  private String graphHash;

  @Column(nullable = false)
  private String layoutSchema;

  @Column(nullable = false, columnDefinition = "text")
  private String layoutJson;

  @Column(nullable = false, length = 71)
  private String layoutHash;

  @Column(nullable = false)
  private String state = "DRAFT";

  @Column(nullable = false)
  private Instant createdAt = Instant.now();

  @Column(nullable = false)
  private Instant updatedAt = Instant.now();

  private String createdBy;
  private String updatedBy;

  public String getDraftId() {
    return draftId;
  }

  public void setDraftId(String v) {
    draftId = v;
  }

  public String getGraphId() {
    return graphId;
  }

  public void setGraphId(String v) {
    graphId = v;
  }

  public long getRevision() {
    return revision;
  }

  public void setRevision(long v) {
    revision = v;
  }

  public String getDefinitionJson() {
    return definitionJson;
  }

  public void setDefinitionJson(String v) {
    definitionJson = v;
  }

  public String getLayoutJson() {
    return layoutJson;
  }

  public void setLayoutJson(String v) {
    layoutJson = v;
  }

  public String getGraphHash() {
    return graphHash;
  }

  public void setGraphHash(String v) {
    graphHash = v;
  }

  public String getLayoutHash() {
    return layoutHash;
  }

  public void setLayoutHash(String v) {
    layoutHash = v;
  }

  public String getState() {
    return state;
  }

  public void setState(String v) {
    state = v;
  }

  public String getTaskKey() {
    return taskKey;
  }

  public void setTaskKey(String v) {
    taskKey = v;
  }

  public String getProfileId() {
    return profileId;
  }

  public void setProfileId(String v) {
    profileId = v;
  }

  public String getProfileVersion() {
    return profileVersion;
  }

  public void setProfileVersion(String v) {
    profileVersion = v;
  }

  public void setDefinitionSchema(String v) {
    definitionSchema = v;
  }

  public void setLayoutSchema(String v) {
    layoutSchema = v;
  }

  public void touch() {
    updatedAt = Instant.now();
  }
}
