package com.chromascape.utils.core.input.remoteinput;

import com.chromascape.utils.core.input.InputSessionDiagnostic;
import com.chromascape.utils.core.input.InputSessionException;
import com.chromascape.utils.core.screen.window.ProcessIdentity;
import com.chromascape.utils.core.screen.window.ProcessManager;
import com.chromascape.utils.core.screen.window.SelectionValidation;
import com.chromascape.utils.core.screen.window.WindowSelectionService;

/**
 * Shared read-only selection fencing for both session backends.
 *
 * <p>Every session operation re-checks that the observed selection generation is still current and
 * that the selected process is still the exact same process, so an exited or recycled target can
 * never receive input.
 */
public final class SelectionFencing {

  private SelectionFencing() {}

  /**
   * Validates the selection against the observed generation and identity.
   *
   * @param selection the selection service
   * @param processManager the platform discovery implementation
   * @param identity the identity the session is bound to
   * @param generation the generation the session observed at open time
   * @throws InputSessionException when the selection changed, exited, or was replaced
   */
  public static void requireValid(
      WindowSelectionService selection,
      ProcessManager processManager,
      ProcessIdentity identity,
      long generation) {
    if (selection.getSelectedGeneration() != generation) {
      throw failure(
          InputSessionDiagnostic.STALE_GENERATION,
          identity,
          generation,
          "Target selection changed");
    }
    SelectionValidation validation = selection.validateSelected(processManager, generation);
    if (validation != SelectionValidation.VALID) {
      InputSessionDiagnostic diagnostic =
          switch (validation) {
            case EXITED -> InputSessionDiagnostic.TARGET_EXITED;
            case REPLACED -> InputSessionDiagnostic.TARGET_REPLACED;
            case NOT_LOCAL -> InputSessionDiagnostic.NOT_LOCAL;
            case STALE_GENERATION -> InputSessionDiagnostic.STALE_GENERATION;
            case NONE -> InputSessionDiagnostic.NO_SELECTION;
            default -> InputSessionDiagnostic.STALE_SELECTION;
          };
      throw failure(diagnostic, identity, generation, "Target selection is no longer valid");
    }
  }

  /**
   * Builds a diagnostic exception bound to the session's identity and generation.
   *
   * @param diagnostic the stable category
   * @param identity the bound identity, or {@code null}
   * @param generation the observed generation
   * @param detail operator-facing detail
   * @return the exception
   */
  static InputSessionException failure(
      InputSessionDiagnostic diagnostic, ProcessIdentity identity, long generation, String detail) {
    return new InputSessionException(diagnostic, identity, generation, detail);
  }
}
