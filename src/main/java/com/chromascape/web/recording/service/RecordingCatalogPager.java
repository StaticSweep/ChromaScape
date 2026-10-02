package com.chromascape.web.recording.service;

import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.replay.playback.ReplayPlaybackCursorCodec;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keyset catalog for ALL recording rows, including video-only recordings. */
@Service
@Transactional(readOnly = true)
public class RecordingCatalogPager {
  private final EntityManager em;
  private final ReplayPlaybackCursorCodec codec;

  public RecordingCatalogPager(EntityManager em, ReplayPlaybackCursorCodec codec) {
    this.em = em;
    this.codec = codec;
  }

  public Page page(String accountName, String taskKey, int limit, String value) {
    if (accountName != null && accountName.length() > 100
        || taskKey != null && taskKey.length() > 160)
      throw new com.chromascape.web.recording.provenance.ProvenanceException(
          "INVALID_CATALOG_FILTER");
    var cursor = codec.decode(value);
    int size = Math.max(1, Math.min(200, limit));
    String sql = "SELECT r FROM RecordingSession r WHERE 1=1";
    if (accountName != null) sql += " AND r.accountName = :account";
    if (taskKey != null) sql += " AND r.taskKey = :task";
    if (cursor != null) sql += " AND (r.startTime < :time OR (r.startTime = :time AND r.id < :id))";
    var query =
        em.createQuery(sql + " ORDER BY r.startTime DESC, r.id DESC", RecordingSession.class);
    if (accountName != null) query.setParameter("account", accountName);
    if (taskKey != null) query.setParameter("task", taskKey);
    if (cursor != null) {
      query.setParameter("time", cursor.startTime());
      query.setParameter("id", cursor.id());
    }
    List<RecordingSession> rows = query.setMaxResults(size + 1).getResultList();
    boolean more = rows.size() > size;
    List<RecordingSession> entries = rows.subList(0, Math.min(size, rows.size()));
    RecordingSession last = entries.isEmpty() ? null : entries.get(entries.size() - 1);
    return new Page(
        List.copyOf(entries), more ? codec.encode(last.getStartTime(), last.getId()) : null, more);
  }

  public record Page(List<RecordingSession> entries, String nextCursor, boolean hasMore) {}
}
