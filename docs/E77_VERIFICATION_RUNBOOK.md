# E7.7 Verification Runbook

Run one command to produce a markdown evidence pack:

```bash
cd /mnt/f/dev/ideaprojects/chromascape
./scripts/verify-e77-session.sh /tmp/e77_verification_report.md
```

If services are not running, the report will still capture hard failures with exact output.

## Windows Host (recommended for micro6 localhost binding)

Run from Windows PowerShell:

```powershell
cd F:\Dev\IdeaProjects\ChromaScape
.\scripts\verify-e77-session.ps1 -ReportFile "$env:TEMP\e77_verification_report.md"
```

If micro6 is bound to Windows localhost only, run all patch-loop E2E probes from Windows terminals.

## Required Service State

- Micro6 local executor reachable on `http://127.0.0.1:8098`
- ChromaScape reachable on `http://localhost:8090`

## Checklist Outcomes by Story

- `sc-582`: verify task-plan item transitions from `EXECUTING` to terminal status.
- `sc-583`: verify quest/boss/skilling harness runs and ingestion succeeds.
- `sc-584`: verify `/api/v1/catalog` returns version `2` and metadata fields.
- `sc-585`: verify dataset export endpoint and schema evidence.

## Shortcut Comment Template

```text
Execution Date: 2026-04-15
Operator: <name>
Environment: <dev/prod, host>
Story: sc-XXX
Result: PASS | FAIL

Checks Run:
- <check 1>
- <check 2>
- <check 3>

Evidence:
- /tmp/e77_verification_report.md
- <api response snippet>
- <dashboard screenshot/path>

Notes:
- <blockers or follow-up>
```
