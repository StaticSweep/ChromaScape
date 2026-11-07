# SC-274 closure audit — 31 October 2026

SC-274 remains In Review. No acceptance amendment or completed runtime qualification is
inferred from the request to close it. This increment corrects a conversion defect and
records further prerequisites hidden behind the live capability gate.

## Changed files

- `HumanFishingActionReader.java`: maps the observed Lure callback to canonical semantic
  option `lure`. Immutable client callback evidence is unchanged. The checksum domain is
  advanced to `human-fishing-observation-converter-v2` because semantic output changed.
- `HumanFishingActionReaderTest.java`: integration regression from the actual reader
  output into `ScenarioCompilationEvidence.world`, proving Fishing does not acquire an
  `EXACT_BANK_WIDGET_OR_EXCHANGE_SEMANTICS` requirement.
- This report and the remaining-delivery-plan pointer.

## Acceptance results

Recording `recording_af17d0dc` remains genuine HUMAN playback evidence: 236 contiguous
ticks, eight fish, +460 Fishing XP and published held-out curation. This is not the
provider-origin input required by SC-1498 and the current SC-274 acceptance text.

Read-only diagnostic calls to production compiler prerequisites found:

1. Immutable RunContext lacks `graphRevision`.
2. Full-range NPC validation reports 138 violations for missing authoritative `spotId`.
   The observer action's symbolic hint does not supply missing snapshot evidence.
3. Initial-state construction rejects the missing or invalid recorded world ID. Later
   initial-state requirements, including collision revision, are not proven by this
   short-circuiting validation.
4. The referenced NPC is observed later than the initial snapshot. Its existence at
   action tick resolves the target, but does not prove initial placement or a native
   dynamic-spawn contract. This remaining native binding is preserved.
5. Current live capability remains SYNTHETIC_CONTRACT, certifiable=false, causal modes
   unavailable. No capability gate was changed and no ScenarioManifest was produced.

The v1 diagnostic also exposed the incorrect uppercase semantic option. The v2 audit
removes only its spurious bank-semantics binding; the actual prerequisite failures remain.
The earlier helper-only diagnostic is retained separately because it incorrectly tried
to validate the action tick against only the initial snapshot. The corrected report
validates NPC pins over all 236 snapshots before independently examining initial state.

## Tests

- Offline Gradle `replayTest`, selected HumanFishingActionReaderTest,
  ScenarioManifestV2CompilerTest and ReplayActionConversionServiceTest: **68 tests,
  zero failures, errors or skips**, BUILD SUCCESSFUL.
- Two fresh production-converter JVMs (16540, 107900), real stored recording, enforced
  read-only database access: one action, no gaps, byte-identical output; old incomplete
  recording still refused as CAPTURE_INCOMPLETE.
- v2 conversion checksum:
  `sha256:8ea3947e2381f590e539e29fd5ece86d4c567c76e694f9e3bd27d191c812b994`.
- v2 output SHA-256:
  `68fc6a435beb982b5b6bd3af7909518e8a1c665942ceab5ba8cb9ad808934409`.
- Read-only prerequisite audit against v1 and v2 conversion outputs; original v1
  conversion outputs retained. This diagnostic does not bypass normal compiler admission.
- Touched Java files formatted with Google Java Format; `git diff --check` passes.

Raw evidence: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261031\closure-audit`.
No full native, Linux, provider, migration or Watch suite was run in this increment.

## Remaining work

1. Correct the capture producer and supported run-context setup to record genuine graph,
   world/collision and authoritative NPC content identities. Test these before asking for
   another capture. Do not patch the finalized recording or invent missing identities.
2. Qualify the runtime and dynamic-world binding with the independent references and
   target-platform evidence required by SC-2201. Its current text explicitly does not
   waive SC-274 acceptance; caller configuration cannot substitute for qualification.
3. Complete the supported provider-origin route under SC-1498's exact human approval
   gate, with approved provider/account/task/budget and one-use run/session/recording IDs.
   Live bootstrap is currently disabled and has no configured operator key.
4. Obtain the accepted bank/travel/Fishing and privacy inputs required by the current
   story; historical incomplete recordings stay blocked. Complete the final acceptance
   matrix and applicable pairing/Watch checks with actual evidence.
5. Resolve the missing Felix task identity before a validated completion transition.
   Original task `task_1789960012362_td2qnusp` returns Task not found. Exact stable-tag
   search currently fails in its search backend; explicit canonical-project task-type
   inventories did not locate the original task. No duplicate task or Done marker created.

## Risks

The v2 conversion has a deliberately different checksum. Consumers must not reuse v1
conversion identity for v2 bytes. The live 8090 service and game client were not restarted
and still run the previously deployed code. The fix is verified in the candidate and in
fresh read-only JVMs, not claimed as deployed. No recording or database rows were changed.

## Rollback

Revert this scoped conversion/test change if necessary. Retain both converter versions'
evidence and all original capture, curation and media artifacts. No shared service,
credentials, migration, registry fence, trust root or replay consumer changed.
