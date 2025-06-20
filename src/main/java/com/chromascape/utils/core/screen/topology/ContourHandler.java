package com.chromascape.utils.core.screen.topology;

import com.chromascape.utils.core.screen.DisplayImage;
import com.chromascape.utils.core.screen.colour.ColourObj;
import org.bytedeco.javacpp.indexer.IntRawIndexer;
import org.bytedeco.javacv.Java2DFrameUtils;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.MatVector;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Point2f;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC1;
import static org.bytedeco.opencv.global.opencv_core.inRange;
import static org.bytedeco.opencv.global.opencv_imgproc.CHAIN_APPROX_SIMPLE;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2HSV;
import static org.bytedeco.opencv.global.opencv_imgproc.CV_RETR_LIST;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.findContours;
import static org.bytedeco.opencv.global.opencv_imgproc.pointPolygonTest;

public class ContourHandler {

    private static final List<ChromaObj> chromaObjects = new ArrayList<>();

    public static List<ChromaObj> getChromaObjsInColour(BufferedImage image, ColourObj colourObj) {
        createChromaObjects(extractContours(extractColours(image, colourObj)));
        return chromaObjects;
    }

    public static Mat extractColours(BufferedImage image, ColourObj colourObj) {
        Mat HSVImage = Java2DFrameUtils.toMat(image);
        cvtColor(HSVImage, HSVImage, COLOR_BGR2HSV);
        Mat result = new Mat(HSVImage.size(), CV_8UC1);
        Mat HSVMin = new Mat(colourObj.getHSVMin());
        Mat HSVMax = new Mat(colourObj.getHSVMax());
        inRange(HSVImage, HSVMin, HSVMax, result);

        HSVImage.release();
        HSVMin.release();
        HSVMax.release();

        DisplayImage.display(Java2DFrameUtils.toBufferedImage(result));
        return result;
    }

    public static MatVector extractContours(Mat binaryMask) {
        MatVector contours = new MatVector();
        findContours(binaryMask, contours, CV_RETR_LIST, CHAIN_APPROX_SIMPLE);
        return contours;
    }

    public static void createChromaObjects(MatVector contours) {
        chromaObjects.clear();
        for (int i = 0; i < contours.size(); i++) {
            Mat contour = contours.get(i);

            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;

            try (IntRawIndexer indexer = contour.createIndexer()) {
                int numRows = contour.rows();
                int numCols = contour.cols();

                for (int p = 0; p < numRows; p++) {
                    int x, y;

                    if (numCols == 1) {
                        // Contour shape: Nx1x2 (common OpenCV format)
                        x = indexer.get(p, 0, 0);
                        y = indexer.get(p, 0, 1);
                    } else {
                        // Contour shape: Nx2 (less common)
                        x = indexer.get(p, 0);
                        y = indexer.get(p, 1);
                    }

                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }

            int width = maxX - minX;
            int height = maxY - minY;

            Rectangle contourBounds = new Rectangle(minX, minY, width, height);
            chromaObjects.add(new ChromaObj(i, contour, contourBounds));
        }
    }

    public static boolean isPointInContour(Point point, Mat contour) {
        try (Point2f point2f = new Point2f(point.x(), point.y())) {
            return pointPolygonTest(contour, point2f, false) > 0;
        }
    }

    public static List<ChromaObj> getChromaObjects() {
        return chromaObjects;
    }
}
