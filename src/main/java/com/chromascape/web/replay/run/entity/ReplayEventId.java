package com.chromascape.web.replay.run.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class ReplayEventId implements Serializable {
    private UUID runId;
    private long ordinal;

    public ReplayEventId() {}

    public ReplayEventId(UUID runId, long ordinal) {
        this.runId = runId;
        this.ordinal = ordinal;
    }

    public UUID getRunId() { return runId; }
    public void setRunId(UUID v) { this.runId = v; }
    public long getOrdinal() { return ordinal; }
    public void setOrdinal(long v) { this.ordinal = v; }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ReplayEventId that)) return false;
        return ordinal == that.ordinal && Objects.equals(runId, that.runId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(runId, ordinal);
    }
}