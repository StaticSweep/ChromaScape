package com.chromascape.web.replay.curation.entity;

import jakarta.persistence.*;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "field_provenance")
public class FieldProvenance {

    @EmbeddedId
    private FieldProvenanceId id;

    @MapsId("curationId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "curation_id", nullable = false)
    private RecordingCuration curation;

    @Column(name = "provenance", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private FieldProvenanceSource provenance;

    @Column(name = "evidence_ref", columnDefinition = "jsonb")
    private String evidenceRef;

    @Column(name = "derivation_version", length = 50)
    private String derivationVersion;

    @Column(name = "supplied_by", length = 100)
    private String suppliedBy;

    @Column(name = "reason", length = 500)
    private String reason;

    protected FieldProvenance() {}

    public FieldProvenance(RecordingCuration curation, String jsonPointer,
                           FieldProvenanceSource provenance) {
        this.id = new FieldProvenanceId(curation.getId(), jsonPointer);
        this.curation = curation;
        this.provenance = provenance;
    }

    public FieldProvenanceId getId() { return id; }
    public RecordingCuration getCuration() { return curation; }
    public FieldProvenanceSource getProvenance() { return provenance; }
    public void setProvenance(FieldProvenanceSource provenance) { this.provenance = provenance; }
    public String getEvidenceRef() { return evidenceRef; }
    public void setEvidenceRef(String evidenceRef) { this.evidenceRef = evidenceRef; }
    public String getDerivationVersion() { return derivationVersion; }
    public void setDerivationVersion(String derivationVersion) { this.derivationVersion = derivationVersion; }
    public String getSuppliedBy() { return suppliedBy; }
    public void setSuppliedBy(String suppliedBy) { this.suppliedBy = suppliedBy; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    @Embeddable
    public static class FieldProvenanceId {
        @Column(name = "curation_id", columnDefinition = "uuid")
        private UUID curationId;

        @Column(name = "json_pointer", length = 500)
        private String jsonPointer;

        protected FieldProvenanceId() {}
        public FieldProvenanceId(UUID curationId, String jsonPointer) {
            this.curationId = curationId;
            this.jsonPointer = jsonPointer;
        }
        public UUID getCurationId() { return curationId; }
        public String getJsonPointer() { return jsonPointer; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof FieldProvenanceId that)) return false;
            return Objects.equals(curationId, that.curationId) &&
                   Objects.equals(jsonPointer, that.jsonPointer);
        }
        @Override public int hashCode() { return Objects.hash(curationId, jsonPointer); }
    }
}