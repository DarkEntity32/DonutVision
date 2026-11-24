DonutVision 1.9.8 — Schematic Printer hotbar fix

WHAT CHANGED
- Fix: auto hotbar select no longer depends on whether the block can be placed.
- Holding RMB: picks a matching item from slots 1-9 for a missing schematic block.
- Selection searches nearby loaded world up to 16 blocks if no placeable candidate is in reach.
- Logs/stairs/slabs and other blockstates are not excluded from material selection;
  their placement orientation/state is NOT guaranteed to match the blueprint.
- Status in Modules page differentiates missing hotbar block / no support / distant schematic.

LIMITS
- Normal survival server placement radius still ~4.4 blocks; infinite-range placement
  is not possible with ordinary interactions. Move nearer to build more of a schematic.
- Unsupported free-floating blocks cannot be placed without a support in survival.
- No automatic pathfinding. Only loaded Minecraft chunks and loaded Litematica placements.
- Server may reject a placement even when candidate looks locally reachable.
- Source update only. The final JAR needs a GitHub Actions build. Build not validated here.

INSTALL
Unzip DonutVision-v1.9.8-GitHub-update.zip into your existing DonutVision-26.1.2
project directory with folder structure intact. Commit; run workflow; get JAR artifact.
