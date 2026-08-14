package com.chromascape.web.aiplayer.routine.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="routine_curations", indexes=@Index(name="idx_routine_curations_recording", columnList="recording_id", unique=true))
public class RoutineCuration {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  @Column(name="recording_id",nullable=false,unique=true,length=160) private String recordingId;
  @Lob private String includeRangesJson; @Lob private String excludeRangesJson;
  @Column(nullable=false) private Instant createdAt=Instant.now();
  public Long getId(){return id;} public String getRecordingId(){return recordingId;} public void setRecordingId(String v){recordingId=v;} public String getIncludeRangesJson(){return includeRangesJson;} public void setIncludeRangesJson(String v){includeRangesJson=v;} public String getExcludeRangesJson(){return excludeRangesJson;} public void setExcludeRangesJson(String v){excludeRangesJson=v;} public Instant getCreatedAt(){return createdAt;}
}
