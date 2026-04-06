package com.chromascape.web.debug.patchloop.service;

/**
 * Default BMAD (Bot Metric Analysis & Diagnostics) graph template.
 *
 * <p>Provides the standard Mermaid graph structure for the run → evidence → analyze → retest loop.
 *
 * <p>This template is used by the simple API ({@code /execute}) when delegating to the graph
 * orchestrator, ensuring a canonical execution path.
 *
 * @since 2.1.0
 */
public final class DefaultBmadGraphTemplate {

  private static final String DEFAULT_BMAD_MERMAID =
      """
      graph TD;
        A[start] --> B[run_script]
        B --> C[collect_evidence]
        C --> D[analyze]
        D --> E[retest]
        E --> F[evaluate_success]
        F -.success.-> G[end]
        F -.failure.-> D
      """;

  private DefaultBmadGraphTemplate() {
    // Utility class - prevent instantiation
  }

  /**
   * Returns the default BMAD graph Mermaid source.
   *
   * <p>The graph defines the standard BMAD loop:
   *
   * <ul>
   *   <li>start → run_script → collect_evidence → analyze → retest → evaluate_success
   *   <li>On success: evaluate_success → end
   *   <li>On failure: evaluate_success → analyze (loop back)
   * </ul>
   *
   * @return the Mermaid graph source code
   */
  public static String getTemplate() {
    return DEFAULT_BMAD_MERMAID;
  }
}
