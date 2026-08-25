package com.chromascape.web.replay.curation.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "curated_actions")
@IdClass(CuratedAction.CuratedActionId.class)
public class CuratedAction {

    @Id
    @Column(name = "curation_id")
    private UUID curationId;

    @Id
    @Column(nullable = false)
    private long tick;

    @Id
    @Column(nullable = false)
    private int sequence;

    @Column(nullable = false)
    private String status;

    @Column(name = "semantic_action_type")
    private String semanticActionType;

    @Column(name = "target_stable_id")
    private String targetStableId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "arguments_json")
    private JsonNode argumentsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_ref")
    private JsonNode evidenceRef;

    @Column
    private BigDecimal confidence;

    public CuratedAction() {}

    public UUID getCurationId() { return curationId; }
    public void setCurationId(UUID curationId) { this.curationId = curationId; }
    public long getTick() { return tick; }
    public void setTick(long tick) { this.tick = tick; }
    public int getSequence() { return sequence; }
    public void setSequence(int sequence) { this.sequence = sequence; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSemanticActionType() { return semanticActionType; }
    public void setSemanticActionType(String semanticActionType) { this.semanticActionType = semanticActionType; }
    public String getTargetStableId() { return targetStableId; }
    public void setTargetStableId(String targetStableId) { this.targetStableId = targetStableId; }
    public JsonNode getArgumentsJson() { return argumentsJson; }
    public void setArgumentsJson(JsonNode argumentsJson) { this.argumentsJson = argumentsJson; }
    public JsonNode getEvidenceRef() { return evidenceRef; }
    public void setEvidenceRef(JsonNode evidenceRef) { this.evidenceRef = evidenceRef; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }

    public static class CuratedActionId implements Serializable {
        private UUID curationId;
        private long tick;
        private int sequence;

        public CuratedActionId() {}

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CuratedActionId that)) return false;
            return tick == that.tick
                    && sequence == that.sequence
                    && Objects.equals(curationId, that.curationId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(curationId, tick, sequence);
        }
    }
}