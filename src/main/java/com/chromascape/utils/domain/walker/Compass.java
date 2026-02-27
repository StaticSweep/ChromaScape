package com.chromascape.utils.domain.walker;

import static org.bytedeco.opencv.global.opencv_core.fastAtan2;
import static org.bytedeco.opencv.global.opencv_core.inRange;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2HSV;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;

import com.chromascape.controller.Controller;
import com.chromascape.utils.core.screen.colour.ColourObj;
import com.chromascape.utils.core.screen.window.ScreenManager;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import org.bytedeco.javacpp.PointerScope;
import org.bytedeco.javacv.Java2DFrameUtils;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point2f;
import org.bytedeco.opencv.opencv_core.Scalar;

/**
 * Handles detection of the in-game compass orientation by calculating the bearing, based on the
 * cardinal markers within the compass (East, West and South).
 *
 * <p>This class enables angle-based transformations, such as rotating click positions on the
 * minimap to match the player's camera orientation.
 */
public class Compass {

  // Minimum length from centre that the outermost cardinal compass marker pixels should be
  private static final double MIN_MARKER_RADIUS = 12.0;
  // Minimum length from centre that the outermost cardinal compass marker pixels should be
  private static final double MAX_MARKER_RADIUS = 17.0;
  // Proximity of pixels to define a cluster (within 4 px? -> part of the same cluster)
  private static final int CLUSTER_PROXIMITY_THRESHOLD = 4;
  double[] cardinals = {0.0, 90.0, 180.0, 270.0, 360.0};
  // How close to the cardinal an angle should be to snap to it
  double cardinalSnapThreshold = 3.0;

  private final Controller controller;

  // Colour of the outer cardinal markers within the compass
  private final ColourObj compassRed =
      new ColourObj("CompassRed", new Scalar(0, 200, 140, 0), new Scalar(20, 255, 200, 0));

  /**
   * Constructs the Compass class. Uses the BaseScript's {@link Controller} object to access zones.
   *
   * @param controller the BaseScript's controller object.
   */
  public Compass(Controller controller) {
    this.controller = controller;
  }

  /**
   * Calculates the current compass angle by using the 3 red markers that denote East, South and
   * West. Dependant that compassRed is accurate in the user's environment. Releases native memory
   * related to JavaCV. Heavily inspired by SRL. Thank you.
   *
   * @return The detected angle in degrees (0-359.9).
   */
  public double getCompassAngle() {
    try (PointerScope ignored = new PointerScope()) {

      // Mask out the compass image by compassRed
      Mat mask = captureRedMarkerMask();
      // Keep only the outermost pixels (to erase the compass needle)
      // Convert them to Points for clustering
      List<Point2f> cardinalPoints = extractCardinalPoints(mask);
      // If the pixels are within 4 pixels of each other, class them as the same cluster
      List<List<Point2f>> clusters = clusterPoints(cardinalPoints);
      // There should be exactly 3 clusters (E, S, W)
      if (clusters.size() < 3) {
        return 0.0;
      }
      // Average each cluster into a single point (weight)
      Point2f[] markers = getClusterWeights(clusters);
      // Move the south cluster to index 0 by judging the longest chord (between E and W)
      sortClusterWeights(markers);
      // Sort the array into S, E, W by comparing the predicted south vs real south
      identifyEastAndWest(markers);
      // Calculate the final bearing using E and W
      double degrees = fastAtan2(markers[1].y() - markers[2].y(), markers[1].x() - markers[2].x());
      // Snap to a cardinal angle if within the threshold
      for (double cardinal : cardinals) {
        // We use deltaAngle to handle the 359 -> 0 wrap-around
        if (Math.abs(deltaAngle((float) degrees, (float) cardinal)) <= cardinalSnapThreshold) {
          return (cardinal == 360.0) ? 0.0 : cardinal;
        }
      }

      return degrees;
    }
  }

  /**
   * Compares predicted south to true south to sort the clusters into [South, East, West]. Uses arc
   * tangents to compare the relationship between the E/S vector and S, Pivot vector. Flips the East
   * and West value to sort the array.
   *
   * @param sortedClusterWeights an array of cluster weights sorted to [South, East, West].
   */
  private void identifyEastAndWest(Point2f[] sortedClusterWeights) {
    float eastOrWestAngle =
        fastAtan2(
            sortedClusterWeights[1].y() - getPivot().y(),
            sortedClusterWeights[1].x() - getPivot().x());
    float southAngle =
        fastAtan2(
            sortedClusterWeights[0].y() - getPivot().y(),
            sortedClusterWeights[0].x() - getPivot().x());
    if (Math.abs(deltaAngle(eastOrWestAngle + 90, southAngle)) > 90) {
      Point2f temp = sortedClusterWeights[1];
      sortedClusterWeights[1] = sortedClusterWeights[2];
      sortedClusterWeights[2] = temp;
    }
  }

  /**
   * Finds the shortest angle between two angles. Wraps around the circle correctly as opposed to a
   * traditional modulus.
   *
   * @param a1 The first angle.
   * @param a2 The second angle.
   * @return the smallest angle between the two given values.
   */
  public static float deltaAngle(float a1, float a2) {
    float result = (a1 - a2);
    while (result > 180) {
      result -= 360;
    }
    while (result <= -180) {
      result += 360;
    }
    return result;
  }

