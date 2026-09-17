# RemoteInput vendored source — provenance

This directory is a vendored, dependency-complete subset of the RemoteInput native sources used to
build `libRemoteInput.so`. It is **not** an independently versioned RemoteInput revision; it is a
verbatim import of pinned upstream paths. No independent revision number is claimed because none
can be established from the vendored source alone.

## Source

| Item | Value |
|---|---|
| Source repository | `https://github.com/StaticSweep/ChromaScape` (upstream of this fork) |
| Original project | `https://github.com/Brandon-T/RemoteInput` (vendored by the source repository) |
| Pinned commit | `5ea3b995f5d13e19c592a6399973fe7dadb5e656` |
| Imported subtree | `third-party/` |
| Import date | 2026-09-14 (UTC) |

The fork **already** has an unrelated `third_party/` (underscore) directory holding the Windows
`KInput` backend. The upstream `third-party` (hyphen) and the fork `third_party` (underscore) are
different trees and must not be renamed, merged or otherwise conflated.

## Import command

```sh
git archive 5ea3b995f5d13e19c592a6399973fe7dadb5e656 third-party | tar -x
```

followed by removal of the excluded paths listed below.

## Imported paths

All of `third-party/**` except the exclusions below — 278 files:

- `third-party/DEV_README.md` (upstream build guide; attribution/instructions)
- `third-party/RemoteInput/CMakeLists.txt`, `README.md`, `setup.py`
- `third-party/RemoteInput/RemoteInput/**` (Echo, Java, Platform, Plugin, Plugin/JVM,
  Plugin/Python, Injection, plus the top-level `.cxx`/`.hxx` translation units)
- `third-party/RemoteInput/RemoteInput/Thirdparty/**` (`kubo_injector`, `linux_detours`,
  `min_hook`, `rd_route`, `nanobind`) — the vendored dependencies referenced by the two CMake
  files.

## Excluded paths and reasons

| Excluded path | Reason |
|---|---|
| `RemoteInput/.github/**` | CI workflow state, including `build.zip` archived build output — not a build input |
| `RemoteInput/RemoteInput.xcodeproj/**` | macOS IDE workspace state |
| `RemoteInput/RemoteInput.cbp`, `.depend`, `.layout` | Code::Blocks IDE workspace state |
| `RemoteInput/precompiled/**` | Prebuilt `libRemoteInput.dll` binary — not a build input |
| `RemoteInput/.gitignore` | Repository metadata, not a build input |

No source file, header, CMake definition, vendored dependency or license/notice file was excluded.

## Source-tree identity

`import-manifest.sha256` lists a SHA-256 for every one of the 278 imported files, sorted by path.

Identity was verified at import time against the pinned commit:

- imported set vs pinned set (after exclusions): exact match, 278 / 278 files;
- per-file content: `git hash-object` of each working-tree file compared against
  `git rev-parse 5ea3b995:<path>` — **0 differences**.

## Local modifications

**None.** Every imported file is byte-identical to the pinned commit. No local patch has been
applied to the vendored sources. If a patch becomes necessary, it must be added here with its
reason and identity before it is used in a build.

Files added by this story (not part of the import): `PROVENANCE.md` and
`import-manifest.sha256`.

## License and attribution inventory

| Path | License |
|---|---|
| `RemoteInput/Thirdparty/kubo_injector/LICENSE_GPL.txt` | GPL (see file) |
| `RemoteInput/Thirdparty/kubo_injector/LICENSE_LGPL.txt` | LGPL (see file) |
| `RemoteInput/Thirdparty/nanobind/LICENSE` | see file (BSD-style) |
| `RemoteInput/Thirdparty/nanobind/ext/robin_map/LICENSE` | MIT |

### OPEN ITEM — missing top-level RemoteInput license

There is **no license file and no license statement** for the RemoteInput project itself in the
vendored tree: `third-party/RemoteInput/` contains only `README.md`, `CMakeLists.txt`, `setup.py`
and the sources, and `README.md` makes no licensing statement.

This is unresolved. It is recorded here rather than papered over because the specification requires
license completeness *before distribution*. Do not describe this tree as license-complete, and do
not invent a license. Resolving it requires the copyright holder's terms for the
`Brandon-T/RemoteInput` sources.

The absence was subsequently confirmed against upstream itself, not only against this import: the
`Brandon-T/RemoteInput` repository root contains no `LICENSE` and no `COPYING` file, `README.md`
makes no licensing statement, `setup.py` names no license, and GitHub's licence detector reports no
licence for the repository. There are therefore no terms to distribute under.

### Disposition: this tree is not distributed

Because no terms exist, RemoteInput is **not** part of the packaged distribution. The
`assembleLinuxDistribution` task ships no `libRemoteInput.so`, no copy of this tree and no
RemoteInput licence file, and it no longer depends on the licence gate below or on
`stageRemoteInputLinux`. This repository's copy exists for local development and for building the
library on a developer machine; it is not a distributable artifact.

That disposition is a distribution decision, not a licensing resolution: the marker below stays
`unresolved` and no licence file has been invented for it. If the copyright holder's terms are ever
established, the marker is what records the decision, and the gate below is what verifies it.

The gate is enforced mechanically, not just documented: the `verifyRemoteInputDistributionLicence`
task fails unless **both** of the following hold:

1. the machine-readable marker below reads `resolved`; and
2. a non-empty resolved licence is present at **`third-party/RemoteInput/LICENSE`**.

Landing a licence (even a non-empty one) with the marker still `unresolved`, or flipping the marker
without landing the licence, does **not** close the gate. The marker is what records the human
licensing decision, so a placeholder licence file cannot silently satisfy the task. That exact
condition pair is what the task checks; there is no override property. The task is no longer wired
into `assembleLinuxDistribution` — it guards a payload the distribution does not carry — so it must
be run explicitly if its state is to be evidenced.

top-level-licence-resolution: unresolved

Note also that `kubo_injector` is GPL/LGPL, which has distribution implications for any artifact
linking it; that is a distribution decision, not something this import can settle. Not distributing
the tree removes that question along with the rest.
