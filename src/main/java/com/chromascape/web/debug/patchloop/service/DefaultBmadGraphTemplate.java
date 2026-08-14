package com.chromascape.web.debug.patchloop.service;

/**
 * Default BMAD (Bot Metric Analysis & Diagnostics) graph template.
 *
 * <p>Provides the standard Mermaid graph structure for the run → evidence → analyze → patch → apply
 * → validate → retest loop.
 *
 * <p>This template is used by the simple API ({@code /execute}) when delegating to the graph
 * orchestrator, ensuring a canonical execution path.
 *
 * @since 2.1.0
 */
public final class DefaultBmadGraphTemplate {

  // Three-edge design (success → end, retry → analyze, failure → end) is required because
  // the graph compiler's cycle detector (LoopGraph.detectCycle) only skips RETRY edges.
  // The original two-edge version (success → end, failure → analyze) created a non-RETRY cycle
  // around the evaluate node that caused GraphCompilationException at compile time.
  private static final String DEFAULT_BMAD_MERMAID =
      """
      graph TD;
        A[start] --> B[run_script]
        B --> C[collect_evidence]
        C --> D[analyze]
        D --> E[generate_patch]
        E --> F[apply_patch]
        F --> G[validate]
        G --> H[retest]
        H --> I[evaluate_success]
        I -.success.-> J[end]
        I -.retry.-> D
        I -.failure.-> J
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
   *   <li>start → run_script → collect_evidence → analyze → generate_patch → apply_patch → validate
   *       → retest → evaluate_success
   *   <li>On success: evaluate_success → end
   *   <li>On retry: evaluate_success → analyze (loop back)
   *   <li>On terminal failure: evaluate_success → end
   * </ul>
   *
   * @return the Mermaid graph source code
   */
  public static String getTemplate() {
    return DEFAULT_BMAD_MERMAID;
  }
}
