package com.raftmod.item;

import com.raftmod.entity.RaftEntity;
import com.raftmod.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * ══════════════════════════════════════════════════════════════════════════════
 *  HammerItem  –  The Raft Expansion / Demolition Tool
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *  Right-click behaviour
 *  ─────────────────────
 *  • Pointed at a RAFT SLAB face  → attempt to add an adjacent slab to the
 *    raft entity in the direction of the hit face.
 *  • Pointed at empty air above/beside the raft → same, using the closest
 *    valid open grid slot.
 *
 *  Left-click behaviour  (handled in RaftEventHandlers.onLeftClick)
 *  ─────────────────────
 *  • When the hammer is the active hand item AND the player left-clicks a raft
 *    slab, that slab is removed (subject to the 2×2 core-protection rule).
 *
 *  Ghost preview
 *  ─────────────
 *  The CLIENT-SIDE ghost block outline is drawn by
 *  {@link com.raftmod.client.HammerGhostOverlay}, which looks up the nearest
 *  valid placement slot by calling {@link #getPreviewGridPos}.  This method
 *  is accessible from the overlay without needing a server round-trip.
 *
 *  Why extend Item (not AxeItem)?
 *  ──────────────────────────────
 *  The hammer is a specialised tool.  We extend plain Item so we don't inherit
 *  axe stripping behaviour.  Tool speed against RaftSlabBlock is handled via
 *  the mineable/axe tag which already grants correct-tool treatment; the hammer
 *  additionally overrides getDestroySpeed() for extra flair.
 */
public class HammerItem extends Item {

    /** Cost in durability per slab placed. */
    private static final int PLACE_COST = 5;

    public HammerItem(Properties properties) {
        super(properties);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Right-click: place a new slab on the raft
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        Player player = ctx.getPlayer();
        if (player == null) return InteractionResult.PASS;

        // Only act on the server; client just updates the ghost.
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        // Find the raft entity the player is interacting with
        RaftEntity raft = findNearestRaft(level, player);
        if (raft == null) return InteractionResult.PASS;

        // Determine target grid position from click hit face
        BlockPos clickedWorld = ctx.getClickedPos();
        BlockPos gridPos = raft.worldToGrid(clickedWorld);

        // Try placing in the clicked direction's neighbour
        net.minecraft.core.Direction face = ctx.getClickedFace();
        BlockPos targetGrid = new BlockPos(
            gridPos.getX() + face.getStepX(),
            0,
            gridPos.getZ() + face.getStepZ()
        );

        if (raft.addBlock(targetGrid)) {
            // Consume durability
            ctx.getItemInHand().hurtAndBreak(PLACE_COST, player,
                LivingEntity -> {});

            level.playSound(null, raft.blockPosition(),
                SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0f, 0.9f);
            return InteractionResult.CONSUME;
        }

        player.displayClientMessage(
            Component.translatable("item.raftmod.raft_hammer.invalid_placement"), true);
        return InteractionResult.FAIL;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Tool speed – faster than bare hand for raft slabs
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public float getDestroySpeed(ItemStack stack,
                                  net.minecraft.world.level.block.state.BlockState state) {
        if (state.is(ModRegistries.RAFT_SLAB.get())) return 8.0f;
        return super.getDestroySpeed(stack, state);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Ghost preview helper – called CLIENT-SIDE by HammerGhostOverlay
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Given the player's current look ray, returns the GRID-relative BlockPos
     * of the cell that would be placed next if the player right-clicked now,
     * or {@code null} if no valid placement exists.
     *
     * This method runs on the CLIENT thread – keep it cheap.
     *
     * @param player      the local player
     * @param raft        the raft entity being aimed at
     * @return grid position for the preview ghost block, or null
     */
    public static BlockPos getPreviewGridPos(Player player, RaftEntity raft) {
        // Raycast from player eye position along look direction (4 block reach)
        Vec3 eyePos = player.getEyePosition();
        Vec3 lookDir = player.getLookAngle();
        Vec3 end = eyePos.add(lookDir.scale(5.0));

        // Intersect the ray with each occupied grid cell's AABB
        BlockPos bestGrid = null;
        double bestDist = Double.MAX_VALUE;

        for (BlockPos gp : raft.getRaftBlocks().keySet()) {
            Vec3 worldOrigin = Vec3.atLowerCornerOf(raft.gridToWorld(gp));
            AABB cellBox = new AABB(worldOrigin, worldOrigin.add(1, 0.5, 1));

            var hit = cellBox.clip(eyePos, end);
            if (hit.isPresent()) {
                double dist = hit.get().distanceToSqr(eyePos);
                if (dist < bestDist) {
                    bestDist = dist;
                    // Return the adjacent open cell in the look direction
                    Vec3 norm = hit.get().subtract(cellBox.getCenter()).normalize();
                    int dx = norm.x > 0.5 ? 1 : (norm.x < -0.5 ? -1 : 0);
                    int dz = norm.z > 0.5 ? 1 : (norm.z < -0.5 ? -1 : 0);
                    bestGrid = gp.offset(dx, 0, dz);
                }
            }
        }

        if (bestGrid == null) return null;
        // Only return a grid pos that is EMPTY (not already occupied)
        return raft.getRaftBlocks().containsKey(bestGrid) ? null : bestGrid;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Utility
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Finds the nearest RaftEntity to the player within 10 blocks.
     */
    public static RaftEntity findNearestRaft(Level level, Player player) {
        List<RaftEntity> rafts = level.getEntitiesOfClass(
            RaftEntity.class,
            player.getBoundingBox().inflate(10.0),
            e -> true
        );
        return rafts.isEmpty() ? null : rafts.get(0);
    }
}