  /**
   * Assigns each cluster a weight value by calculating the mean average between each pixel within.
   * This is useful when considering the cluster as a whole rather than an individual point for
   * calculation.
   *
   * @param clusters a {@link List} containing {@code List<Point2f>}s that each refer to a cluster.
   * @return an array of {@link Point2f}s which refer to the weighted average of each respective
   *     cluster.
   */
  private Point2f[] getClusterWeights(List<List<Point2f>> clusters) {
    Point2f[] clusterWeights = new Point2f[clusters.size()];

    for (int i = 0; i < clusters.size(); i++) {
      float weightX = 0;
      float weightY = 0;

      for (int j = 0; j < clusters.get(i).size(); j++) {
        weightX += clusters.get(i).get(j).x();
        weightY += clusters.get(i).get(j).y();
      }
      clusterWeights[i] =
          new Point2f((weightX / clusters.get(i).size()), (weightY / clusters.get(i).size()));
    }
    return clusterWeights;
  }

  /**
   * Calculates the largest chord between each of the cluster weights and places south at the first
   * index, with east or west following afterward. This mutates the given array and does not return
   * any value.
   *
   * @param clusterWeights an array of points referring to the weighted average of the cardinal
   *     clusters.
   */
  private void sortClusterWeights(Point2f[] clusterWeights) {

    double d1 = getDistanceBetweenTwoPoints(clusterWeights[0], clusterWeights[1]);
    double d2 = getDistanceBetweenTwoPoints(clusterWeights[0], clusterWeights[2]);

    if (d1 > 25) {
      Point2f temp = clusterWeights[0];
      clusterWeights[0] = clusterWeights[2];
      clusterWeights[2] = temp;
    }
    if (d2 > 25) {
      Point2f temp = clusterWeights[0];
      clusterWeights[0] = clusterWeights[1];
      clusterWeights[1] = temp;
    }
  }

  /**
   * Gets the Euclidean distance between two {@link Point2f} values.
   *
   * @param a The first value to compare.
   * @param b The second value to compare.
   * @return The distance between the two points.
   */
  private float getDistanceBetweenTwoPoints(Point2f a, Point2f b) {
    return (float)
        Math.sqrt(((a.x() - b.x()) * (a.x() - b.x())) + ((a.y() - b.y()) * (a.y() - b.y())));
  }

  /**
   * Uses the BaseScript's controller to access the compass. Masks out the cardinal markers in the
   * colour compassRed.
   *
   * @return the masked compass image in {@link Mat} form.
   */
  private Mat captureRedMarkerMask() {
    Rectangle zone = controller.zones().getMinimap().get("compassSimilarity");
    BufferedImage img = ScreenManager.captureZone(zone);

    Mat src = Java2DFrameUtils.toMat(img);
    Mat hsv = new Mat();
    Mat mask = new Mat();

    Mat lower = new Mat(compassRed.hsvMin());
    Mat upper = new Mat(compassRed.hsvMax());

    cvtColor(src, hsv, COLOR_BGR2HSV);
    inRange(hsv, lower, upper, mask);

    src.release();
    hsv.release();
    lower.release();
    upper.release();

    return mask;
  }

  /**
   * Uses a mask of the compass filtered by compassRed to remove the inner compass needle. Leaving
   * only clusters of the outermost cardinal markers.
   *
   * @param mask a CU81 greyscale mask of the compass, filtered by compassRed.
   * @return a list of {@link Point2f} objects to denote the outermost cardinal markers.
   */
  private List<Point2f> extractCardinalPoints(Mat mask) {
    List<Point2f> cardinalPoints = new ArrayList<>();
    for (int y = 0; y < mask.rows(); y++) {
      for (int x = 0; x < mask.cols(); x++) {
        if (mask.ptr(y, x).get() != 0) {
          double dist = Math.hypot(x - getPivot().x(), y - getPivot().y());
          if (dist >= MIN_MARKER_RADIUS && dist <= MAX_MARKER_RADIUS) {
            cardinalPoints.add(new Point2f(x, y));
          }
        }
      }
    }
    return cardinalPoints;
  }

  /**
   * Provides the compass pivot/centre based on fixed-classic or resizable-classic.
   *
   * @return the coordinate offset for the compass center based on the ZoneManager.
   */
  private Point2f getPivot() {
    if (controller.zones().getIsFixed()) {
      return new Point2f(17, 17);
    } else {
      return new Point2f(18, 18);
    }
  }

  /**
   * Groups nearby points together based on the CLUSTER_PROXIMITY_THRESHOLD.
   *
   * @param points the list of detected red pixels.
   * @return a list of lists, where each inner list represents a distinct marker cluster.
   */
  private List<List<Point2f>> clusterPoints(List<Point2f> points) {
    List<List<Point2f>> clusters = new ArrayList<>();
    while (!points.isEmpty()) {
      List<Point2f> cluster = new ArrayList<>();
      Point2f root = points.remove(0);
      cluster.add(root);
      points.removeIf(
          p -> {
            if (Math.hypot(root.x() - p.x(), root.y() - p.y()) < CLUSTER_PROXIMITY_THRESHOLD) {
              cluster.add(p);
              return true;
            }
            return false;
          });
      clusters.add(cluster);
    }
    return clusters;
  }
}
