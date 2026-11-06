package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/** A uniform ordinal stride whose exact PTS spacing respects the sampling ceiling, even for VFR. */
final class MouseVideoSampleStride {
  static final String METHOD = "pts-window-stride/v1";
  private static final BigInteger SCALE = BigInteger.valueOf(1_000_000L);

  private MouseVideoSampleStride() {}

  static int compute(MouseVideoFrameIndexService.FrozenFrameIndex index, double fps, int cap) {
    try {
      if (!index.complete() || index.frames().isEmpty() || !Double.isFinite(fps)) {
        throw new IllegalArgumentException("Incomplete sampling clock");
      }
      BigInteger numerator = new BigInteger(index.timeBaseNumerator());
      BigInteger denominator = new BigInteger(index.timeBaseDenominator());
      if (numerator.signum() <= 0 || denominator.signum() <= 0) {
        throw new IllegalArgumentException("Invalid sampling timebase");
      }
      BigInteger rate =
          BigDecimal.valueOf(Math.max(1.0, Math.min(60.0, fps)))
              .movePointRight(6)
              .setScale(0, RoundingMode.DOWN)
              .toBigIntegerExact();
      BigInteger factor = numerator.multiply(rate);
      BigInteger threshold = denominator.multiply(SCALE);
      var frames = index.frames();
      BigInteger previous = null;
      for (var frame : frames) {
        BigInteger pts = new BigInteger(frame.pts());
        if (previous != null && pts.compareTo(previous) < 0) {
          throw new IllegalArgumentException("Non-monotonic sampling clock");
        }
        previous = pts;
      }
      int step = 1;
      int end = 0;
      for (int start = 0; start < frames.size(); start++) {
        BigInteger pts = new BigInteger(frames.get(start).pts());
        // The largest count in any interval shorter than 1/fps is a safe global stride.
        // Duplicated timestamps stay distinct source ordinals but cannot be oversampled.
        while (end < frames.size()
            && new BigInteger(frames.get(end).pts())
                    .subtract(pts)
                    .multiply(factor)
                    .compareTo(threshold)
                < 0) {
          end++;
        }
        step = Math.max(step, end - start);
      }
      long frameCap = Math.max(1, cap);
      return Math.max(step, (int) ((frames.size() + frameCap - 1) / frameCap));
    } catch (RuntimeException invalid) {
      throw new MouseVideoException(
          MouseVideoException.CODE_DECODER_FAILED,
          "Source timestamps cannot support bounded visual sampling",
          invalid);
    }
  }
}
