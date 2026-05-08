package com.raftmod.entity;

import com.raftmod.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * ══════════════════════════════════════════════════════════════════════════════
 *  RaftEntity  –  The Moving Platform
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *  Architecture overview
 *  ─────────────────────
 *  • This entity IS NOT tied to world blocks.  The raft "floor" lives entirely
 *    in a Map<Vec3i, BlockState> called `raftBlocks`.  That map is serialised
 *    to a CompoundTag and synced to clients via SynchedEntityData so the
 *    RaftEntityRenderer can draw each block at the correct world position.
 *
 *  • Movement  – every tick the entity advances along its heading at `speed`
 *    blocks/tick.  Only ocean blocks (water) below the raft allow movement;
 *    the raft stops if it would move over land.
 *
 *  • Player riding  – we do NOT use the vanilla vehicle system (no sit-down).
 *    Instead, every tick we find players whose feet land on the raft surface
 *    AABB and silently teleport them by the same delta we moved this tick.
 *
 *  • Loot spawning  – every LOOT_SPAWN_INTERVAL ticks a cluster of
 *    FloatingLootEntity objects is spawned in a lane 30 blocks ahead of the
 *    raft's current heading.
 *
 *  Grid coordinate system
 *  ──────────────────────
 *  Grid positions are stored as integer (col, 0, row) offsets relative to the
 *  entity's block-aligned origin (south-west corner of the initial 2×2 grid).
 *  The initial 2×2 occupies columns 0–1, rows 0–1.
 *
 *      col→   0    1
 *  row↓   [SW] [SE]
 *          [NW] [NE]
 */
public class RaftEntity extends Entity {

    // ── Synced data keys ───────────────────────────────────────────────────
    /** Serialised block map, synced to clients for rendering. */
    private static final EntityDataAccessor<CompoundTag> BLOCKS_TAG =
        SynchedEntityData.defineId(RaftEntity.class, EntityDataSerializers.COMPOUND_TAG);

    /** Current heading in radians (server-authoritative, sent to clients). */
    private static final EntityDataAccessor<Float> HEADING =
        SynchedEntityData.defineId(RaftEntity.class, EntityDataSerializers.FLOAT);

    // ── Constants ──────────────────────────────────────────────────────────
    /** Blocks/tick the raft travels.  0.04 ≈ 0.8 m/s (half a boat). */
    private static final double RAFT_SPEED = 0.04;

    /** Slab height in blocks – half a full block. */
    public static final double SLAB_HEIGHT = 0.5;

    /** How many ticks between loot spawns. 200 = 10 seconds. */
    private static final int LOOT_SPAWN_INTERVAL = 200;

    /** Width of the loot lane ahead of the raft (blocks). */
    private static final int LOOT_LANE_WIDTH = 10;

    /** Distance ahead of the raft to spawn loot (blocks). */
    private static final int LOOT_SPAWN_DISTANCE = 30;

    /** How many loot items to drop per spawn event. */
    private static final int LOOT_COUNT = 5;

    // ── Server-side state (not synced – reconstructed from BLOCKS_TAG) ────
    /**
     * The raft grid.  Key = relative grid position (col, 0, row).
     * Value = BlockState to render at that cell.
     *
     * NOTE: On the CLIENT this map is populated by deserialising BLOCKS_TAG
     *       inside {@link #readSyncedBlocksTag(CompoundTag)}.
     */
    private final Map<BlockPos, BlockState> raftBlocks = new LinkedHashMap<>();

    /** Tracks how many ticks since last loot spawn. */
    private int lootSpawnTimer = 0;

    /** Previous position used to compute per-tick delta for player movement. */
    private Vec3 prevPositionForDelta = Vec3.ZERO;

    // ══════════════════════════════════════════════════════════════════════
    //  Construction / initialisation
    // ══════════════════════════════════════════════════════════════════════

