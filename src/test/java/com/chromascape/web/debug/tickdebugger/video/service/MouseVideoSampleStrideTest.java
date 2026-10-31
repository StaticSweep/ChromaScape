package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MouseVideoSampleStrideTest {
  private MouseVideoFrameIndexService.FrozenFrameIndex index(
      List<String> pts, String numerator, String denominator) {
    return new MouseVideoFrameIndexService.FrozenFrameIndex(
        "index",
        "frame-index/v2",
        true,
        numerator + "/" + denominator,
        numerator,
        denominator,
        pts.stream().map(p -> new MouseVideoFrameIndexService.Frame(p, "0", "0", false)).toList());
  }

  @Test
  void burstyVfrCannotExceedConfiguredSpacingDespiteItsLowAverageRate() {
    List<String> pts = new ArrayList<>();
    for (int i = 0; i < 60; i++) pts.add(Integer.toString(i));
    pts.addAll(List.of("180", "200", "220"));
    var index = index(pts, "1", "120");
    int step = MouseVideoSampleStride.compute(index, 30, 3600);
    assertThat(step).isEqualTo(4);
    for (int i = step; i < pts.size(); i += step) {
      assertThat(Integer.parseInt(pts.get(i)) - Integer.parseInt(pts.get(i - step)))
          .isGreaterThanOrEqualTo(4);
    }
  }

  @Test
  void eightyFpsIsReducedBelowTheHardSixtyFpsCeiling() {
    List<String> pts = new ArrayList<>();
    for (int i = 0; i < 80; i++) pts.add(Integer.toString(i));
    assertThat(MouseVideoSampleStride.compute(index(pts, "1", "80"), 60, 3600)).isEqualTo(2);
  }

  @Test
  void repeatedPtsRemainSourceOrdinalsWithoutDuplicatedSamplingTimes() {
    var pts = List.of("0", "0", "0", "0", "1", "1", "1", "1", "2");
    assertThat(MouseVideoSampleStride.compute(index(pts, "1", "30"), 30, 3600)).isEqualTo(4);
  }

  @Test
  void hugeOffsetsAndRationalTimebasesDoNotRoundThroughDouble() {
    List<String> pts = new ArrayList<>();
    var base = new BigInteger("1000000000000000000000000");
    for (int i = 0; i < 80; i++) pts.add(base.add(BigInteger.valueOf(i)).toString());
    assertThat(MouseVideoSampleStride.compute(index(pts, "1001", "30000"), 30, 3600)).isEqualTo(1);
  }

  @Test
  void theFrameCapStillBoundsTheNumberOfExtractedSamples() {
    List<String> pts = new ArrayList<>();
    for (int i = 0; i < 10000; i++) pts.add(Integer.toString(i));
    int step = MouseVideoSampleStride.compute(index(pts, "1", "120"), 60, 32);
    assertThat((10000L + step - 1) / step).isLessThanOrEqualTo(32);
  }

  @Test
  void invalidOrReversingClocksAreRefused() {
    assertThatThrownBy(
            () ->
                MouseVideoSampleStride.compute(index(List.of("0", "2", "1"), "1", "30"), 30, 3600))
        .isInstanceOf(MouseVideoException.class);
    assertThatThrownBy(
            () -> MouseVideoSampleStride.compute(index(List.of("0", "1"), "0", "30"), 30, 3600))
        .isInstanceOf(MouseVideoException.class);
    assertThatThrownBy(
            () ->
                MouseVideoSampleStride.compute(
                    index(List.of("0", "1"), "1", "30"), Double.NaN, 3600))
        .isInstanceOf(MouseVideoException.class);
  }
}
