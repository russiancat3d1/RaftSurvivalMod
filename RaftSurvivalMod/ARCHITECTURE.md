# Raft Survival Mod — Architecture Reference
## NeoForge 1.21.1 | Java 21

---

## File Map

```
raftmod/
├── build.gradle                                  NeoForge Gradle 7 build
├── src/main/resources/
│   ├── META-INF/neoforge.mods.toml
│   ├── assets/raftmod/
│   │   ├── blockstates/raft_slab.json
│   │   └── models/block/raft_slab.json
│   └── data/minecraft/tags/blocks/mineable/
│       └── axe.json                             ← gates raft_slab to axe-only
└── src/main/java/com/raftmod/
    ├── RaftMod.java                             @Mod entry point
    ├── registry/ModRegistries.java              All DeferredRegister<> holders
    ├── entity/
    │   ├── RaftEntity.java          ★ Core moving platform
    │   ├── FloatingLootEntity.java  ★ Buoyant loot with spring physics
    │   └── GrapplingHookEntity.java ★ Thrown projectile / loot retrieval
    ├── block/RaftSlabBlock.java                 Half-slab, axe-only
    ├── item/
    │   ├── HammerItem.java          ★ Expand/demolish raft + ghost preview
    │   └── GrapplingHookItem.java               Throw the hook
    ├── client/
    │   ├── RaftEntityRenderer.java  ★ BlockRenderDispatcher per-cell rendering
    │   └── HammerGhostOverlay.java  ★ Translucent slab preview HUD layer
    └── events/RaftEventHandlers.java ★ All NeoForge event listeners
```

---

## Design Decisions & Key Trade-offs

### 1. Entity-based Block Storage (vs. actual world blocks)

**Problem:** A moving platform in Minecraft must either:
- (A) Move real world blocks every tick → involves 4–16 `setBlock` + `removeBlock` calls per tick, causing lighting/chunk updates and visible flicker.
- (B) Store blocks inside an entity and render them there.

**Choice:** Option (B), matching the Create mod contraption approach.
- `RaftEntity` holds `Map<BlockPos, BlockState> raftBlocks` (grid-relative coords).
- The map is serialised to `CompoundTag` and synced via `EntityDataAccessor<CompoundTag>`.
- `RaftEntityRenderer` calls `BlockRenderDispatcher.renderBatched()` per cell.

### 2. Player "Standing" Physics

Vanilla entities have no solid surface. We solve this with a per-tick scan:

```java
// In RaftEntity.tick() (server side only):
AABB surfaceBox = buildRaftAABB().expandTowards(0, 1.8, 0);
for (Player p : level.getEntitiesOfClass(Player.class, surfaceBox)) {
    if (Math.abs(p.getY() - (getY() + SLAB_HEIGHT)) < 0.65) {
        p.setPos(p.getX() + dx, p.getY(), p.getZ() + dz);
    }
}
```

**Limitation:** `setPos` teleports the player, which bypasses the player's own
velocity. This is intentional – it gives the "glued to the raft" feel.
For a smoother result, use `player.addDeltaMovement(delta)` instead, but
beware of drift accumulation over long journeys.

### 3. Ghost Block Preview Architecture

```
Client frame:
  HammerGhostOverlay.render()
    └─ HammerItem.getPreviewGridPos(player, raft)   ← pure raycasting, no packets
         └─ For each occupied cell, test AABB.clip() against look ray
         └─ Return the adjacent open cell closest to the camera
    └─ Draw translucent QUADS + DEBUG_LINES into world space via Tesselator
```

The preview is entirely client-side. No packets needed because the grid state
is already synced to the client via `EntityDataAccessor<CompoundTag>`.

### 4. Loot Stream Spawning

The spawn logic calculates a **perpendicular spread vector**:

```
Forward direction:  (sinH, 0, cosH)   where H = heading
Perpendicular:      (-cosH, 0, sinH)

Lane origin = raftPos + forward * 30
Each item   = laneOrigin + perpendicular * random(-5..+5)
```

Items use weighted randomisation (rarer items further back in the pool array,
selected with `Math.pow(rand.nextDouble(), 2.0)` for exponential falloff).

### 5. Grappling Hook Pull

On collision with `FloatingLootEntity`, the hook:
1. Sets `hookOwner` on the loot entity.
2. The loot's tick then applies `HOOK_PULL_ACCEL` toward the owner each tick.
3. Auto-collects when `distanceTo(owner) < 1.5`.

The hook itself becomes static (latched = true) after grabbing.

---

## Tool Gating — Full Matrix

| Tool         | Hand?     | Breaks raft? | Adds raft? | Picks up loot? |
|--------------|-----------|:---:|:---:|:---:|
| Empty hand   | Main      | ✗ (cancelled by event) | – | sneak+RMB ✓ |
| Any axe      | Main      | ✓ (drops slab item) | – | – |
| Hammer       | Main      | ✓ (removes from entity, costs durability) | ✓ (RMB) | – |
| Grappling hook | Main    | – | – | ✓ (throws hook) |

---

## How to Spawn a Raft (Commands / Creative)

```
/summon raftmod:raft ~ ~1 ~
```

Then call `initializeRaft()` – this is triggered automatically via the
`onPlayerLogin` event for first-time ocean spawns.

---

## Extending the Mod

| Feature | Where to add it |
|---|---|
| New loot items | `pickRandomLoot()` in `RaftEntity` → or replace with `LootTable` |
| Ocean obstacle (rocks) | Add an `ObstacleEntity` and check collisions in `RaftEntity.tick()` |
| Shark enemies | New `SharkEntity extends Monster`, spawn in loot lane |
| Recipes | Standard `data/raftmod/recipes/` JSON datapacks |
| Multi-layer raft (2nd floor) | Add Y=1..N cells to `raftBlocks`; renderer handles any Y |
| Raft steering wheel | `BlockEntity` stored in raftBlocks, right-click changes `HEADING` |

---

## Known Limitations / TODOs

1. **Lighting on rendered blocks** – `LevelLightEngine.getLightValue()` returns
   the world-block light at the grid cell's world position.  This is correct
   but means blocks appear darker when the entity moves into shadow.  A full
   fix would bake light per-frame.

2. **Client-side collision** – Players see themselves standing on the raft
   only because the server teleports them.  On high-latency connections there
   is slight rubber-banding.  Mitigate with client-side prediction packets.

3. **`E` key binding** – The "sneak + right-click" loot interaction is a
   workaround.  For a real `E`-key binding, register a `KeyMapping` in
   `FMLClientSetupEvent` and fire a custom network packet to the server.

4. **Ocean check** – `findOceanSurfaceY()` scans a fixed Y range (40–80).
   This works for default ocean level but will miss deep oceans or custom
   world generators.  Replace with `Heightmap.getHeight()` for robustness.
