package com.chromascape.web.aiplayer.routine.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="behavior_graph_definitions", indexes=@Index(name="idx_behavior_graph_definitions_graph_id", columnList="graph_id", unique=true))
public class BehaviorGraphDefinition {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  @Column(name="graph_id",nullable=false,unique=true,length=160) private String graphId;
  @Column(name="graph_hash",nullable=false,length=71) private String graphHash;
  @Column(name="identity_json",nullable=false, columnDefinition = "text") private String identityJson;
  @Column(name="definition_json",nullable=false, columnDefinition = "text") private String definitionJson;
  @Column(name="assistant_diff_json", columnDefinition = "text") private String assistantDiffJson;
  @Column(name="supersedes_graph_id", length=160) private String supersedesGraphId;
  @Column(nullable=false,length=20) private String state="DRAFT";
  @Column(nullable=false) private Instant createdAt=Instant.now();
  public Long getId(){return id;} public String getGraphId(){return graphId;} public void setGraphId(String v){graphId=v;} public String getGraphHash(){return graphHash;} public void setGraphHash(String v){graphHash=v;} public String getIdentityJson(){return identityJson;} public void setIdentityJson(String v){identityJson=v;} public String getDefinitionJson(){return definitionJson;} public void setDefinitionJson(String v){definitionJson=v;} public String getAssistantDiffJson(){return assistantDiffJson;} public void setAssistantDiffJson(String v){assistantDiffJson=v;} public String getSupersedesGraphId(){return supersedesGraphId;} public void setSupersedesGraphId(String v){supersedesGraphId=v;} public String getState(){return state;} public void setState(String v){state=v;} public Instant getCreatedAt(){return createdAt;}
}
