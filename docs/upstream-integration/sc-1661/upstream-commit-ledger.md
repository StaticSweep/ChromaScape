# SC-1661 — Upstream Commit Ledger

Pinned candidate baseline: `583a05b91687d156b1baa52dbda2b219604c7b39`
Reconciled code baseline: `966e0e3`
Pinned upstream: `5ea3b995f5d13e19c592a6399973fe7dadb5e656` (`StaticSweep/main`, tag `v0.5.0`)
True merge base: `46db1c462752d39bd0c04da630bfe5d35edc117a` ("Update CONTRIBUTING.md to remove outdated sections")

The upstream-only set was **recomputed against this baseline** rather than inherited.

```
$ git rev-list --left-right --count 583a05b...5ea3b99
292     15
```

Measured from the reconciled code baseline instead, the ahead count is 4 higher — the four commits
between `583a05b` and `966e0e3` are two documentation commits and the two carries:

```
$ git rev-list --left-right --count 966e0e3...5ea3b99
296     15
```

The 15 upstream-only commits — the set this ledger dispositions — are unchanged either way, because
none of the four intervening commits touches upstream ancestry. Report a count only next to the
revision it was measured from: commit counts include documentation and do not measure the size of the
functional difference. See `integration-base.md` for the full divergence table.

## Authoritative owning-story scopes

The earlier revision of this ledger assigned owners that do not match the actual stories. These
are the authoritative scopes, and every owner below is drawn from them:

| Story | Actual scope |
|---|---|
| SC-1662 | Native RemoteInput build and library loading |
| SC-1663 | Process discovery and explicit target selection |
| SC-1664 | Input attachment and mouse/keyboard lifecycle |
| SC-1665 | Rendered-buffer capture and screen API compatibility |
| SC-1666 | Recording, debugger, and remote-launch integration |
| SC-1667 | Fedora startup and attachment diagnostics |
| SC-1668 | Host qualification and integration PR |

Where an upstream change does not fit these scopes it is recorded as an **explicit deferral**, not
assigned to a story whose purpose differs.

## Ledger

