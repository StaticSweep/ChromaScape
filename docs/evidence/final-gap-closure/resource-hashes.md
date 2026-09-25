# Micro6 Pathfinder/Transport Resource Hashes (Phase 2 audit-first evidence)

Source revision: 59a7c67790 "Implement canonical Micro6 catalog v2 for AI Player"
Captured: 2026-08-14 (WSL)

Path: runelite-client/src/main/resources/net/runelite/client/plugins/microbot/shortestpath/

| Resource | SHA-256 |
|---|---|
| collision-map.zip | 06728bb9815d8173837b22108ffe575e367a34abc50f2fded48126910da15deb |
| transports.tsv | 40a4fddbec4c2175ed191f7c3eaa065f2354c94b5c24f1784cc5db2ca46cf287 |
| transports.json | d5558cd419c8d46bdc958064cb97f963d1ea793866414c025906ec15033512ed |
| seasonal_transports.tsv | 289fe9fc228c3de3616e93cd7cfdb5bce8aef1282c1a0247f0ee6d5120d49460 |
| restrictions.tsv | 8e1743a20fe43dc7e9ee1564486818ace94af3d1bc58aa762bda720006df534c |

ShortestPathCoreTest asserts SplitFlagMap.fromResources() non-null, region extents set,
and exact-tile/transport/route expectations. Resources load with nonzero extents.
