package com.chromascape.web.replay.artifact.entity;

import com.chromascape.web.replay.api.dto.ArtifactKind;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Content-addressed artifact reference. Column names and state values
 * match V14 exactly: checksum / state / finalized_at with states
 * STAGING, FINALIZED, CORRUPT, TOMBSTONED.
 */
@Entity
@Table(name = "artifact_refs")
public class ArtifactRef {

    public static final String STATE_STAGING = "STAGING";
    public static final String STATE_FINALIZED = "FINALIZED";
    public static final String STATE_CORRUPT = "CORRUPT";
    public static final String STATE_TOMBSTONED = "TOMBSTONED";

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "run_id", columnDefinition = "uuid", nullable = false)
    private UUID runId;

    @Column(name = "kind", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private ArtifactKind kind;

    /** SHA-256 content address (hex), unique across artifacts. */
    @Column(name = "checksum", nullable = false, unique = true)
    private String checksum;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "media_type", length = 100)
    private String mediaType;

    /** STAGING -> FINALIZED (or CORRUPT / TOMBSTONED). */
    @Column(name = "state", nullable = false, length = 20)
    private String state = STATE_STAGING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    protected ArtifactRef() {}

    public ArtifactRef(UUID id, UUID runId, ArtifactKind kind, String checksum, long sizeBytes) {
        this.id = id;
        this.runId = runId;
        this.kind = kind;
        this.checksum = checksum;
        this.sizeBytes = sizeBytes;
        this.state = STATE_STAGING;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getRunId() { return runId; }
    public ArtifactKind getKind() { return kind; }
    public String getChecksum() { return checksum; }
    public long getSizeBytes() { return sizeBytes; }
    public String getMediaType() { return mediaType; }
    public void setMediaType(String v) { this.mediaType = v; }
    public String getState() { return state; }
    public void setState(String v) { this.state = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getFinalizedAt() { return finalizedAt; }
    public void setFinalizedAt(Instant v) { this.finalizedAt = v; }
}
