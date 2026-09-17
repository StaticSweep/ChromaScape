package com.chromascape.utils.core.screen.window;

/**
 * A user selection bound to the neutral process identity and to the selection generation at which
 * it was made.
 *
 * @param window the selected window value
 * @param identity the selected process identity; may be null for legacy selections without
 *     enrichment
 * @param generation monotonically increasing selection generation
 */
public record TargetSelection(WindowInfo window, ProcessIdentity identity, long generation) {}
