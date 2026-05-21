package com.chromascape.utils.core.screen.topology;

import java.awt.Rectangle;

/**
 * The object returned by {@link TemplateMatching}. Contains all the necessary information to react
 * to the state of the template image. It is the consumer's responsibility to act on the result.
 * There are no errors thrown.
 *
 * @param bounds The bounding box and location of the detected image. If success is false/match not
 *     found, this value is {@code null}.
 * @param score The minVal/threshold/confidence at which the image correlated to the base (lower =
 *     better). If success is false/match not found, this value is {@code Double.MAX_VALUE()}.
 * @param success Whether the template was successfully found within the base image. {@code boolean}
 * @param message A message associated to state, in case there is no match, this will contain
 *     further information.
 */
public record MatchResult(Rectangle bounds, double score, boolean success, String message) {}
