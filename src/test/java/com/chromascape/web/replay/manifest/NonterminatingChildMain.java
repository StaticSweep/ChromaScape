package com.chromascape.web.replay.manifest;

/**
 * Deliberately nonterminating child used by the fresh-JVM cleanup regression in
 * {@code ScenarioManifestFreshJvmDeterminismTest}. It reports startup and then sleeps far beyond
 * the harness's bounded termination window, so the test can prove the harness reaps every child it
 * starts instead of leaking it.
 */
public final class NonterminatingChildMain {

  private NonterminatingChildMain() {}

  public static void main(String[] args) throws Exception {
    System.out.println("nonterminating child started pid=" + ProcessHandle.current().pid());
    System.out.flush();
    Thread.sleep(600_000L);
  }
}
