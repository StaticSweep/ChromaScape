package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import org.junit.jupiter.api.Test;

/** Storage exhaustion must not be presented as malformed media. */
class MouseVideoAnalysisWorkerTest {

  @Test
  void boundedStorageExhaustionIsReportedAsQuota() {
    assertThat(
            MouseVideoAnalysisWorker.samplingFailureCode(
                failure("ffmpeg: write error: No space left on device")))
        .isEqualTo(MouseVideoException.CODE_QUOTA_EXCEEDED);
    assertThat(MouseVideoAnalysisWorker.samplingFailureCode(failure("open: ENOSPC")))
        .isEqualTo(MouseVideoException.CODE_QUOTA_EXCEEDED);
  }

  @Test
  void undecodableSourceRemainsADecoderFailure() {
    assertThat(MouseVideoAnalysisWorker.samplingFailureCode(failure("Invalid data found")))
        .isEqualTo(MouseVideoException.CODE_DECODER_FAILED);
    assertThat(MouseVideoAnalysisWorker.samplingFailureCode(failure(null)))
        .isEqualTo(MouseVideoException.CODE_DECODER_FAILED);
  }

  private static MouseVideoProcessRunner.Result failure(String stderr) {
    return new MouseVideoProcessRunner.Result(
        1, "", stderr, false, false, true, false, false, false);
  }
}
