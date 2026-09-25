package com.chromascape.web.replay.run.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "replay_attempts")
public class ReplayAttempt {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "run_id", nullable = false)
  private UUID runId;

  @Column(name = "attempt_no", nullable = false)
  private int attemptNo;

  @Column(name = "job_id")
  private String jobId;

  @Column(name = "lease_owner")
  private String leaseOwner;

  @Column(name = "fencing_generation", nullable = false)
  private int fencingGeneration;

  @Column(name = "lease_expires_at")
  private LocalDateTime leaseExpiresAt;

  @Column(name = "heartbeat_at")
  private LocalDateTime heartbeatAt;

  @Column(name = "worker_identity")
  private String workerIdentity;

  @Column(nullable = false)
  private String state = "QUEUED";

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "started_at")
  private LocalDateTime startedAt;

  @Column(name = "terminal_at")
  private LocalDateTime terminalAt;

  public ReplayAttempt() {}

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public UUID getRunId() {
    return runId;
  }

  public void setRunId(UUID runId) {
    this.runId = runId;
  }

  public int getAttemptNo() {
    return attemptNo;
  }

  public void setAttemptNo(int attemptNo) {
    this.attemptNo = attemptNo;
  }

  public String getJobId() {
    return jobId;
  }

  public void setJobId(String jobId) {
    this.jobId = jobId;
  }

  public String getLeaseOwner() {
    return leaseOwner;
  }

  public void setLeaseOwner(String leaseOwner) {
    this.leaseOwner = leaseOwner;
  }

  public int getFencingGeneration() {
    return fencingGeneration;
  }

  public void setFencingGeneration(int gen) {
    this.fencingGeneration = gen;
  }

  public LocalDateTime getLeaseExpiresAt() {
    return leaseExpiresAt;
  }

  public void setLeaseExpiresAt(LocalDateTime t) {
    this.leaseExpiresAt = t;
  }

  public LocalDateTime getHeartbeatAt() {
    return heartbeatAt;
  }

  public void setHeartbeatAt(LocalDateTime t) {
    this.heartbeatAt = t;
  }

  public String getWorkerIdentity() {
    return workerIdentity;
  }

  public void setWorkerIdentity(String id) {
    this.workerIdentity = id;
  }

  public String getState() {
    return state;
  }

  public void setState(String state) {
    this.state = state;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime t) {
    this.createdAt = t;
  }

  public LocalDateTime getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(LocalDateTime t) {
    this.startedAt = t;
  }

  public LocalDateTime getTerminalAt() {
    return terminalAt;
  }

  public void setTerminalAt(LocalDateTime t) {
    this.terminalAt = t;
  }
}
