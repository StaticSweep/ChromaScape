package com.chromascape.utils.domain.zones;

import org.bytedeco.javacv.Java2DFrameUtils;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC1;
import static org.bytedeco.opencv.global.opencv_core.bitwise_and;

public class MaskZones {

    public static BufferedImage maskZones(BufferedImage originalImg, Rectangle maskArea) {
        Mat original = Java2DFrameUtils.toMat(originalImg);
        Mat mask = new Mat(original.size(), CV_8UC1, new Scalar(255));

        Rect rect = new Rect(maskArea.x, maskArea.y, maskArea.width, maskArea.height);
        Mat maskROI = new Mat(mask, rect);
        maskROI.setTo(new Mat(new Scalar(0)));

        Mat output = new Mat(original.size(), original.type());

        bitwise_and(original, original, output, mask);

        BufferedImage outImg = Java2DFrameUtils.toBufferedImage(output);

        original.release();
        mask.release();
        maskROI.release();
        output.release();

        return outImg;
    }
}
