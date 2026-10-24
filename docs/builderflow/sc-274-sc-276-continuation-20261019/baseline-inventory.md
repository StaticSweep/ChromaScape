# Baseline inventory — 2026-10-19 (plan step 1)

Read-only inventory of review inputs. No secrets are recorded here (variable *names* only).
No unrelated changes were discarded.

## Revisions and working trees

| Project | Revision | Branch | Dirty files |
|---|---|---|---:|
| `C:\Dev\IdeaProjects\micro6` | `a6cf32fce730a4e8ea0808f74a4697967f4233f6` | `codex/sc-1618-micro6` | 112 |
| `C:\Dev\IdeaProjects\.worktrees\sc274-completion` (ChromaScape) | `c791c6d47102bf5971497ab5ece5f48883b79b82` | `codex/sc274-completion-20261015` | 119 |
| `C:\Dev\IdeaProjects\.worktrees\sc1976-micro6` | `429e32978b1027ca913660ef38f69b92d35bb3c0` | `codex/sc1976-finish-20261012` | (see check below) |

## Preserved item-1 baseline (unchanged)

- `ChromaScape\.env.local` → `MICRO6_RUNTIME_SELECTION_REGISTRY_BASE_URL=http://192.168.1.6:8084`.
- Candidate ChromaScape on port 8090 = PID 195400 (debugger/workspace 200, anonymous stabilization 401).
- Replay consumption disabled (`worker=false`); nine terminal `replay_attempts` preserved.

## Preserved source-disabled Micro6 client (8098)

- PID 242640, `-Dmicro6.source.mode=disabled`,
  `-Dmicro6.artifact.identity.path=C:\Dev\IdeaProjects\.worktrees\sc1976-micro6\runelite-client\build\identity\micro6-artifact.properties`.
- Artifact: `.worktrees\sc1976-micro6\runelite-client\build\libs\client-1.13.3-SNAPSHOT-shaded.jar`
  `sha256:893c4f6942ac32ad466ba9c5e86fe436ee4ceca783a81743bbced16d41b4a72b`,
  `build_id=429e32978b1027ca913660ef38f69b92d35bb3c0`, generated `2026-10-15T01:37:27Z`.
- Must remain untouched; a separate owned port/bundle root/experiment config is used instead.

## ChromaScape configuration variable names (values not recorded)

Registry: `MICRO6_RUNTIME_SELECTION_REGISTRY_BASE_URL`, `MICRO6_RUNTIME_SELECTION_REGISTRY_AUTH_TOKEN`.
Source trust (configured): `CHROMASCAPE_SOURCE_PUBLIC_KEY_BASE64`, `CHROMASCAPE_SOURCE_PUBLIC_KEY_ID`,
`CHROMASCAPE_SOURCE_ENVIRONMENT`, `CHROMASCAPE_SOURCE_METADATA_TOKEN`, `CHROMASCAPE_SOURCE_CONTENT_TOKEN`.
Source transport: `MICRO6_SOURCE_TRANSPORT_BASE_URL`, `MICRO6_SOURCE_TRANSPORT_AUTH_TOKEN`, `MICRO6_SOURCE_TRANSPORT_ACTOR`.

## Micro6 development signing variables (micro6\.env.local; names only)

`MICRO6_SOURCE_BUNDLE_ROOT`, `MICRO6_SOURCE_ENVIRONMENT`, `MICRO6_SOURCE_SIGNING_PRIVATE_KEY_BASE64`,
`MICRO6_SOURCE_SIGNING_KEY_ID` (`micro6-sc810-local-ed25519`), `MICRO6_SOURCE_VERIFICATION_PUBLIC_KEY_BASE64`,
`MICRO6_SOURCE_VERIFICATION_KEY_ID`, `MICRO6_SOURCE_METADATA_TOKEN`, `MICRO6_SOURCE_CONTENT_TOKEN`,
`MICRO6_SOURCE_REQUESTS_PER_MINUTE`.

## Task-name correction

`:client:prepareMicro6SourceBundle` (`runelite-client/build.gradle.kts:1181`) is the bundle
producer. `:client:prepareAuthoritativeSourceBundle` does not exist. Retained as dated
history in `../sc-274-sc-276-continuation-20261018/source-runtime-assessment.md`.
