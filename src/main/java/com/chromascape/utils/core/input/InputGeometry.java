package com.chromascape.utils.core.input;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.Objects;

/**
 * Maps public movement coordinates to the client-relative coordinates a backend consumes.
 *
 * <p>The single conversion point between screen/window space and backend coordinates; backends
 * themselves never convert again.
 *
 * @param publicMovementBounds the bounds of the public coordinate space
 */
public record InputGeometry(Rectangle publicMovementBounds) {
  /** Defensively copies and validates the public movement bounds. */
  public InputGeometry {
    publicMovementBounds = new Rectangle(Objects.requireNonNull(publicMovementBounds));
    if (publicMovementBounds.width < 0 || publicMovementBounds.height < 0) {
      throw new IllegalArgumentException("Movement bounds must not have negative dimensions");
    }
  }

  /** Client-relative geometry: the origin is the client area's top-left corner. */
  public static InputGeometry clientRelative(int width, int height) {
    return new InputGeometry(new Rectangle(0, 0, width, height));
  }

  /**
   * Converts a public point to client-relative coordinates.
   *
   * @param point the public-space point
   * @return the client-relative point
   * @throws IllegalArgumentException when the point lies outside the movement bounds
   */
  public Point toClientPoint(Point point) {
    Objects.requireNonNull(point, "point");
    int x = point.x - publicMovementBounds.x;
    int y = point.y - publicMovementBounds.y;
    if (x < 0 || y < 0 || x >= publicMovementBounds.width || y >= publicMovementBounds.height) {
      throw new IllegalArgumentException("Point is outside target movement bounds");
    }
    return new Point(x, y);
  }
}
