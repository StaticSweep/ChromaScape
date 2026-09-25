package com.chromascape.web.aiplayer.routine.source.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SourceReadAuditEventRepository extends JpaRepository<SourceReadAuditEvent, UUID> {}
