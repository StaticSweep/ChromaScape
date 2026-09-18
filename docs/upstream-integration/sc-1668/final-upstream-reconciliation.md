# SC-1668 final upstream reconciliation

Pinned upstream: `5ea3b995f5d13e19c592a6399973fe7dadb5e656`.

The original 15-commit inventory remains authoritative. This table records the final integration
outcome rather than implying that upstream commits were cherry-picked wholesale.

| Upstream | Final outcome |
|---|---|
| `a87899d` | Adapted in SC-1668: AWT mode is chosen once before Spring, respects an explicit operator value, supports display-backed Fedora, and stays headless without a display. Request handlers no longer mutate global AWT state. |
| `88b7b5f` | Split: required input/point-selection behavior is represented by the SC-1664 session/input seam; DemoAgility remains explicitly deferred as an unrelated sample. |
| `afef25d` | Superseded by the retained `ZoneManager`/`SubZoneMapper` minimap API and SC-1665 frame source; no duplicate upstream Minimap type was added. |
| `db17529` | Adapted by SC-1665 through `TargetFrameSource`, `ScreenManager`, freshness, decoding, image conversion, and bounded capture diagnostics while preserving custom recording callers. |
| `b7feca0` | Deferred: upstream version string only. |
| `ac06b03` | Deferred: upstream version string only. |
| `c662806` | Deferred: upstream-facing README does not describe this customized fork. |
| `0f412fb` | Split: relevant native/input behavior is superseded by SC-1662/SC-1664; Discord webhook remains deferred and custom build/checkstyle surfaces remain intact. |
| `6038886` | Deferred with DemoAgility. |
| `5ea2a16` | Superseded at the RemoteInput boundary: the fork retains its exception model and does not import the broad demo/Dax churn or unused MatchResult type. Compilation and native/session tests verify the adapted path. |
| `69ff913` | Adapted across SC-1662–SC-1667: pinned native source/build metadata, neutral discovery, identity-fenced input lifecycle, rendered capture, recording/debugger routing, and Fedora preflight. Vendored editor/archive state and unlicensed distribution payload remain excluded. |
| `c4de3f4` | Deferred: upstream version string only. |
| `580a619` | Adapted by SC-1662 build/loading work without deleting fork-specific Gradle tasks or Windows native behavior. |
| `cad0847` | Adapted by SC-1665 detached BufferedImage/Mat conversion and the retained coordinate seam; contested build hunks were reconciled per-hunk. |
| `5ea3b99` | Adapted in SC-1668: one-time first-cycle hook added; host-native `~/.runelite/profiles2` behavior retained. Flatpak/Bolt lookup, profile snapshot churn, version bump, sample changes, and cosmetic template text are intentionally not imported. |

No upstream-only commit is left without a final disposition. “Superseded” means the required behavior
is implemented through a fork-native boundary and the upstream type or broad refactor is unnecessary;
it does not claim byte-for-byte equivalence.
