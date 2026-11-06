-- Native gameplay outcomes are independently content-addressed evidence. The
-- unchanged report-v1 artifactRefs binds this evidence to fenced finalization.
ALTER TABLE artifact_refs DROP CONSTRAINT IF EXISTS artifact_refs_kind_check;
ALTER TABLE artifact_refs ADD CONSTRAINT artifact_refs_kind_check
    CHECK (kind IN ('MANIFEST', 'SNAPSHOT', 'TRACE', 'REPORT', 'CHAIN', 'PROJECTION', 'OUTCOME'));