    public RaftEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;   // we drive our own movement; bypass vanilla physics
        this.setNoGravity(true);
    }

    /**
     * Called once right after the entity is first spawned in the world.
     * Places the initial 2×2 raft-slab grid.
     */
    public void initializeRaft() {
        raftBlocks.clear();
        BlockState slab = ModRegistries.RAFT_SLAB.get().defaultBlockState();
        for (int col = 0; col < 2; col++) {
            for (int row = 0; row < 2; row++) {
                raftBlocks.put(new BlockPos(col, 0, row), slab);
            }
        }
        syncBlocksToClients();
        refreshBoundingBox();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SynchedEntityData / serialisation
    // ══════════════════════════════════════════════════════════════════════

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(BLOCKS_TAG, new CompoundTag());
        builder.define(HEADING, 0.0f);   // default heading: +Z direction (south)
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        tag.put("RaftBlocks", buildBlocksTag());
        tag.putFloat("Heading", entityData.get(HEADING));
        tag.putInt("LootTimer", lootSpawnTimer);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("RaftBlocks")) {
            readSyncedBlocksTag(tag.getCompound("RaftBlocks"));
            entityData.set(BLOCKS_TAG, buildBlocksTag());
        }
        if (tag.contains("Heading")) {
            entityData.set(HEADING, tag.getFloat("Heading"));
        }
        lootSpawnTimer = tag.getInt("LootTimer");
        refreshBoundingBox();
    }

    // ── Tag helpers ────────────────────────────────────────────────────────

    /** Serialise the block map into a CompoundTag suitable for sync/save. */
    private CompoundTag buildBlocksTag() {
        CompoundTag root = new CompoundTag();
        ListTag list = new ListTag();
        for (Map.Entry<BlockPos, BlockState> entry : raftBlocks.entrySet()) {
            CompoundTag cell = new CompoundTag();
            cell.putInt("cx", entry.getKey().getX());
            cell.putInt("cy", entry.getKey().getY());
            cell.putInt("cz", entry.getKey().getZ());
            cell.put("state", NbtUtils.writeBlockState(entry.getValue()));
            list.add(cell);
        }
        root.put("cells", list);
        return root;
    }

    /** Deserialise a CompoundTag back into the raftBlocks map. */
    private void readSyncedBlocksTag(CompoundTag root) {
        raftBlocks.clear();
        ListTag list = root.getList("cells", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag cell = list.getCompound(i);
            BlockPos pos = new BlockPos(cell.getInt("cx"), cell.getInt("cy"), cell.getInt("cz"));
            BlockState state = NbtUtils.readBlockState(
                this.level().holderLookup(net.minecraft.core.registries.Registries.BLOCK),
                cell.getCompound("state")
            );
            raftBlocks.put(pos, state);
        }
    }

    /** Push the current block map to clients via synced data. */
    private void syncBlocksToClients() {
        entityData.set(BLOCKS_TAG, buildBlocksTag());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Tick – the heartbeat of the raft
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public void tick() {
        super.tick();

        if (level().isClientSide()) {
            // Client: keep block map up-to-date when synced data changes
            CompoundTag synced = entityData.get(BLOCKS_TAG);
            if (!synced.isEmpty()) {
                readSyncedBlocksTag(synced);
            }
            return;   // physics / loot spawning only on server
        }

        // ── 1. Record pre-move position for delta calculation ──────────────
        prevPositionForDelta = position();

        // ── 2. Move the raft ───────────────────────────────────────────────
        double heading  = entityData.get(HEADING);
        double deltaX   = Math.sin(heading) * RAFT_SPEED;
        double deltaZ   = Math.cos(heading) * RAFT_SPEED;

        double newX = getX() + deltaX;
        double newZ = getZ() + deltaZ;

        // Guard: keep raft on water.  Sample the block below the raft center.
        BlockPos checkPos = new BlockPos((int) newX, (int)(getY() - 1), (int) newZ);
        if (!level().getBlockState(checkPos).getFluidState().isEmpty()) {
            setPos(newX, getY(), newZ);
        }
        // If we hit land, gently turn 45° to avoid getting stuck.
        else {
            float newHeading = entityData.get(HEADING) + Mth.PI / 4f;
            entityData.set(HEADING, newHeading);
        }

        // ── 3. Move players standing on the raft with it ───────────────────
        Vec3 delta = position().subtract(prevPositionForDelta);
        if (!delta.equals(Vec3.ZERO)) {
            movePlayersWithRaft(delta);
        }

        // ── 4. Periodically spawn floating loot ahead ─────────────────────
        lootSpawnTimer++;
        if (lootSpawnTimer >= LOOT_SPAWN_INTERVAL) {
            lootSpawnTimer = 0;
            spawnLootStream(heading, deltaX, deltaZ);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Player movement – the "invisible floor" mechanic
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Finds every player whose feet sit within the raft surface bounding box
     * and teleports them by the same vector the raft moved this tick.
     *
     * Surface AABB is the raft footprint + 0.6 blocks above it so that a
     * player whose legs clip slightly into a slab corner stays detected.
     */
    private void movePlayersWithRaft(Vec3 delta) {
        AABB surfaceBox = buildRaftAABB().inflate(0.1, 0, 0.1)
                                         .expandTowards(0, 1.8, 0);

        for (Player player : level().getEntitiesOfClass(Player.class, surfaceBox)) {
            // Confirm the player is actually STANDING ON the raft surface
            // (not flying above it, not below it in the water).
            double playerFeetY = player.getY();
            double raftSurfaceY = getY() + SLAB_HEIGHT;

            if (Math.abs(playerFeetY - raftSurfaceY) < 0.65) {
                // Move player with the raft.
                // We use setPos instead of addDeltaMovement because the
                // player's own physics would otherwise fight our push.
                player.setPos(
                    player.getX() + delta.x,
                    player.getY(),
                    player.getZ() + delta.z
                );
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Loot stream
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Spawns {@value #LOOT_COUNT} FloatingLootEntity objects in a
     * {@value #LOOT_LANE_WIDTH}-block-wide lane {@value #LOOT_SPAWN_DISTANCE}
     * blocks ahead of the raft's current heading.
     *
     * The items to drop are chosen from a small hardcoded loot table –
     * replace with LootTable integration for a richer experience.
     */
    private void spawnLootStream(double heading, double dirX, double dirZ) {
        Random rand = level().getRandom();

        // Normalise the direction vector
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len == 0) return;
        double nx = dirX / len;
        double nz = dirZ / len;

        // Origin of the lane: 30 blocks ahead of the raft centre
        double laneOriginX = getX() + nx * LOOT_SPAWN_DISTANCE;
        double laneOriginZ = getZ() + nz * LOOT_SPAWN_DISTANCE;

        // Perpendicular direction (right of travel) for lateral spreading
        double perpX = -nz;
        double perpZ =  nx;

        for (int i = 0; i < LOOT_COUNT; i++) {
            // Random lateral offset within the lane width
            double lateral = (rand.nextDouble() - 0.5) * LOOT_LANE_WIDTH;
            double spawnX  = laneOriginX + perpX * lateral;
            double spawnZ  = laneOriginZ + perpZ * lateral;

            // Find ocean surface Y at that column
            double spawnY = findOceanSurfaceY(spawnX, spawnZ);
            if (spawnY < 0) continue;  // not ocean, skip

            // Pick a random loot item from the table
            ItemStack lootItem = pickRandomLoot(rand);

            FloatingLootEntity loot = new FloatingLootEntity(
                ModRegistries.FLOATING_LOOT_ENTITY.get(), level());
            loot.setItem(lootItem);
            loot.setPos(spawnX, spawnY, spawnZ);
            level().addFreshEntity(loot);
        }
    }

    /**
     * Walks down from a fixed altitude to find the water surface level.
     * Returns -1 if no water was found (e.g. over land).
     */
    private double findOceanSurfaceY(double x, double z) {
        for (int y = 80; y > 40; y--) {
            BlockPos pos = new BlockPos((int) x, y, (int) z);
            if (!level().getBlockState(pos).getFluidState().isEmpty()
                && level().getBlockState(pos.above()).getFluidState().isEmpty()) {
                return y;
            }
        }
        return -1;
    }

    /** Simple weighted random loot table. Replace with LootTable API as desired. */
    private ItemStack pickRandomLoot(Random rand) {
        net.minecraft.world.item.Item[] pool = {
            net.minecraft.world.item.Items.KELP,
            net.minecraft.world.item.Items.SEAGRASS,
            net.minecraft.world.item.Items.COD,
            net.minecraft.world.item.Items.SALMON,
            net.minecraft.world.item.Items.TROPICAL_FISH,
            net.minecraft.world.item.Items.NAUTILUS_SHELL,
            net.minecraft.world.item.Items.HEART_OF_THE_SEA
        };
        // Rarer items are further back in the array – weight by index
        int idx = (int) (pool.length * Math.pow(rand.nextDouble(), 2.0));
        idx = Mth.clamp(idx, 0, pool.length - 1);
        return new ItemStack(pool[idx]);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Grid manipulation (called by HammerItem)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Adds a new raft slab at the given GRID position (col, 0, row).
     * Validates that the position is adjacent to an existing cell before adding.
     *
     * @param gridPos  relative grid position (col, 0, row)
     * @return true if the block was successfully added
     */
    public boolean addBlock(BlockPos gridPos) {
        if (raftBlocks.containsKey(gridPos)) return false;  // already occupied

        // Must be orthogonally adjacent to at least one existing cell
        boolean hasNeighbour = false;
        for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            if (raftBlocks.containsKey(gridPos.offset(d[0], 0, d[1]))) {
                hasNeighbour = true;
                break;
            }
        }
        if (!hasNeighbour) return false;

        raftBlocks.put(gridPos, ModRegistries.RAFT_SLAB.get().defaultBlockState());
        syncBlocksToClients();
        refreshBoundingBox();
        return true;
    }

    /**
     * Removes a raft slab at the given GRID position.
     * The initial 2×2 core cannot be removed.
     */
    public boolean removeBlock(BlockPos gridPos) {
        // Protect the core 2×2
        int col = gridPos.getX(), row = gridPos.getZ();
        if (col >= 0 && col < 2 && row >= 0 && row < 2) return false;

        if (!raftBlocks.remove(gridPos, raftBlocks.get(gridPos))) return false;
        syncBlocksToClients();
        refreshBoundingBox();
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  AABB helpers
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Recalculates the bounding box based on the current grid extent.
     * Called whenever blocks are added or removed.
     */
    private void refreshBoundingBox() {
        if (raftBlocks.isEmpty()) return;
        int minCol = Integer.MAX_VALUE, maxCol = Integer.MIN_VALUE;
        int minRow = Integer.MAX_VALUE, maxRow = Integer.MIN_VALUE;
        for (BlockPos p : raftBlocks.keySet()) {
            minCol = Math.min(minCol, p.getX());
            maxCol = Math.max(maxCol, p.getX());
            minRow = Math.min(minRow, p.getZ());
            maxRow = Math.max(maxRow, p.getZ());
        }
        // Each cell is 1×1 block; add 1 to get inclusive max edge
        this.setBoundingBox(new AABB(
            getX() + minCol,    getY(),             getZ() + minRow,
            getX() + maxCol+1,  getY() + SLAB_HEIGHT, getZ() + maxRow+1
        ));
    }

    /**
     * Returns the current raft surface AABB (footprint only, no height).
     */
    public AABB buildRaftAABB() {
        return getBoundingBox();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Public accessors
    // ══════════════════════════════════════════════════════════════════════

    /** Read-only view of the block map, used by the renderer. */
    public Map<BlockPos, BlockState> getRaftBlocks() {
        return Collections.unmodifiableMap(raftBlocks);
    }

    public float getHeading() {
        return entityData.get(HEADING);
    }

    /**
     * Converts a GRID-relative BlockPos to an absolute world BlockPos,
     * snapping the entity's floating position to block-grid integers first.
     */
    public BlockPos gridToWorld(BlockPos gridPos) {
        return new BlockPos(
            Mth.floor(getX()) + gridPos.getX(),
            Mth.floor(getY()),
            Mth.floor(getZ()) + gridPos.getZ()
        );
    }

    /**
     * Converts an absolute world position to a GRID-relative BlockPos.
     */
    public BlockPos worldToGrid(BlockPos worldPos) {
        return new BlockPos(
            worldPos.getX() - Mth.floor(getX()),
            0,
            worldPos.getZ() - Mth.floor(getZ())
        );
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Vanilla overrides
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public boolean isPickable() { return true; }  // can be targeted by players

    @Override
    public boolean isPushable() { return false; } // raft is immovable by physics

    @Override
    public boolean isInvulnerableTo(net.minecraft.world.damagesource.DamageSource source) {
        return true; // raft cannot be damaged (only modified by hammer/axe)
    }
}
