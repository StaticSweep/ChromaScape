package com.chromascape.web.aiplayer.routine.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity
@Table(name="current_slice_runs", indexes=@Index(name="idx_current_slice_runs_run_id", columnList="run_id", unique=true))
public class CurrentSliceRun {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  @Column(name="run_id", nullable=false, unique=true, length=160) private String runId;
  @Column(name="attempt_id", length=160) private String attemptId;
  @JdbcTypeCode(SqlTypes.LONGVARCHAR) @Column(name="identity_json", nullable=false) private String identityJson;
  @Column(nullable=false, length=40) private String stage;
  @Column(nullable=false) private boolean controlAllowed=false;
  @Column(nullable=false) private Instant createdAt=Instant.now();
  public Long getId(){return id;} public String getRunId(){return runId;} public void setRunId(String v){runId=v;} public String getAttemptId(){return attemptId;} public void setAttemptId(String v){attemptId=v;} public String getIdentityJson(){return identityJson;} public void setIdentityJson(String v){identityJson=v;} public String getStage(){return stage;} public void setStage(String v){stage=v;} public boolean isControlAllowed(){return false;} public Instant getCreatedAt(){return createdAt;}
}
