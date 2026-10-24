# Recorded source scheduling

Timed v2 recording actions retain their absolute source tick, source sequence and
manifest index. The Java replay worker validates a complete ordered schedule,
advances using bounded action-less steps (not fabricated WAIT actions), and sends
each source action as `step` with `maxTicks: 0` at its exact tick. Multiple actions
at one tick retain distinct indexes and source sequence. The tail advances to the
recording end so final activity effects are not silently omitted.

This requires explicit `sourceActionProtocol` v1 with `zeroTickStep: true` and
`receipts: INDEXED_ONE_USE`; older workers fail closed for timed recordings.
Authoritative fidelity, clean identity and certification gates remain separate.
No new gameplay method or content parity claim follows from this protocol.

The native kernel checks the exact semantic permission and next timed source
receipt before dispatch. It commits only an accepted action. Advancing ticks stop
at the next unconsumed source action; an action-less step at that tick refuses.
Missing indexes, duplicate/reordered/late actions and changed payloads cannot
skip or repeat a source effect. Untimed authored policy permissions remain
reusable; they do not acquire fabricated source indexes. Session actions use the
same source receipt boundary. Durable recorded journals preserve the exact
manifest prefix and timing and pass their explicit index without source metadata
inside the strict semantic payload.

Fresh reconstruction builds fresh semantic, source and NPC-binding policies.
Only a fully validated reconstructed state/RNG/trace replaces the active run;
failed restore cannot consume active receipts. Zero-tick non-session log entries
must still name actual timed actions when replayed, not merely a resealed index.

This contract is not a substitute for the remaining world/bank/widget/travel,
capture, full-server parity, isolation, lifecycle and certification requirements.
Authored source-schedule tests and development stdio runs are not genuine capture
or a certifying production worker.
