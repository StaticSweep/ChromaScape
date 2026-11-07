# Compiler native NPC binding boundary

Compiler 2.2.0 adds closed import and published-curation validation for
`initialState.nativeFishingWorld` (`xrsps-native-fishing-world/v1`) and
`initialState.nativeNpcBindings` (`xrsps-native-npc-bindings/v1`). These are inert
declarations, not executable code, remote resources or world-support attestations.
The existing manifest byte/depth/duplicate-key/checksum gates still apply.

Validation proceeds through:

1. Closed object fields, numeric/string bounds, 1..64 unique ascending selected
   catalogue indices, and 1..10000 source-ordered binding rows. No observed NPC,
   quest or exchange scene can be mixed into the native Fishing world.
2. Equal world/binding catalogue pins; exact manifest index, source tick and
   sequence; exactly one numeric compiler NPC audit target and matching snapshot
   reference. Unknown alias/endpoint/script fields cannot enter through the
   general captured-state surface.
3. At compilation, exact original action-tick snapshot identity, captured NPC
   type and canonical position/plane, plus the existing authoritative
   npcId/type/size/spotId pin validator even for authored BEST_EFFORT input.
   A published operator patch is never used
   as the source snapshot. Neither a later observed NPC nor a declaration may
   rewrite the original scene, source action or immutable published curation.
4. The native worker independently checks the actual production catalogue and
   live native entity at dispatch. Compiler validation performs no catalogue
   file or network I/O; a checksum declaration alone is not proof of native data.

The selected published curation remains owner/recording/session/publication
checked and frozen. Both native fields are allowed only through this typed
boundary. Per-leaf provenance retains the exact operator curation pointer;
operator state never promotes captured quality or clears original source gaps.

World eligibility is deliberately separate. Compilation retains all existing
source-linked world requirements and adds `NATIVE_FISHING_WORLD_CONTRACT` under
`requiredNativeBindings`, producing `NATIVE_WORLD_SUPPORT_REQUIRED`. Import's
execution gate also refuses native declarations even if someone reseals a
document after removing those requirements. This provisional gate must be
replaced only by the exact verified native world/bank/widget/travel/collision
contract, not a generic accepted action, source-reference string or clean build.
No new runtime method is advertised by this compiler increment.

The authored `native-npc-binding.json` export fixture is labeled
`AUTHORED_TEST_ONLY` and `INCOMPLETE_BLOCKED`. An explicit development worker may
exercise the unchanged bytes to verify the source/native dispatch boundary;
that does not authorize production causal execution, repair a historical
recording, establish genuine capture, or certify the worker. Multiple same-tick
source actions and general source scheduling remain separate delivery gates.

The compiler version changes the deterministic seed and manifest identity by
design. Golden pins must be regenerated for that explicit compiler revision;
prior exports, source recordings and verification artifacts remain unchanged.