| # | Upstream SHA | Subject | Date | Scale | Prereqs | Custom conflicts | Disposition | Actual implementation owner | Required verification |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `a87899d` | Fixed HeadlessException on script start (#49) | 2025-12-23 | 2 files, +4/-6 | — | `ChromaScapeApplication.java` is a heavily modified custom file | **Adapt** | **SC-1668** — headless host startup is host qualification, not a build/capture story | App boots without a display; existing Spring context tests pass |
| 2 | `88b7b5f` | Add DemoAgility, moving object support, tightness overload (#52) | 2026-01-10 | 11 files, +703/-110 | 1 | Removes `controller/Controller.java`; rewrites `PointSelector`, `ClickDistribution` — both baseline-modified | **Adapt (split)** | **SC-1664** for the movement/`PointSelector`/`ClickDistribution` changes — those are input-path behaviour. **The DemoAgility scenario itself is an explicit deferral** (upstream sample script, no Linux-port requirement) | `PointSelector` callers across replay/compiler unaffected; movement behaviour parity |
| 3 | `afef25d` | Add minimap class (#53) | 2026-01-12 | 1 file, +96 | 2 | Additive; new class | **Adapt (additive)** | **SC-1665** — the minimap reads the rendered buffer, so it lands with the screen API | Class present, compiles, reads only through the retained screen API |
| 4 | `db17529` | Screen-capture runtime optimisations (#54) | 2026-01-29 | 7 files, +273/-199 | 2 | **Direct conflict:** rewrites `ScreenManager` (-117) and `ColourContours`, both baseline-modified; adds `MouseOver` | **Adapt — highest-risk capture boundary** | **SC-1665** | `ScreenManager` capture/bounds parity tests; recording first-frame readiness |
| 5 | `b7feca0` | Release v0.2.0 (#55) | 2026-01-29 | 1 file, +1/-1 | — | Version string only | **Explicit deferral** | **None** — no porting requirement; the fork does not track upstream version strings | n/a |
| 6 | `ac06b03` | Bump version (#56) | 2026-01-29 | 1 file, +1/-1 | — | Version string only | **Explicit deferral** | **None** — as above | n/a |
| 7 | `c662806` | Revise README | 2026-02-01 | 1 file, +28/-10 | — | Fork README diverges | **Explicit deferral** | **None** — upstream-facing documentation with no porting requirement | n/a |
| 8 | `0f412fb` | Discord webhook & centre-point logic; remove unused modules; fix KInput makefile (#57) | 2026-02-15 | 30 files, +323/-667 | 2 | Deletes `CVTemplates.sh`; edits `.gitignore` and `checkstyle-suppressions.xml` (both baseline-modified); touches `BaseScript` | **Adapt (split)** | **SC-1662** for the KInput makefile / module-removal build parts; **SC-1664** for the centre-point input logic. **The Discord webhook is an explicit deferral** (notification feature unrelated to the Linux port) | Checkstyle suppressions preserved; no build regression |
| 9 | `6038886` | Clarify failsafe logic in DemoAgilityScript (#58) | 2026-02-18 | 2 files, +10/-15 | 2, 8 | Script-only | **Explicit deferral** | **None** — same DemoAgility sample script deferred at #2 | n/a |
| 10 | `5ea2a16` | Optimise exceptions; add MatchResult (#59) | 2026-02-27 | 29 files, +840/-1001 | 2, 8 | Rewrites `Dax`, `BaseScript` and all demo scripts; broad exception-type churn | **Adapt** | **SC-1662** — not because it is a native-build change, but because it is the direct prerequisite of `69ff913`, which SC-1662 opens | Compile + affected unit tests |
| 11 | **`69ff913`** | **Integrate RemoteInput (#60)** | 2026-04-09 | **350 files, +95,328/-4,643** | 10 | **The dominant boundary.** Deletes `remoteinput/Kinput.java`; deletes `WindowHandler.java` (-157); deletes all of `third_party/KInput/**` incl. binaries; rewrites `VirtualMouseUtils` (+348), `VirtualKeyboardUtils` (+131), `ScreenManager` (-251); adds `RemoteInput.java`, `RemoteInputInterface.java`, `ControlKey.java`, `MouseButton.java` and a vendored `third-party/RemoteInput/**` | **Defer → split across SC-1662–SC-1667** | **SC-1662** native build + library loading; **SC-1663** the `WindowHandler` deletion and process/target selection rewrite; **SC-1664** the `Kinput` deletion and input attachment; **SC-1665** the `ScreenManager` rewrite; **SC-1666** recording/debugger integration; **SC-1667** remote-launch integration | RemoteInput substitution; input abstraction parity; single native attachment ownership |
| 12 | `c4de3f4` | Bump version (#61) | 2026-04-09 | 1 file, +1/-1 | — | Version string only | **Explicit deferral** | **None** — as #5/#6 | n/a |
| 13 | `580a619` | Cross-platform and build improvements (#64) | 2026-04-14 | 8 files, +297/-43 | 11 | `settings.gradle.kts` (+5) is baseline-modified; build-shape change | **Adapt — build boundary** | **SC-1662** | Composite/subproject settings resolve; `replayTest` etc. still register |
| 14 | `cad0847` | BufferedImage ↔ Mat conversion; MouseOverText extraction (#65) | 2026-05-21 | 12 files, +307/-165 | 11, 13 | **`build.gradle.kts` -78 lines** (baseline carries custom tasks); `ScreenManager` touched again | **Adapt — coordinate-conversion boundary** | **SC-1665**; the `build.gradle.kts` hunk is **SC-1662**'s to merge per-hunk | Image conversion round-trip tests; canvas vs screen coordinate contract |
| 15 | `5ea3b99` | RuneLite profile loading; profile optimisation; `onFirstCycle()` (#66) | 2026-07-10 | 9 files, +315/-54 | 11, 13, 14 | `build.gradle.kts` touched; profile-loading behaviour | **Adapt** | **SC-1668** — client start-up/profile integration is host-qualification work; the `build.gradle.kts` hunk is **SC-1662**'s | Profile load path; first-cycle hook; startup validation |

## Summary by disposition

| Disposition | Count | Commits |
|---|---|---|
| Adapt / merge, made implementable by an owner | 10 | `a87899d`, `88b7b5f`, `afef25d`, `db17529`, `0f412fb`, `5ea2a16`, `69ff913`, `580a619`, `cad0847`, `5ea3b99` |
| **Explicit deferral** (no story owns it, and none is assigned) | 5 | `b7feca0`, `ac06b03`, `c662806`, `6038886`, `c4de3f4` |
| **Total** | **15** | |

Every upstream-only commit either has an actual implementation owner drawn from the authoritative
scopes, or is an explicit deferral with its reason recorded. No commit is assigned to a story whose
purpose differs from the change.

## Notable observations

1. **`69ff913` is not a normal commit to merge.** At 350 files and ~95k insertions it vendors an
   entire native project — including `.xcodeproj/xcuserdata/.../UserInterfaceState.xcuserstate` and
   `.github/workflows/build.zip`. Merging it wholesale would import editor state and build archives.
   It must be split across SC-1662–SC-1667 and the vendored tree reviewed before inclusion.
2. **Upstream deletes classes the baseline still uses.** `WindowHandler.java` and `Kinput.java` are
   removed by `69ff913` while both are present in the baseline. Since SC-1663 owns process discovery
   and target selection, and SC-1664 owns input attachment, those are the two stories that must land
   a replacement *before* any deletion — see `compatibility-map.md`.
3. **`build.gradle.kts` is contested by three upstream commits** (`cad0847` -78 lines, `580a619`,
   `5ea3b99`), while the baseline carries custom `replayTest`, `replayCertificationTest`,
   `replayStressCertificationTest`, `cleanChromascape`, `cleanAll` and native-library copy tasks.
   Merges must be per-hunk, never wholesale, and SC-1662 owns the build surface.
4. **Five commits are version-string, README or sample-script changes** and satisfy no porting
   requirement. They are explicitly deferred rather than assigned.

## Method

```
git merge-base 583a05b 5ea3b99                     -> 46db1c4
git rev-list --left-right --count 583a05b...5ea3b99 -> 292  15
git rev-list --reverse 583a05b..5ea3b99            -> the 15 commits above
git diff --name-status 583a05b 5ea3b99 -- '*Kinput*' '*RemoteInput*'
```

The baseline is 292 commits ahead of the merge base: that is the custom fork surface, and it is why
upstream integration must be a per-commit adaptation rather than a branch merge.
