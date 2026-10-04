-- A logical request retains one attempt, including failed/completed attempts.
-- INSERT + row lock + run creation/binding commit together, across all app instances.
CREATE TABLE builder_goal_start_request (
    request_id VARCHAR(256) PRIMARY KEY,
    fingerprint VARCHAR(71) NOT NULL,
    iteration_id VARCHAR(100) UNIQUE REFERENCES patch_loop_iteration(iteration_id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
