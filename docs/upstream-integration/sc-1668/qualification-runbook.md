# SC-1668 host qualification runbook

Use a clean checkout of the exact candidate revision on every host. Do not copy a PASS result from
one machine to another. Store raw logs outside Git until they have been reviewed and redacted.

## 1. Initialize records

On Fedora, from the candidate checkout:

```bash
REVISION="$(git rev-parse HEAD)"
python3 -B scripts/qualification/sc1668_evidence.py init \
  --role fedora-primary --host-id <stable-nonsensitive-host-label> \
  --revision "$REVISION" --output <evidence-dir>/fedora-primary.json
```

Repeat on the second machine with role `fedora-secondary` and a different host label.

On Windows, use the canonical checkout and Windows paths:

```powershell
Set-Location C:\Dev\IdeaProjects\ChromaScape
$Revision = (git rev-parse HEAD).Trim()
py -3 -B scripts\qualification\sc1668_evidence.py init `
  --role windows --host-id <stable-nonsensitive-host-label> `
  --revision $Revision --output <evidence-dir>\windows.json
```

An initialized record is deliberately incomplete: every check is NOT_RUN.

## 2. Record exact versions

Use the `version` command for `os`, `arch`, `java`, `client`, and `nativeArtifactSha256`. Fedora must
record the SHA-256 of the exact manifest-verified `libRemoteInput.so`; Windows uses
`NOT_APPLICABLE` for that field.

Example:

```bash
python3 -B scripts/qualification/sc1668_evidence.py version \
  --file <evidence-dir>/fedora-primary.json --name java \
  --value "$(java -version 2>&1 | head -n 1)"
```

Do not put usernames, access tokens, account names, raw command lines, or absolute home paths in a
record. Put detailed output in a separately reviewed log and reference its relative filename.

## 3. Fedora procedure (run independently on both hosts)

1. Confirm the checkout is clean and at the candidate revision.
2. Configure the host-native client and ChromaScape using the SC-1667 runbook. Do not use Flatpak or
   bwrap for the client being attached.
3. Run `scripts/fedora/chromascape-fedora.sh preflight` with attachment disabled. Retain the complete
   output. It must select exactly the intended PID and verify the library/manifest pair.
4. Run the required Gradle build and focused tests. Record build PASS only on exit zero.
5. Start ChromaScape through the owned SC-1667 lifecycle wrapper and verify `/debugger`.
6. With one client, explicitly select it, attach, perform one bounded reversible mouse/keyboard
   action in a safe test context, capture frames, start/stop a short recording, and inspect debugger
   identity/diagnostics. Verify release.
7. Start a second eligible client. Prove implicit selection refuses ambiguity; explicitly select one
   client and confirm no frame/input appears in the other session.
8. Exercise remote launch, resize, minimize/restore, target restart, and repeated attach/release.
   Every operation must remain bounded and must either succeed for the selected identity or fail
   closed with a diagnostic.
9. Stop via `scripts/fedora/chromascape-fedora.sh stop`, confirm ownership is released, restart, and
   perform a second attach/release.

Record each outcome only after inspecting its log:

```bash
python3 -B scripts/qualification/sc1668_evidence.py record \
  --file <evidence-dir>/fedora-primary.json --check attachment --status PASS \
  --evidence "fedora-primary/live-attachment.log: bounded attach/release for selected identity"
```

Use FAIL for an executed failing case. Use NOT_RUN when a required client, service, or operation was
not exercised. Never use PASS for fixture-only coverage of a live case.

## 4. Windows procedure

Run from Windows, not WSL:

```bat
cd /d C:\Dev\IdeaProjects\ChromaScape
gradlew.bat cleanTest test
gradlew.bat replayTest
gradlew.bat assembleLinuxDistribution
scripts\restart-chromascape.bat
```

The Linux assembly command is a distribution-exclusion regression on Windows; it is not a Windows
native package. Also verify the existing KInput DLL staging, window enumeration/selection, bounded
input and release, GDI capture, recording, debugger, replay, and Builder Goal remote-launch paths.
Follow `docs/CHROMASCAPE_RESTART_RUNBOOK.md` for restart configuration and inspect
`C:\Dev\IdeaProjects\ChromaScape\chroma_bootrun.log` on startup failure.

## 5. Verify the matrix

Copy the three reviewed JSON records into one evidence directory and run:

```bash
python3 -B scripts/qualification/sc1668_evidence.py verify \
  <evidence-dir>/fedora-primary.json \
  <evidence-dir>/fedora-secondary.json \
  <evidence-dir>/windows.json

python3 -B scripts/qualification/sc1668_evidence.py verify --require-complete \
  <evidence-dir>/fedora-primary.json \
  <evidence-dir>/fedora-secondary.json \
  <evidence-dir>/windows.json
```

The first command validates an honest partial matrix. The second is the release gate and exits 2 if
any required case is FAIL or NOT_RUN, if revisions differ, or if the Fedora records reuse one host.
