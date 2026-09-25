package com.chromascape.utils.core.input.mouse;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import javax.swing.JFrame;

/**
 * A lightweight transparent overlay used to visually render the virtual mouse position. This
 * overlay draws a red "X" at the current mouse coordinates and remains always on top, without
 * interfering with user input or system focus.
 */
public class MouseOverlay extends JFrame {

  private Point mousePoint = new Point(0, 0);

  /**
   * Constructs the mouse overlay window. The overlay is frameless, transparent, always on top, and
   * spans the full screen. It is also hidden from the taskbar and does not steal focus.
   */
  public MouseOverlay() {
    setUndecorated(true);
    setBackground(new Color(0, 0, 0, 0));
    setAlwaysOnTop(true);
    setFocusableWindowState(false);
    setType(Type.UTILITY);

    setLayout(null);
    setVisible(true);
  }

  /**
   * Updates the overlay to draw the red "X" at the specified mouse location.
   *
   * @param p The new position to draw the virtual mouse indicator.
   */
  public void setMousePoint(Point p) {
    this.mousePoint = p;
    repaint();
  }

  /**
   * Paints the virtual mouse indicator at the last provided point. Draws a red "X" using two
   * diagonal lines.
   *
   * @param g The Graphics context for the overlay window.
   */
  @Override
  public void paint(Graphics g) {
    super.paint(g);
    Graphics2D g2d = (Graphics2D) g.create();
    g2d.setColor(Color.RED);
    g2d.setStroke(new BasicStroke(2f));
    int size = 6;

    // Convert from global screen space to window-local coordinates
    int x = mousePoint.x - getX();
    int y = mousePoint.y - getY();

    g2d.drawLine(x - size, y - size, x + size, y + size); // Top-left to bottom-right
    g2d.drawLine(x - size, y + size, x + size, y - size); // Bottom-left to top-right

    g2d.dispose();
  }

  /**
   * Erases the overlay completely, removing it from the screen and freeing resources. After calling
   * this method, the overlay will no longer be visible or repaintable.
   */
  public void eraseOverlay() {
    // Hide the window
    setVisible(false);
    // Dispose of the JFrame resources
    dispose();
    // Reset the mouse point just in case
    mousePoint = new Point(0, 0);
  }
}
