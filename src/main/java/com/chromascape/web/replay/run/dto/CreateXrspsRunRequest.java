package com.chromascape.web.replay.run.dto;

import com.chromascape.web.replay.api.dto.ReplayStopPolicy;
import com.chromascape.web.replay.conversion.ConversionMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request for the one-click recording-to-xRSPS v2 flow. */
public record CreateXrspsRunRequest(
    @NotNull ConversionMode conversionMode,
    @NotNull Boolean openViewer,
    @NotNull ReplayStopPolicy stopPolicy,
    @NotBlank
        @Size(min = 8, max = 128)
        @Pattern(
            regexp = "^[A-Za-z0-9._:-]+$",
            message = "idempotencyKey allows [A-Za-z0-9._:-] only")
        String idempotencyKey) {

  public boolean viewerRequested() {
    return Boolean.TRUE.equals(openViewer);
  }
}
