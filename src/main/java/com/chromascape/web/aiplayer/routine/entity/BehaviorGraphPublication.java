package com.chromascape.web.aiplayer.routine.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
    name = "behavior_graph_publications",
    uniqueConstraints = {
      @UniqueConstraint(columnNames = {"graphId", "graphVersion"}),
      @UniqueConstraint(columnNames = {"sourceDraftId", "sourceDraftRevision"})
    })
public class BehaviorGraphPublication {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long publicationId;

  @Column(nullable = false)
  private String graphId;

  @Column(nullable = false)
  private long graphVersion;

  @Column(nullable = false)
  private String sourceDraftId;

  @Column(nullable = false)
  private long sourceDraftRevision;

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
  private Instant publishedAt = Instant.now();

  private String publishedBy;

  public void setGraphId(String v) {
    graphId = v;
  }

  public void setGraphVersion(long v) {
    graphVersion = v;
  }

  public void setSourceDraftId(String v) {
    sourceDraftId = v;
  }

  public void setSourceDraftRevision(long v) {
    sourceDraftRevision = v;
  }

  public void setDefinitionSchema(String v) {
    definitionSchema = v;
  }

  public void setDefinitionJson(String v) {
    definitionJson = v;
  }

  public void setGraphHash(String v) {
    graphHash = v;
  }

  public void setLayoutSchema(String v) {
    layoutSchema = v;
  }

  public void setLayoutJson(String v) {
    layoutJson = v;
  }

  public void setLayoutHash(String v) {
    layoutHash = v;
  }

  public long getGraphVersion() {
    return graphVersion;
  }

  public Long getPublicationId() {
    return publicationId;
  }

  public String getGraphId() {
    return graphId;
  }

  public String getSourceDraftId() {
    return sourceDraftId;
  }

  public long getSourceDraftRevision() {
    return sourceDraftRevision;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }

  public String getGraphHash() {
    return graphHash;
  }

  public String getLayoutHash() {
    return layoutHash;
  }
}
