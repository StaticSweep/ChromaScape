# SC-1498 Bootstrap Recording Runbook

## Current readiness

The contract, one-use human gate, ChromaScape orchestration, Micro6 executor gate, evidence store,
and native-Windows operator script are implemented. ChromaScape and Micro6 must be started from
Windows, not WSL.

Do not run the golden fixture against a live account. A live proposal must contain a fresh semantic
observation, real current account/client/build/catalog identities, a real Claude or Codex proposal,
fresh run/session/recording/request identities, monotonic lease/fence values, and a future deadline.

The canonical restart runbook currently records an account-identity migration blocker. Real Fishing
must remain blocked until the selected catalog record uses the AccountConnector-derived hash and
the Micro6 health response proves that expected and actual identities match. Do not bypass this by
using the account name.

## 1. Native Windows tests

Run from a Windows terminal:

```bat
cd /d C:\Dev\IdeaProjects\micro6
gradlew.bat :runelite-client:test ^
  --tests "net.runelite.client.plugins.microbot.accountbuilder.integration.builder.gameplay.GameplayEnvelopeGateTest" ^
  --tests "net.runelite.client.plugins.microbot.accountbuilder.integration.builder.gameplay.GameplayContractResourceEqualityTest"
```

Do not continue if either test fails.

## 2. One-run configuration

Generate two different random secrets. Do not commit or print them.

In `C:\Dev\IdeaProjects\ChromaScape\.env.local`:

```dotenv
GAMEPLAY_BOOTSTRAP_OPERATOR_ENABLED=true
GAMEPLAY_BOOTSTRAP_OPERATOR_KEY=<random 32+ character operator key>
GAMEPLAY_BOOTSTRAP_AUTHORITY_HMAC_SECRET=<random 32+ character authority secret>
GAMEPLAY_BOOTSTRAP_MICRO6_URL=http://127.0.0.1:8098
GAMEPLAY_BOOTSTRAP_EVIDENCE_ROOT=.chromascape/bootstrap-recordings
```

In `C:\Dev\IdeaProjects\micro6\.env`:

```dotenv
MICRO6_GAMEPLAY_AUTHORITY_HMAC_SECRET=<the same authority secret>
MICRO6_GAMEPLAY_AUTHORITY_LEDGER=.micro6/gameplay-authority-ledger-v1.txt
```

The operator key and authority secret must be different. The authority secret is shared only to
verify the one-use signed authority at the Micro6 gate.

## 3. Start and verify

Start ChromaScape using its canonical Windows restart path:

```bat
cd /d C:\Dev\IdeaProjects\ChromaScape
scripts\restart-chromascape.bat
```

Use the Builder Goal debugger flow to start the canonical Micro6 AccountBuilder client. Confirm the
selected account is identity-matched, the requested world is correct, and the executor reports
dispatch-ready. Then verify:

```powershell
Invoke-RestMethod http://127.0.0.1:8090/api/v1/gameplay/bootstrap/availability |
    ConvertTo-Json -Depth 10
Invoke-RestMethod http://127.0.0.1:8098/health | ConvertTo-Json -Depth 20
```

Expected operator availability is `enabled=true`, `operatorKeyConfigured=true`, with both CLAUDE
and CODEX listed. Micro6 must report the current runtime artifact, account/client/build/catalog
identity, and dispatch readiness expected by the envelope.

## 4. Prepare the exact proposal

Create a JSON file with this top-level shape:

```json
{
  "observation": {},
  "modelVersion": "exact-provider-model-version",
  "providerOutput": {},
  "envelope": {}
}
```

The nested values must validate against
`src\main\resources\contracts\gameplay\sc-1429-gameplay-contract-v1.schema.json`. Keep budgets at
the smallest practical values: one action, one attempt, and at most five minutes. The envelope's
`authorityId` is a draft placeholder; the operator service replaces it with the server-generated
one-use authority ID after approval. No other approved scope field may change.

## 5. Human approval and dispatch

Set the operator key only in the current PowerShell process and invoke the script:

```powershell
$env:GAMEPLAY_BOOTSTRAP_OPERATOR_KEY = '<operator key>'
cd C:\Dev\IdeaProjects\ChromaScape
.\scripts\sc1498-bootstrap.ps1 `
    -ProposalPath C:\path\to\sc1498-proposal.json `
    -ApprovedBy '<stable-human-operator-id>'
```

The script prints the exact scope and digest. It dispatches nothing unless the human types the full
`APPROVE sha256:...` value shown on screen. Approval expires after five minutes and can be bound
once.

Keep a second Windows terminal ready for the kill switch:

```powershell
$env:GAMEPLAY_BOOTSTRAP_OPERATOR_KEY = '<operator key>'
cd C:\Dev\IdeaProjects\ChromaScape
.\scripts\sc1498-bootstrap.ps1 `
    -StopRequestId '<exact requestId>' `
    -Reason 'Human SC-1498 kill switch' `
    -KillSwitch
```

## 6. Evidence and shutdown

The final receipt must be `CONFIRMED`, truthfully `STOPPED`, or truthfully
`UNCERTAIN_OUTCOME`; queue acceptance alone is not success. Audit:

```text
C:\Dev\IdeaProjects\ChromaScape\.chromascape\bootstrap-recordings\<recordingId>.sc-1498.json
```

Require the full observation-to-terminal receipt chain, unchanged provider/model attribution,
consumed authority, and all of these values set to `false`:

- `learnedModelActivated`
- `continuingAuthority`
- `simulatorTrace`

After the run, set `GAMEPLAY_BOOTSTRAP_OPERATOR_ENABLED=false`, clear the process-scoped operator
key, and restart ChromaScape. Never reuse either the proposal identities or authority ledger entry.
