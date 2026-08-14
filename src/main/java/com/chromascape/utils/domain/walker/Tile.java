package com.chromascape.utils.domain.walker;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Record class to deserialize and store a DAX API path as a set of Tiles.
 *
 * @param x x co-ordinate.
 * @param y y co-ordinate.
 * @param z z co-ordinate.
 */
public record Tile(@JsonProperty("x") int x, @JsonProperty("y") int y, @JsonProperty("z") int z) {}
