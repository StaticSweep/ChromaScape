package com.chromascape.web.debug.patchloop.dto;

/**
 * Type of analysis prompt.
 *
 * <p>Indicates whether the analysis is for an issue diagnosis or a feature request.
 *
 * @since 2.0.0
 */
public enum PromptType {
  /** Analysis of a bug, failure, or unexpected behavior */
  ISSUE,

  /** Analysis for a new feature or enhancement request */
  FEATURE
}
