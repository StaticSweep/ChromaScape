package com.chromascape.utils.core.screen.vision;

import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.MatVector;
import org.bytedeco.opencv.opencv_core.Point;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.List;

import static org.bytedeco.opencv.global.opencv_core.extractChannel;
import static org.bytedeco.opencv.global.opencv_core.merge;
import static org.bytedeco.opencv.global.opencv_cudaarithm.minMaxLoc;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2BGRA;
import static org.bytedeco.opencv.global.opencv_imgproc.TM_SQDIFF_NORMED;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.matchTemplate;

public class CvUtils {

    /**
     * Performs template matching to locate a smaller image (template) within a larger image (base),
     * using normalized squared difference matching with an alpha channel mask to ignore transparent pixels.
     * <p>
     * The method requires both images to have 4 channels (BGRA). If they do not, they are converted internally.
     * The matching ignores fully transparent pixels in the template by applying a mask based on its alpha channel.
     * <p>
     * The method returns the bounding rectangle of the best match if its matching score is below the given threshold.
     * If no match satisfies the threshold, the method returns {@code null}.
     * <p>
     * Note: The method releases the native OpenCV memory of the input and intermediate matrices,
     * so the caller should not use the input Mats after this call.
     *
     * @param template The template image (smaller), expected as a Mat in BGRA format or convertible to it.
     * @param base The base image (larger) where the template is searched, expected as a Mat in BGRA format or convertible to it.
     * @param threshold The maximum allowed normalized squared difference score for a valid match. Lower values mean better matches.
     * @return A {@link Rectangle} representing the position and size of the matching area in the base image,
     *         or {@code null} if no match meets the threshold criteria.
     * @throws IllegalArgumentException If either input Mat is empty (not loaded).
     * @throws Exception If the template is larger than the base image.
     */
    public Rectangle patternMatch(Mat template, Mat base, double threshold) throws Exception {

        if (template.empty()) {
            throw new IllegalArgumentException("Template image could not be loaded: " + template);
        }

        if (base.empty()) {
            throw new IllegalArgumentException("Base image could not be loaded: " + base);
        }

        // Ensure template has an alpha channel (4 channels). If not, convert from BGR to BGRA.
        if (template.channels() != 4) {
            cvtColor(template, template, COLOR_BGR2BGRA);
        }

        // Ensure base image has 4 channels (BGRA) for consistency with template.
        if (base.channels() != 4) {
            cvtColor(base, base, COLOR_BGR2BGRA);
        }

        // Prevent matching if template is larger than the base image.
        if (template.cols() > base.cols() || template.rows() > base.rows()) {
            throw new Exception("Template larger than base");
        }

        // Calculate the size of the result matrix (convolution) for matchTemplate output.
        int convRows = base.rows() - template.rows() + 1;
        int convCols = base.cols() - template.cols() + 1;
        Mat convolution = new Mat(convRows, convCols);

        // Create a mask based on the template's alpha channel.
        // Extract alpha channel and replicate it to 3 channels (required for mask in matchTemplate).
        Mat alpha = new Mat();
        extractChannel(template, alpha, 3);
        List<Mat> channels = Arrays.asList(alpha, alpha, alpha);
        Mat mask = new Mat();

        MatVector vec = new MatVector(channels.size());
        for (int i = 0; i < channels.size(); i++) {
            vec.put(i, channels.get(i));
        }
        merge(vec, mask);

        // Perform template matching with normalization and mask applied.
        matchTemplate(base, template, convolution, TM_SQDIFF_NORMED, mask);

        DoublePointer maxVal = new DoublePointer();
        DoublePointer minVal = new DoublePointer();
        Point minLoc = new Point();
        Point maxLoc = new Point();

        // Find minimum and maximum values and their locations in the convolution result.
        minMaxLoc(convolution, minVal, maxVal, minLoc, maxLoc);

        // If minimum match value exceeds threshold, no sufficient match was found.
        if (minVal.get() > threshold) return null;

        // Return the bounding rectangle where the best match was found.
        Rectangle match = new Rectangle(minLoc.x(), minLoc.y(), template.cols(), template.rows());

        // Release native memory to avoid leaks.
        template.release();
        base.release();
        convolution.release();
        alpha.release();
        mask.release();

        return match;
    }

}
