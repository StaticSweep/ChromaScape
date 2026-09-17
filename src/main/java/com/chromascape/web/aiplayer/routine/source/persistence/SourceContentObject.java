package com.chromascape.web.aiplayer.routine.source.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/**
 * Durable content-addressed source object. The bytes are written only after the manifest entry hash
 * has been verified; identity fields are intentionally exposed as read-only Java properties.
 */
@Entity
@Table(name = "source_content_objects")
public class SourceContentObject {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "content_hash", nullable = false, unique = true, length = 71)
  private String contentHash;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  // A byte[] basic field maps to PostgreSQL BYTEA. @Lob would select PostgreSQL large-object OIDs,
  // which would contradict the Flyway-owned content-addressed BYTEA column.
  @Column(name = "content_bytes", nullable = false, columnDefinition = "BYTEA")
  private byte[] contentBytes;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();

  @Column(name = "pruned_at")
  private Instant prunedAt;

  @Column(name = "redacted_at")
  private Instant redactedAt;

  protected SourceContentObject() {}

  public static SourceContentObject create(String contentHash, byte[] content) {
    if (contentHash == null || content == null) {
      throw new IllegalArgumentException("content identity and bytes are required");
    }
    SourceContentObject object = new SourceContentObject();
    object.contentHash = contentHash;
    object.sizeBytes = content.length;
    object.contentBytes = Arrays.copyOf(content, content.length);
    return object;
  }

  public UUID getId() {
    return id;
  }

  public String getContentHash() {
    return contentHash;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public byte[] getContentBytes() {
    return contentBytes == null ? null : Arrays.copyOf(contentBytes, contentBytes.length);
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getPrunedAt() {
    return prunedAt;
  }

  public void setPrunedAt(Instant value) {
    prunedAt = value;
  }

  public Instant getRedactedAt() {
    return redactedAt;
  }

  public void setRedactedAt(Instant value) {
    redactedAt = value;
  }
}
