package com.chromascape.web.aiplayer.routine.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
    name = "routine_models",
    indexes = @Index(name = "idx_routine_models_model_id", columnList = "model_id", unique = true))
public class RoutineModel {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "model_id", nullable = false, unique = true, length = 160)
  private String modelId;

  @Column(name = "graph_id", nullable = false, length = 160)
  private String graphId;

  @Column(name = "model_hash", nullable = false, length = 71)
  private String modelHash;

  @Column(name = "identity_json", nullable = false, columnDefinition = "text")
  private String identityJson;

  @Column(name = "definition_json", nullable = false, columnDefinition = "text")
  private String definitionJson;

  @Column(nullable = false)
  private Instant createdAt = Instant.now();

  public Long getId() {
    return id;
  }

  public String getModelId() {
    return modelId;
  }

  public void setModelId(String value) {
    modelId = value;
  }

  public String getGraphId() {
    return graphId;
  }

  public void setGraphId(String value) {
    graphId = value;
  }

  public String getModelHash() {
    return modelHash;
  }

  public void setModelHash(String value) {
    modelHash = value;
  }

  public String getIdentityJson() {
    return identityJson;
  }

  public void setIdentityJson(String value) {
    identityJson = value;
  }

  public String getDefinitionJson() {
    return definitionJson;
  }

  public void setDefinitionJson(String value) {
    definitionJson = value;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
