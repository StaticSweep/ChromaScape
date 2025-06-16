package com.chromascape.utils.core.screen.vision;

import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacv.Java2DFrameUtils;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.javacv.Java2DFrameConverter;

import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC1;
import static org.bytedeco.opencv.global.opencv_core.bitwise_and;
import static org.bytedeco.opencv.global.opencv_core.extractChannel;
import static org.bytedeco.opencv.global.opencv_core.minMaxLoc;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2BGRA;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.matchTemplate;
import static org.bytedeco.opencv.global.opencv_imgproc.TM_SQDIFF_NORMED;

public class CvUtils {

    private static final OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();
    private static final Java2DFrameConverter bufferedImageConverter = new Java2DFrameConverter();

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
     * @param templateImg The template image (smaller), expected as a BufferedImage in BGRA format or convertible to it.
     * @param baseImg The base image (larger) where the template is searched, expected as a BufferedImage in BGRA format or convertible to it.
     * @param threshold The maximum allowed normalized squared difference score for a valid match. Lower values mean better matches.
     * @param debugMsg Set this to true if you want detailed messages useful when debugging.
     * @return A {@link Rectangle} representing the position and size of the matching area in the base image,
     *         or {@code null} if no match meets the threshold criteria.
     * @throws IllegalArgumentException If either input Mat is empty (not loaded).
     * @throws Exception If the template is larger than the base image.
     */
    public Rectangle patternMatch(BufferedImage templateImg, BufferedImage baseImg, double threshold, boolean debugMsg) throws Exception {

        debug(">> Entered patternMatch()", debugMsg);

        Mat template = Java2DFrameUtils.toMat(templateImg);
        Mat base = Java2DFrameUtils.toMat(baseImg);

        if (template.empty()) throw new IllegalArgumentException("Template image is empty");
        if (base.empty()) throw new IllegalArgumentException("Base image is empty");

        debug("Template size: " + template.size().width() + "x" + template.size().height() +
                ", channels: " + template.channels(), debugMsg);
        debug("Base size: " + base.size().width() + "x" + base.size().height() +
                ", channels: " + base.channels(), debugMsg);

        if (template.channels() != 4) {
            debug("Converting template to BGRA...", debugMsg);
            cvtColor(template, template, COLOR_BGR2BGRA);
        }

        if (base.channels() != 4) {
            debug("Converting base to BGRA...", debugMsg);
            cvtColor(base, base, COLOR_BGR2BGRA);
        }

        if (template.cols() > base.cols() || template.rows() > base.rows()) {
            throw new Exception("Template is larger than base image");
        }

        int convRows = base.rows() - template.rows() + 1;
        int convCols = base.cols() - template.cols() + 1;
        debug("Convolution matrix size: " + convCols + "x" + convRows, debugMsg);

        Mat convolution = new Mat(convRows, convCols);

        Mat alpha = new Mat();
        extractChannel(template, alpha, 3);
        debug("Alpha mask size: " + alpha.size().width() + "x" + alpha.size().height() +
                ", channels: " + alpha.channels(), debugMsg);

        debug("Calling matchTemplate()...", debugMsg);
        matchTemplate(base, template, convolution, TM_SQDIFF_NORMED, alpha);
        debug("matchTemplate() done.", debugMsg);
        debug("Convolution empty: " + convolution.empty(), debugMsg);

        if (convolution.empty()) {
            throw new RuntimeException("matchTemplate() failed — convolution matrix is empty");
        }

        DoublePointer minVal = new DoublePointer(1);
        DoublePointer maxVal = new DoublePointer(1);
        Point minLoc = new Point();
        Point maxLoc = new Point();

        debug("Calling minMaxLoc()...", debugMsg);
        minMaxLoc(convolution, minVal, maxVal, minLoc, maxLoc, new Mat());
        debug("minMaxLoc() done.", debugMsg);
        debug("minVal: " + minVal.get() + ", maxVal: " + maxVal.get(), debugMsg);
        debug("minLoc: (" + minLoc.x() + "," + minLoc.y() + "), maxLoc: (" + maxLoc.x() + "," + maxLoc.y() + ")", debugMsg);

        if (minVal.get() > threshold) {
            System.out.println("No match: minVal above threshold (" + minVal.get() + " > " + threshold + ")");
            return null;
        }

        Rectangle match = new Rectangle(minLoc.x(), minLoc.y(), template.cols(), template.rows());
        debug("Match found at: " + match, debugMsg);

        template.release();
        base.release();
        convolution.release();
        alpha.release();

        debug("<< Exiting patternMatch()", debugMsg);
        return match;
    }

    public BufferedImage removeBlocks(BufferedImage originalImg, Rectangle maskArea) {
        Mat original = Java2DFrameUtils.toMat(originalImg);
        Mat mask = new Mat(original.size(), CV_8UC1, new Scalar(255));

        Rect rect = new Rect(maskArea.x, maskArea.y, maskArea.width, maskArea.height);
        Mat maskROI = new Mat(mask, rect);
        maskROI.setTo(new Mat(new Scalar(0)));

        Mat output = new Mat(original.size(), original.type());

        bitwise_and(original, original, output, mask);

        BufferedImage outImg = matToBufferedImage(output);

        original.release();
        mask.release();
        maskROI.release();
        output.release();

        return outImg;
    }

    public BufferedImage matToBufferedImage(Mat mat) {
        return bufferedImageConverter.convert(matConverter.convert(mat));
    }

    private void debug(String message, boolean debug) {
        if (debug) System.out.println(message);
    }
}
