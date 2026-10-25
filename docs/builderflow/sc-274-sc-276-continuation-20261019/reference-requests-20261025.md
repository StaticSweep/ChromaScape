# Independent oracle reference requests (R10 preparation)

Generated from `server/src/replay/oracle-acceptance-matrix.ts` and `promised-method-inventory.ts` at commit
HEAD of `codex/sc274-native-recovered-20261024`. Nothing here is evidence: every row is a REQUEST for a genuine
capture. Authored or resealed data cannot discharge any row (see R2).

## What a reference must be

- `kind: INDEPENDENT_CAPTURE`, produced by a tool/server that is NOT this xRSPS replay (for example a live OSRS
  client capture or a separately maintained reference implementation), with its tool version recorded.
- Frame-per-tick records: `tick`, `position {x,y,plane}`, `skills`, `inventory [{slot,itemId,quantity}]`, `events []`,
  plus every optional field named for the method below (`equipment`, `vars`, `bank`, `exchange`, `quest`, `combat`,
  `eventPayloads`). A capture missing a required field is reported INCOMPLETE_CAPTURE; nothing is invented.
- Sealed with `sealOracleReference` so `sha256` covers the exact frames; the reference id is immutable.
- Recorded together: the exact action stream and initial world state the replay must be driven with. Their SHA-256
  digests are approved with the reference (`actionInputSha256`, `initialStateSha256`).
- Approved OUTSIDE the candidate: a reviewed entry in `server/src/replay/oracle-reference-inventory.ts`
  (`OracleApprovedReference`: referenceId, method, scenario, expectedSha256, authority, source, captureId,
  toolVersion, actionInputSha256, initialStateSha256), landed by a code review that names the approving authority.
- Replay output is then bound to the pinned runtime identity (build commit, content, cache, closure checksum) and
  the comparison profile `xrsps-oracle-compare/v1`; no tolerances are applied.

## Requests

| Method | Advertised now | Reference id(s) | Required beyond position/skills/inventory/events | Current blocker |
|---|---|---|---|---|
| `quest:misthalin_mystery` | yes | `oracle/quest/misthalin_mystery/full-run` | vars, quest, eventPayloads | reference capture |
| `quest:rune_mysteries` | yes | `oracle/quest/rune_mysteries/full-run` | vars, quest, eventPayloads | reference capture |
| `npc:minnow-exchange` | yes | `oracle/npc/minnow-exchange/exchange` | vars, eventPayloads | reference capture |
| `ge:finite-order-book-v1` | yes | `oracle/ge/finite-order-book/fill-sequence` | exchange, eventPayloads | reference capture |
| `bank:explicit-quantity-v1` | yes | `oracle/bank/explicit-quantity/deposit-withdraw` | bank, vars, eventPayloads | reference capture |
| `bank:containers-v1` | yes | `oracle/bank/containers/deposit-withdraw` | bank, vars, eventPayloads | reference capture |
| `travel:jewelry-v1` | yes | `oracle/travel/jewelry/teleport-charges` | equipment, vars, eventPayloads | reference capture |
| `skill:fishing-v1` | yes | `oracle/skill/fishing/catch-sequence` | eventPayloads | reference capture |
| `skill:glassblowing-v1` | no | `oracle/skill/glassblowing/product-sequence` | vars, eventPayloads | no scoped method contract with independently captured production/headless parity evidence |
| `magic:wind-strike-v1` | no | `oracle/magic/wind-strike/manual-cast` | combat, eventPayloads | no scoped method contract with independently captured production/headless parity evidence |
| `magic:alchemy-v1` | no | `oracle/magic/alchemy/low-high` | eventPayloads | no scoped method contract with independently captured production/headless parity evidence |
| `magic:superheat-v1` | no | `oracle/magic/superheat/bar-smelting` | eventPayloads | no scoped method contract with independently captured production/headless parity evidence |
| `magic:ring-enchantment-v1` | no | `oracle/magic/ring-enchantment/table` | eventPayloads | no scoped method contract with independently captured production/headless parity evidence |
| `auxiliary:movement-v1` | no | `oracle/auxiliary/movement/path-run` | eventPayloads | no scoped method contract with independently captured production/headless parity evidence |
| `auxiliary:drop-v1` | no | `oracle/auxiliary/drop/ground-item` | eventPayloads | no scoped method contract with independently captured production/headless parity evidence |
| `auxiliary:equip-v1` | no | `oracle/auxiliary/equip/equip-unequip` | equipment, eventPayloads | no scoped method contract with independently captured production/headless parity evidence |
| `auxiliary:object-interaction-v1` | no | `oracle/auxiliary/object/interaction` | vars, eventPayloads | no scoped method contract with independently captured production/headless parity evidence |
| `auxiliary:dialogue-v1` | no | `oracle/auxiliary/dialogue/options` | vars, eventPayloads | no scoped method contract with independently captured production/headless parity evidence |

## Per-request checklist

For each reference id above the requester must supply: permitted source and capture conditions (account/world
state, start tick, seed or RNG observations if the method is random), the observations required by the field list,
provenance (`source`, `captureId`, `toolVersion`, `capturedAt`), the expected digest, and the approval authority who
signs the inventory entry. If a capture cannot show randomness inputs, the replay result must be reported
INCOMPLETE rather than approximated.
