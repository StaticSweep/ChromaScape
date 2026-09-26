# Builder Goal Launcher Contract

The production Builder Goal launcher lives in the Micro6 repository root:

```
C:\Dev\IdeaProjects\micro6\launch-buildergoal-client.ps1
```

ChromaScape never ships an executable copy of the launcher. The copy that used to live at
`docs/launch-buildergoal-client.ps1` is removed; this document is the source of truth for the
launcher contract that ChromaScape enforces.

## Contract version

ChromaScape invokes the launcher with `-ContractVersion` before any preflight or process cleanup.
The launcher prints a single-line JSON document and exits zero:

```json
{"contract_version":3,"validate_only":true,"compile_only":true,"run_task":":client:runBuilderGoal","prepare_task":":client:prepareBuilderGoal","authoritative_run_task":":client:runBuilderGoalAuthoritative","source_mode_env":"MICRO6_SOURCE_MODE","source_modes":["disabled","authoritative"]}
```

ChromaScape requires `contract_version == 3`. An incompatible or missing contract fails with
`LAUNCH_CONFIGURATION_INVALID` before any existing Micro6 process is stopped.

Contract version 3 is not cosmetic. It is the version that **adopts the source mode ChromaScape
declares** instead of letting the executor default that mode from ambient environment state. An
older launcher can start an executor that refuses to bind its port, so it is rejected.

| Field | Meaning |
| --- | --- |
| `contract_version` | Increments when the launcher's switches or task names change incompatibly. |
| `validate_only` | The launcher supports `-ValidateOnly` (non-destructive task discovery). |
| `compile_only` | The launcher supports `-CompileOnly` (`:client:prepareBuilderGoal`). |
| `run_task` | The canonical Gradle run task (`:client:runBuilderGoal`). |
| `prepare_task` | The canonical Gradle compile task (`:client:prepareBuilderGoal`). |
| `authoritative_run_task` | The packaged-JAR run task (`:client:runBuilderGoalAuthoritative`). |
| `source_mode_env` | The environment key that carries the explicit source mode. |
| `source_modes` | The source modes this launcher can resolve. |

## Switches

| Switch | Behavior |
| --- | --- |
| `-ContractVersion` | Print the machine-readable contract JSON and exit 0. |
| `-ValidateOnly` | Run `gradlew.bat --no-daemon --no-watch-fs help --task :client:runBuilderGoal`. Never launches the client and never stops processes. |
| `-CompileOnly` | Run `gradlew.bat --no-daemon --no-watch-fs :client:prepareBuilderGoal`. Compiles Micro6 production classes; never launches the client. |
| `MICRO6_SOURCE_MODE=authoritative` (environment) | Select `:client:runBuilderGoalAuthoritative`; no separate command-line switch is required. |
| `-PayloadFile <path>` | Path to the temporary JSON payload (`clientArgs`) written by ChromaScape. Required for normal launches. |

## Source mode: `disabled` versus `authoritative`

Micro6 has two explicit launch modes. The mode is **always declared**, never inferred from whichever
`MICRO6_SOURCE_*` values happen to exist — a developer `.env` commonly defines all of them, and
inferring from presence used to strand ordinary local launches on the fail-closed authoritative
startup path so the executor never listened on its port.

| | `disabled` (normal local launch) | `authoritative` |
| --- | --- | --- |
| Declared by | ChromaScape's server-owned launch environment | A trusted operator, then the launcher |
| Code source | `:client:runBuilderGoal` (classes directory) | `:client:runBuilderGoalAuthoritative` (shaded JAR only) |
| Source endpoints | Typed `SOURCE_UNAVAILABLE` | Signed promoted bundle only |
| Startup checks | None for source; executor binds normally | Sidecar, build ID, artifact digest, and bundle signature must all match |
| Task execution | Available and dispatch-ready | Available and dispatch-ready |

ChromaScape sets `MICRO6_SOURCE_MODE=disabled` in the launch environment it owns. This is deliberately
**not** a browser-controlled payload field and cannot be overridden by a request.

