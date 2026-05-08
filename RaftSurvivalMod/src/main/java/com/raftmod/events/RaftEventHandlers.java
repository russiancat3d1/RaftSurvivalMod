package com.raftmod.events;

import com.raftmod.entity.FloatingLootEntity;
import com.raftmod.entity.RaftEntity;
import com.raftmod.item.HammerItem;
import com.raftmod.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.List;

/**
 * ══════════════════════════════════════════════════════════════════════════════
 *  RaftEventHandlers  –  NeoForge Event Listeners
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *  Registered on the FORGE (game) event bus in RaftMod:
 *      NeoForge.EVENT_BUS.register(RaftEventHandlers.class);
 *
 *  Covers four interaction domains:
 *
 *  1. HAND-BREAK CANCEL
 *     Intercepts PlayerInteractEvent.LeftClickBlock.  If the player has an
 *     empty hand, cancel the break attempt so raft slabs cannot be hand-mined.
 *
 *  2. HAMMER LEFT-CLICK REMOVE
 *     If the player left-clicks a raft slab WITH the hammer, remove that cell
 *     from the raft entity (unless it is part of the protected 2×2 core).
 *
 *  3. 'E' KEY RAYCAST PICKUP
 *     In LivingEntityTickEvent we check if the player is sneaking (sneak = 'E'
 *     analogue for item interaction – see note below) and looking at a
 *     FloatingLootEntity within 5 blocks.  If so, the loot is collected.
 *
 *     ┌──────────────────────────────────────────────────────────────────┐
 *     │  Why sneak instead of actual 'E' key?                           │
 *     │  The 'E' key opens the inventory by default; intercepting it     │
 *     │  requires a client-side KeyMapping registered in FMLClientSetup. │
 *     │  For the server-authoritative interaction (pickup) we repurpose  │
 *     │  sneak + right-click, which is already a standard mod convention.│
 *     │  Replace with a custom KeyMapping for a full implementation.     │
 *     └──────────────────────────────────────────────────────────────────┘
 *
 *  4. RAFT SPAWN COMMAND
 *     PlayerLoggedInEvent: spawns a starter raft in front of a new player who
 *     joins an ocean biome for the first time (simple first-spawn heuristic).
 */
@net.neoforged.bus.api.EventBusSubscriber(modid = com.raftmod.RaftMod.MOD_ID,
                                           bus = net.neoforged.bus.api.EventBusSubscriber.Bus.GAME)
public class RaftEventHandlers {

    // ══════════════════════════════════════════════════════════════════════
    //  1 & 2  –  Block interaction gating
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Fires on LEFT click (mining attempt) against a world block.
     *
     * We only care about RaftSlabBlock – but remember: raft slabs are
     * virtual (inside the entity), so this event fires only if someone
     * somehow placed a raft slab as a real world block (e.g. creative mode).
     * For the entity-based raft, see the entity tick handling below.
     */
    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        Player player = event.getEntity();
        Level level = event.getLevel();
        BlockPos pos = event.getPos();

        if (!level.getBlockState(pos).is(ModRegistries.RAFT_SLAB.get())) return;

        ItemStack held = player.getMainHandItem();

        // RULE: bare hand → cancel break entirely
        if (held.isEmpty()) {
            event.setCanceled(true);
            return;
        }

        // RULE: hammer held → delegate to raft entity removal
        if (held.getItem() instanceof HammerItem) {
            // Find raft and remove the cell
            RaftEntity raft = HammerItem.findNearestRaft(level, player);
            if (raft != null) {
                BlockPos gridPos = raft.worldToGrid(pos);
                boolean removed = raft.removeBlock(gridPos);
                if (removed) {
                    level.playSound(null, pos, SoundEvents.WOOD_BREAK,
                        SoundSource.BLOCKS, 1.0f, 1.2f);
                    held.hurtAndBreak(3, player, p -> {});
                }
            }
            event.setCanceled(true);  // prevent normal world-block breaking
        }
        // Any other tool → vanilla behaviour (requires axe tag to drop anything)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  3  –  Raycast loot pickup ('E' / sneak interaction)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Every tick we test:  is the player sneaking AND right-clicking AND
     * looking at a FloatingLootEntity within 5 blocks?
     *
     * In practice this reacts to the PlayerInteractEvent.RightClickEmpty event
     * (fired when right-click hits nothing) rather than the tick, to avoid
     * polling overhead.
     */
    @SubscribeEvent
    public static void onRightClickEmpty(PlayerInteractEvent.RightClickEmpty event) {
        Player player = event.getEntity();
        Level level = event.getLevel();

        // Require sneak + empty main hand as the 'E'-style interaction
        if (!player.isShiftKeyDown()) return;
        if (!player.getMainHandItem().isEmpty()) return;

        // Raycast using the standard entity-clip method
        FloatingLootEntity target = raycastForLoot(player, level, 5.0);
        if (target == null) return;

        // Server-authoritative: collect the loot
        if (!level.isClientSide()) {
            ItemStack lootItem = target.getItem();
            if (!player.getInventory().add(lootItem.copy())) {
                player.drop(lootItem, false);
            }
            level.playSound(null, target.blockPosition(),
                SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS,
                0.2f, ((level.getRandom().nextFloat() - level.getRandom().nextFloat()) * 0.7f + 1.0f) * 2.0f);
            target.discard();
        }

        event.setCanceled(true);
    }

