-- SC-728 editor drafts are deliberately separate from SC-967's immutable v1 graph rows.
CREATE TABLE behavior_graph_drafts (
  draft_id VARCHAR(64) PRIMARY KEY,
  graph_id VARCHAR(64) NOT NULL UNIQUE,
  base_graph_id VARCHAR(64),
  task_key VARCHAR(64) NOT NULL,
  profile_id VARCHAR(128) NOT NULL,
  profile_version VARCHAR(64) NOT NULL,
  revision BIGINT NOT NULL CHECK (revision > 0),
  definition_schema VARCHAR(64) NOT NULL,
  definition_json TEXT NOT NULL,
  graph_hash VARCHAR(71) NOT NULL,
  layout_schema VARCHAR(64) NOT NULL,
  layout_json TEXT NOT NULL,
  layout_hash VARCHAR(71) NOT NULL,
  state VARCHAR(16) NOT NULL CHECK (state IN ('DRAFT', 'PUBLISHED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by VARCHAR(128),
  updated_by VARCHAR(128)
);

CREATE TABLE behavior_graph_publications (
  publication_id BIGSERIAL PRIMARY KEY,
  graph_id VARCHAR(64) NOT NULL,
  graph_version BIGINT NOT NULL CHECK (graph_version > 0),
  source_draft_id VARCHAR(64) NOT NULL,
  source_draft_revision BIGINT NOT NULL CHECK (source_draft_revision > 0),
  definition_schema VARCHAR(64) NOT NULL,
  definition_json TEXT NOT NULL,
  graph_hash VARCHAR(71) NOT NULL,
  layout_schema VARCHAR(64) NOT NULL,
  layout_json TEXT NOT NULL,
  layout_hash VARCHAR(71) NOT NULL,
  published_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_by VARCHAR(128),
  CONSTRAINT uq_behavior_graph_publication_version UNIQUE (graph_id, graph_version),
  CONSTRAINT uq_behavior_graph_publication_source UNIQUE (source_draft_id, source_draft_revision)
);
