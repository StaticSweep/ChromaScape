# SC-1661 — Upstream Commit Ledger

Pinned baseline: `583a05b91687d156b1baa52dbda2b219604c7b39`
Pinned upstream: `5ea3b995f5d13e19c592a6399973fe7dadb5e656` (`StaticSweep/main`, tag `v0.5.0`)
True merge base: `46db1c462752d39bd0c04da630bfe5d35edc117a` ("Update CONTRIBUTING.md to remove outdated sections")

The upstream-only set was **recomputed against this baseline** rather than inherited. It is 15
commits. The earlier count of 15 was recorded against the Fedora comparison; it is
independently confirmed here for the new baseline, and is authoritative only for this pairing.

```
$ git rev-list --left-right --count 583a05b...5ea3b99
292     15
```

## Ledger

| # | Upstream SHA | Subject | Date | Scale | Prereqs | Custom conflicts | Disposition | Owning story | Required verification |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `a87899d` | Fixed HeadlessException on script start (#49) | 2025-12-23 | 2 files, +4/-6 | — | `ChromaScapeApplication.java` is a heavily modified custom file | **Adapt** | SC-1662 | App boots headless; existing Spring context tests pass |
| 2 | `88b7b5f` | Add DemoAgility, moving object support, tightness overload (#52) | 2026-01-10 | 11 files, +703/-110 | 1 | Removes `controller/Controller.java`; rewrites `PointSelector`, `ClickDistribution` — both modified in the baseline | **Adapt** | SC-1662 | Agility demo compiles; `PointSelector` callers across replay/compiler unaffected |
| 3 | `afef25d` | Add minimap class (#53) | 2026-01-12 | 1 file, +96 | 2 | Additive; new class | **Merge** | SC-1662 | Class present and compiles |
| 4 | `db17529` | Screen-capture runtime optimisations (#54) | 2026-01-29 | 7 files, +273/-199 | 2 | **Direct conflict:** rewrites `ScreenManager` (-117) and `ColourContours`, both baseline-modified; adds `MouseOver` | **Adapt — highest-risk capture boundary** | SC-1663 | `ScreenManager` capture/bounds parity tests; recording first-frame readiness |
| 5 | `b7feca0` | Release v0.2.0 (#55) | 2026-01-29 | 1 file, +1/-1 | — | Version string only | **Already satisfied / defer** | SC-1668 | n/a (version metadata) |
| 6 | `ac06b03` | Bump version (#56) | 2026-01-29 | 1 file, +1/-1 | — | Version string only | **Already satisfied / defer** | SC-1668 | n/a |
| 7 | `c662806` | Revise README | 2026-02-01 | 1 file, +28/-10 | — | Fork README diverges | **Defer** (docs, upstream-specific) | SC-1668 | n/a |
| 8 | `0f412fb` | Discord webhook & centre-point logic; remove unused modules; fix KInput makefile (#57) | 2026-02-15 | 30 files, +323/-667 | 2 | Deletes `CVTemplates.sh`; adds `DiscordNotification`; edits `.gitignore` and `checkstyle-suppressions.xml` (both baseline-modified); touches `BaseScript` | **Adapt** | SC-1662 | Checkstyle suppressions preserved; no build regression |
| 9 | `6038886` | Clarify failsafe logic in DemoAgilityScript (#58) | 2026-02-18 | 2 files, +10/-15 | 2, 8 | Script-only | **Merge** | SC-1662 | Script compiles |
| 10 | `5ea2a16` | Optimise exceptions; add MatchResult (#59) | 2026-02-27 | 29 files, +840/-1001 | 2, 8 | Rewrites `Dax`, `BaseScript` and all demo scripts; broad exception-type churn | **Adapt** | SC-1662 | Compile + affected unit tests |
| 11 | **`69ff913`** | **Integrate RemoteInput (#60)** | 2026-04-09 | **350 files, +95,328/-4,643** | 10 | **The dominant boundary.** Deletes `remoteinput/Kinput.java`; deletes `WindowHandler.java` (-157); deletes all of `third_party/KInput/**` incl. binaries; rewrites `VirtualMouseUtils` (+348), `VirtualKeyboardUtils` (+131), `ScreenManager` (-251); adds `RemoteInput.java`, `RemoteInputInterface.java`, `ControlKey.java`, `MouseButton.java` and a vendored `third-party/RemoteInput/**` | **Defer → SC-1662–SC-1667** | SC-1662–SC-1667 | RemoteInput substitution; input abstraction parity; native attachment ownership |
| 12 | `c4de3f4` | Bump version (#61) | 2026-04-09 | 1 file, +1/-1 | — | Version string only | **Already satisfied / defer** | SC-1668 | n/a |
| 13 | `580a619` | Cross-platform and build improvements (#64) | 2026-04-14 | 8 files, +297/-43 | 11 | `settings.gradle.kts` (+5) is baseline-modified; build-shape change | **Adapt — build boundary** | SC-1666 | Composite/subproject settings resolve; `replayTest` etc. still register |
| 14 | `cad0847` | BufferedImage ↔ Mat conversion; MouseOverText extraction (#65) | 2026-05-21 | 12 files, +307/-165 | 11, 13 | **`build.gradle.kts` -78 lines** (baseline carries custom tasks); `ScreenManager` touched again | **Adapt — coordinate-conversion boundary** | SC-1663 | Image conversion round-trip tests; canvas vs screen coordinate contract |
| 15 | `5ea3b99` | RuneLite profile loading; profile optimisation; `onFirstCycle()` (#66) | 2026-07-10 | 9 files, +315/-54 | 11, 13, 14 | `build.gradle.kts` touched; profile-loading behaviour | **Adapt** | SC-1664 | Profile load path; first-cycle hook; startup validation |

## Summary by disposition

| Disposition | Count | Commits |
|---|---|---|
| Merge | 2 | `afef25d`, `6038886` |
| Adapt | 8 | `a87899d`, `88b7b5f`, `db17529`, `0f412fb`, `5ea2a16`, `580a619`, `cad0847`, `5ea3b99` |
| Defer (owning story) | 1 | `69ff913` → SC-1662–SC-1667 |
| Already satisfied / defer | 4 | `b7feca0`, `ac06b03`, `c662806`, `c4de3f4` |
| **Total** | **15** | |

Every upstream-only commit has an owning story or a documented deferral. No commit is
unaccounted for.

## Notable observations

1. **`69ff913` is not a normal commit to merge.** At 350 files and ~95k insertions it vendors
   an entire native project — including `.xcodeproj/xcuserdata/.../UserInterfaceState.xcuserstate`
   and `.github/workflows/build.zip`. Merging it wholesale would import editor state and build
   archives. Its Java-side content (the `remoteinput` package replacement of `Kinput`,
   `VirtualMouseUtils`, `VirtualKeyboardUtils`, `ScreenManager`) must be adapted
   deliberately, and the vendored tree reviewed before inclusion.
2. **Upstream deletes classes the baseline still uses.** `WindowHandler.java` and
   `Kinput.java` are removed by `69ff913`, while both are present in the baseline. These are
   the two sharpest compatibility boundaries — see `compatibility-map.md`.
3. **`build.gradle.kts` is contested by three upstream commits** (`cad0847` -78 lines,
   `580a619`, `5ea3b99`), while the baseline carries custom `replayTest`,
   `replayCertificationTest`, `replayStressCertificationTest`, `cleanChromascape`, `cleanAll`
   and native-library copy tasks. Build-file merges must be per-hunk, never wholesale.
4. Four commits are version-string or README only and satisfy no porting requirement.

## Method

```
git merge-base 583a05b 5ea3b99                    -> 46db1c4
git rev-list --left-right --count 583a05b...5ea3b99 -> 292  15
git rev-list --reverse 583a05b..5ea3b99           -> the 15 commits above
git diff --name-status 583a05b 5ea3b99 -- '*Kinput*' '*RemoteInput*'
```

The baseline is 292 commits ahead of the merge base: that is the custom fork surface, and it
is why upstream integration must be a per-commit adaptation rather than a branch merge.
