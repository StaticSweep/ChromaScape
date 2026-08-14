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
{"contract_version":2,"validate_only":true,"compile_only":true,"run_task":":client:runBuilderGoal","prepare_task":":client:prepareBuilderGoal"}
```

ChromaScape requires `contract_version == 2`. An incompatible or missing contract fails with
`LAUNCH_CONFIGURATION_INVALID` before any existing Micro6 process is stopped.

| Field | Meaning |
| --- | --- |
| `contract_version` | Increments when the launcher's switches or task names change incompatibly. |
| `validate_only` | The launcher supports `-ValidateOnly` (non-destructive task discovery). |
| `compile_only` | The launcher supports `-CompileOnly` (`:client:prepareBuilderGoal`). |
| `run_task` | The canonical Gradle run task (`:client:runBuilderGoal`). |
| `prepare_task` | The canonical Gradle compile task (`:client:prepareBuilderGoal`). |

## Switches

| Switch | Behavior |
| --- | --- |
| `-ContractVersion` | Print the machine-readable contract JSON and exit 0. |
| `-ValidateOnly` | Run `gradlew.bat --no-daemon --no-watch-fs help --task :client:runBuilderGoal`. Never launches the client and never stops processes. |
| `-CompileOnly` | Run `gradlew.bat --no-daemon --no-watch-fs :client:prepareBuilderGoal`. Compiles Micro6 production classes; never launches the client. |
| `-PayloadFile <path>` | Path to the temporary JSON payload (`clientArgs`) written by ChromaScape. Required for normal launches. |

## Preflight order enforced by ChromaScape

For a replacement launch (Run Task / Launch Account on a non-ready or mismatched executor):

1. Inspect the current executor; reuse it when account, world, and dispatch readiness all match.
2. `-ContractVersion` — verify launcher contract version 2.
3. `-ValidateOnly` — validate the launch configuration.
4. `-CompileOnly` — compile Micro6 production classes.
5. Only after validation and compilation succeed, terminate the verified existing process tree.
6. Confirm the executor port is free.
7. Launch `:client:runBuilderGoal`.

A validation failure (`LAUNCH_CONFIGURATION_INVALID`), a compile failure (`GRADLE_TASK_FAILED`), or a
preflight timeout (`GRADLE_TIMEOUT`) all leave the existing client running.