### Preparing and running an authoritative launch

Authoritative serving requires a bundle generated for the exact packaged bytes:

1. Commit a clean worktree — the source generator refuses a dirty tree, including ignored `.env`
   variants, so it cannot attest source that differs from the revision it records.
2. Provide the signing, verification, environment, and bundle-root values in the environment or local
   configuration. Signing material never belongs in Gradle files, this document, or any log.
3. Run `:client:prepareMicro6SourceBundle`. It builds the shaded JAR, writes its identity sidecar,
   promotes the signed bundle, and re-validates the promoted pointer against the sidecar.
4. Declare `MICRO6_SOURCE_MODE=authoritative` and `MICRO6_SOURCE_BUNDLE_ROOT`, then run
   `:client:runBuilderGoalAuthoritative` (or the launcher with `-Authoritative`).

The authoritative task validates the promoted bundle against the sidecar **before Java starts** and
fails closed. It never falls back to local mode.

## Readiness chain

A launcher command acknowledgement is not executor readiness. The complete chain is:

```text
launcher command accepted
  → Micro6 process started
  → executor listening on 8098
  → /health identity matches
  → dispatch ready
```

leeuwbot reporting **launcher online** only means the launcher can accept and deliver a command. It
says nothing about whether the executor is listening. Only the executor port and `/health` establish
that, and only `dispatch ready` means work can be published.

## Executor startup rejection

Some Micro6 startup refusals can never resolve by waiting. ChromaScape scans the launch log for these
markers and fails the launch immediately with the dedicated classification
`EXECUTOR_STARTUP_REJECTED`, then stops the owned process tree:

| Marker | Cause |
| --- | --- |
| `Local builder executor did not start` | Generic bind refusal. |
| `source_startup_failed` | Any fail-closed authoritative source violation, including an unknown mode or a bundle that does not match the running artifact. |
| `no artifact sidecar configured` | Authoritative identity requested with no sidecar. |
| `running packaged artifact identity is unavailable` | The running artifact could not be attested. |

The launch-operation response carries the sanitized reason in `failureKind` and the failure detail.
ChromaScape does not wait out the readiness timeout for these, because the executor will never bind
while RuneLite stays open.

## Preflight order enforced by ChromaScape

For a replacement launch (Run Task / Launch Account on a non-ready or mismatched executor):

1. Inspect the current executor; reuse it when account, world, and dispatch readiness all match.
2. `-ContractVersion` — verify launcher contract version 3.
3. `-ValidateOnly` — validate the launch configuration.
4. `-CompileOnly` — compile Micro6 production classes.
5. Only after validation and compilation succeed, terminate the verified existing process tree.
6. Confirm the executor port is free.
7. Launch `:client:runBuilderGoal` with `MICRO6_SOURCE_MODE=disabled`, and treat any fatal startup
   marker as an immediate `EXECUTOR_STARTUP_REJECTED` failure rather than waiting for readiness.

A validation failure (`LAUNCH_CONFIGURATION_INVALID`), a compile failure (`GRADLE_TASK_FAILED`), a
preflight timeout (`GRADLE_TIMEOUT`), or an executor startup rejection (`EXECUTOR_STARTUP_REJECTED`)
all leave no orphaned client running.

## Linux adapter

On Linux ChromaScape uses the contract-v3 shell adapter in the Micro6 repository root
(`launch-buildergoal-client.sh`, delegating to `scripts/remote-launcher/launcher.py`) instead of
a direct `./gradlew :client:runBuilderGoal --args=...` invocation, because
`:client:runBuilderGoal` requires `MICRO6_CLIENT_ARGS_FILE` and only the adapter supplies it.
The JSON contract, the preflight order above, and the `clientArgs` payload schema are identical;
only the switch spelling differs (`--contract-version`, `--validate-only`, `--compile-only`,
`--payload-file <path>`), invoked via `bash` so the adapter needs no execute bit.
