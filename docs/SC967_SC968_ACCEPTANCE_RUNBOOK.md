# SC-967 / SC-968 native acceptance

This is the story-closing acceptance path. Run it from native Windows PowerShell, never WSL. It
uses clean checkouts, launches only processes it owns, and refuses stale runtime evidence.

## Preconditions

- Native Java 17, Maven, Go, and the repository Gradle wrappers are available.
- Clean checkouts exist for Contracts, LeeuwBotAPI, ChromaScape, Micro6, and standard RuneLite.
- Micro6 `capture-contract.lock` points at the exact Contracts revision.
- The candidate signing key and registry trust directory represent the same configured key ID.
- ChromaScape port 8090 and the configured candidate-registry port are free.

Set the following in the current PowerShell process. Keep tokens and private-key material out of
shell history, launcher arguments, repository files, and generated evidence.

```powershell
$env:SC968_CAPTURE_TOKEN = '<capture bearer token>'
$env:SC968_OPERATOR_TOKEN = '<operator bearer token>'
$env:SC968_OPERATOR_ID = '<auditable operator id>'
$env:SC968_REGISTRY_TOKEN = '<candidate registry token>'
$env:SC968_PUBLIC_KEY_RING = 'C:\secure\candidate-public-keys.json'
$env:SC968_REGISTRY_TRUSTED_KEYS_DIR = 'C:\secure\trusted-candidate-keys'
$env:SC968_SIGNING_PRIVATE_KEY_PATH = 'C:\secure\candidate-private-key.pem'
$env:SC968_SIGNING_PUBLIC_KEY_PATH = 'C:\secure\candidate-public-key.pem'
$env:SC968_SIGNING_KEY_ID = '<configured key id>'
$env:SC968_EXPECTED_ARTIFACT_NAME = '<exact Micro6 shadow JAR name>'
$env:SC968_MICRO6_LAUNCH_SCRIPT = 'C:\secure\launch-sc968-micro6.ps1'
$env:SC968_RUNELITE_LAUNCH_SCRIPT = 'C:\secure\launch-sc968-runelite.ps1'
$env:SC968_REGISTRY_URL = 'http://127.0.0.1:8081'
$env:SC968_TASK_KEY = 'FISHING_TRAINING'
$env:SC968_SETTINGS_HASH = 'sha256:<64 lowercase hex>'
$env:SC968_PROFILE_ID = '<canonical Fishing profile id>'
$env:SC968_PROFILE_HASH = 'sha256:<64 lowercase hex>'
$env:SC968_CATALOG_HASH = 'sha256:<64 lowercase hex>'
```

The two launcher scripts must read the process environment populated by the harness. They must not
accept credentials on their command lines. The Micro6 launcher runs the canonical Fishing task and
ends its capture normally. The RuneLite launcher starts the standard RuneLite capture adapter and
keeps the client open long enough for the operator to perform one **Net → Fishing spot** interaction
and complete the capture. Its sidecar must write `sc968-capture-status/v1` to
`SC968_CAPTURE_STATUS_FILE`; the harness rejects fixtures, fabricated Micro6 fields, missing source
identity, failed/uncertain capture, and gameplay that begins before shadow observation is active.

## Run

```powershell
Set-Location C:\Dev\IdeaProjects\ChromaScape
.\scripts\sc968-acceptance.ps1 `
  -ContractsRoot 'C:\Dev\IdeaProjects\ai-routine-contracts' `
  -LeeuwBotRoot 'C:\Dev\IdeaProjects\leeuwbotbackend\leeuwbot-api\leeuwbotapi' `
  -ChromaScapeRoot 'C:\Dev\IdeaProjects\ChromaScape' `
  -Micro6Root 'C:\Dev\IdeaProjects\micro6' `
  -RuneLiteRoot 'C:\Dev\IdeaProjects\runelite'
```

Do not use `-AllowDirtyDevelopmentTrees` for story closure.

## Required result

The command succeeds only when every build, test, runtime, identity, Assistant, candidate,
RuneLite, replay, shadow, and safety gate passes. Attach these files and their manifest hashes to
SC-967 and SC-968:

- `output\sc968\sc-967-demo-manifest.json`
- `output\sc968\sc-968-acceptance-manifest.json`
- `output\sc968\runtime\baseline.json`
- `output\sc968\runtime\assistant.json`
- `output\sc968\runtime\runelite.json`
- `output\sc968\runtime\candidate.json`
- `output\sc968\runtime\shadow.json`

Both top-level manifests must report `overall: PASS`. Safety must report
`control_allowed: false` and `control_dispatch_count: 0`. A failed or partial run is evidence for
triage, not authorization to complete either Shortcut story.