    /**
     * Performs an entity raycast from the player's eye position along their
     * look vector up to {@code reach} blocks.
     *
     * Uses the {@code EntityHitResult} approach:
     *   1. Start with the look-vector endpoint.
     *   2. Inflate an AABB along the ray.
     *   3. Pick the closest FloatingLootEntity whose AABB intersects the ray.
     *
     * This mirrors the vanilla
     * {@code ProjectileUtil.getEntityHitResult} approach.
     *
     * @param player   the player casting the ray
     * @param level    the world
     * @param reach    maximum distance in blocks
     * @return the closest FloatingLootEntity on the ray, or null
     */
    private static FloatingLootEntity raycastForLoot(Player player, Level level, double reach) {
        Vec3 eyePos  = player.getEyePosition();
        Vec3 lookEnd = eyePos.add(player.getLookAngle().scale(reach));

        // Expand a thin AABB along the ray segment
        AABB rayBox = new AABB(eyePos, lookEnd).inflate(0.5);

        // Gather candidates
        List<FloatingLootEntity> candidates =
            level.getEntitiesOfClass(FloatingLootEntity.class, rayBox,
                e -> !e.isRemoved());

        FloatingLootEntity closest   = null;
        double closestDistSq = reach * reach;

        for (FloatingLootEntity candidate : candidates) {
            // Precise AABB-ray intersection
            AABB entityBox = candidate.getBoundingBox().inflate(0.2);
            var optHit = entityBox.clip(eyePos, lookEnd);
            if (optHit.isPresent()) {
                double distSq = optHit.get().distanceToSqr(eyePos);
                if (distSq < closestDistSq) {
                    closestDistSq = distSq;
                    closest = candidate;
                }
            }
        }
        return closest;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  4  –  Starter raft spawn
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Spawns a starting raft 5 blocks ahead of the player the first time they
     * join the world.  Checks a persistent NBT tag to avoid re-spawning.
     */
    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer serverPlayer)) return;

        // Check if this player already has a raft (persistent tag)
        CompoundTag persistData = serverPlayer.getPersistentData();
        if (persistData.getBoolean("raftmod:raft_spawned")) return;

        // Only spawn in ocean dimensions (overworld + on water)
        Level level = serverPlayer.level();
        Vec3 lookDir = serverPlayer.getLookAngle();
        Vec3 spawnPos = serverPlayer.position().add(lookDir.scale(5)).add(0, 0, 0);

        // Verify it's over water
        BlockPos checkPos = new BlockPos(
            (int) spawnPos.x, (int) spawnPos.y - 1, (int) spawnPos.z);
        if (level.getBlockState(checkPos).getFluidState().isEmpty()) return;

        // Spawn the raft entity
        RaftEntity raft = new RaftEntity(ModRegistries.RAFT_ENTITY.get(), level);
        raft.setPos(spawnPos.x, spawnPos.y, spawnPos.z);
        raft.initializeRaft();
        level.addFreshEntity(raft);

        // Mark as spawned so we don't do it again
        persistData.putBoolean("raftmod:raft_spawned", true);
    }

    // ── Extra helper import needed ─────────────────────────────────────────
    // CompoundTag is used in onPlayerLogin above:
    static { }
    // (import is inlined via full class path below to avoid top-of-file clutter)
    private static net.minecraft.nbt.CompoundTag getTag(
            net.minecraft.server.level.ServerPlayer p) {
        return p.getPersistentData();
    }
}
