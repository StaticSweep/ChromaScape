package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Summary of retest results during patch loop execution.
 *
 * <p>Tracks the number of retests performed, their outcomes, and impact metrics.
 *
 * @since 2.1.0
 */
public class RetestSummaryDto {

  @JsonProperty("totalRetests")
  private Integer totalRetests;

  @JsonProperty("successfulRetests")
  private Integer successfulRetests;

  @JsonProperty("failedRetests")
  private Integer failedRetests;

  @JsonProperty("lastRetestSuccess")
  private Boolean lastRetestSuccess;

  @JsonProperty("improvementsCount")
  private Integer improvementsCount;

  @JsonProperty("regressionsCount")
  private Integer regressionsCount;

  public RetestSummaryDto() {}

  public Integer getTotalRetests() {
    return totalRetests;
  }

  public void setTotalRetests(Integer totalRetests) {
    this.totalRetests = totalRetests;
  }

  public Integer getSuccessfulRetests() {
    return successfulRetests;
  }

  public void setSuccessfulRetests(Integer successfulRetests) {
    this.successfulRetests = successfulRetests;
  }

  public Integer getFailedRetests() {
    return failedRetests;
  }

  public void setFailedRetests(Integer failedRetests) {
    this.failedRetests = failedRetests;
  }

  public Boolean getLastRetestSuccess() {
    return lastRetestSuccess;
  }

  public void setLastRetestSuccess(Boolean lastRetestSuccess) {
    this.lastRetestSuccess = lastRetestSuccess;
  }

  public Integer getImprovementsCount() {
    return improvementsCount;
  }

  public void setImprovementsCount(Integer improvementsCount) {
    this.improvementsCount = improvementsCount;
  }

  public Integer getRegressionsCount() {
    return regressionsCount;
  }

  public void setRegressionsCount(Integer regressionsCount) {
    this.regressionsCount = regressionsCount;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    RetestSummaryDto that = (RetestSummaryDto) o;
    return Objects.equals(totalRetests, that.totalRetests)
        && Objects.equals(successfulRetests, that.successfulRetests)
        && Objects.equals(failedRetests, that.failedRetests)
        && Objects.equals(lastRetestSuccess, that.lastRetestSuccess)
        && Objects.equals(improvementsCount, that.improvementsCount)
        && Objects.equals(regressionsCount, that.regressionsCount);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        totalRetests,
        successfulRetests,
        failedRetests,
        lastRetestSuccess,
        improvementsCount,
        regressionsCount);
  }

  @Override
  public String toString() {
    return "RetestSummaryDto{"
        + "totalRetests="
        + totalRetests
        + ", successfulRetests="
        + successfulRetests
        + ", failedRetests="
        + failedRetests
        + ", lastRetestSuccess="
        + lastRetestSuccess
        + ", improvementsCount="
        + improvementsCount
        + ", regressionsCount="
        + regressionsCount
        + '}';
  }
}
