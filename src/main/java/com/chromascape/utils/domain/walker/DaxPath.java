package com.chromascape.utils.domain.walker;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Record class to deserialize the raw output of the DAX API's walker.
 *
 * @param pathStatus Status of the request: SUCCESS or FAILURE.
 * @param path A {@link List} of Tile objects leading from current to destination positions.
 * @param cost How many tokens used.
 */
public record DaxPath(
    @JsonProperty("pathStatus") String pathStatus,
    @JsonProperty("path") List<Tile> path,
    @JsonProperty("cost") int cost) {}
