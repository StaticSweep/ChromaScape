package com.chromascape.utils.core.screen;

import java.awt.BorderLayout;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.WindowConstants;
import org.bytedeco.javacv.Java2DFrameUtils;

/**
 * Utility class for temporarily displaying {@link BufferedImage} instances in a Swing window for
 * debugging and visualization purposes (e.g., testing masks or OpenCV Mats).
 */
public class DisplayImage {

  private static JFrame frame;
  private static JLabel label;

  /**
   * Displays the provided {@link BufferedImage} in a simple Swing window.
   *
   * <p>If the display window has not been created yet, it will be initialized and shown. Otherwise,
   * the image inside the existing window will be updated.
   *
   * <p>This method is primarily intended for testing and debugging purposes during development.
   *
   * @param image The image to display. If the source is an OpenCV {@code Mat}, convert it first
   *     using {@link Java2DFrameUtils#toBufferedImage(org.bytedeco.opencv.opencv_core.Mat)}.
   */
  public static void display(BufferedImage image) {
    if (frame == null) {
      frame = new JFrame("ScreenShot");
      frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
      label = new JLabel(new ImageIcon(image));
      frame.getContentPane().add(label, BorderLayout.CENTER);
      frame.pack();
      frame.setLocationRelativeTo(null);
      frame.setVisible(true);
    } else {
      label.setIcon(new ImageIcon(image));
    }
  }
}
