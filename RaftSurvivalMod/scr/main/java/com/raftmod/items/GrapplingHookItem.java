package com.raftmod.item;

import com.raftmod.entity.GrapplingHookEntity;
import com.raftmod.registry.ModRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * ══════════════════════════════════════════════════════════════════════════════
 *  GrapplingHookItem  –  Throws the Grappling Hook Projectile
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *  Right-click behaviour
 *  ─────────────────────
 *  • First right-click  → launches a GrapplingHookEntity from the player's
 *    eye position along their look vector at LAUNCH_SPEED.
 *  • Second right-click (hook already in flight or latched) → retracts
 *    the active hook by calling discard() on it.
 *
 *  Only ONE hook per player may be active at a time.  This is enforced by
 *  scanning the level for existing GrapplingHookEntity instances owned by
 *  this player before launching a new one.
 *
 *  Cooldown
 *  ────────
 *  A 10-tick (0.5 s) cooldown is applied via the vanilla item use-cooldown
 *  mechanism to prevent spam-throwing.
 */
public class GrapplingHookItem extends Item {

    /** Ticks of cooldown after retraction before a new hook can be thrown. */
    private static final int RETRACT_COOLDOWN = 10;

    public GrapplingHookItem(Properties properties) {
        super(properties);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Right-click: launch or retract
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player,
                                                   InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        // Check for an existing hook belonging to this player
        GrapplingHookEntity existingHook = findPlayerHook(level, player);

        if (existingHook != null) {
            // ── RETRACT: discard the active hook ──────────────────────────
            if (!level.isClientSide()) {
                existingHook.discard();
                level.playSound(null, player.blockPosition(),
                    SoundEvents.FISHING_BOBBER_RETRIEVE,
                    SoundSource.NEUTRAL, 0.5f, 1.2f);

                // Short cooldown so the player can't immediately re-throw
                player.getCooldowns().addCooldown(this, RETRACT_COOLDOWN);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }

        // ── LAUNCH: fire a new hook ────────────────────────────────────────
        if (!level.isClientSide()) {
            GrapplingHookEntity.launch(player, level);
            player.awardStat(Stats.ITEM_USED.get(this));
        }

        // Swing the arm on both sides for visual feedback
        player.swing(hand);

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Utility
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Searches the level for any GrapplingHookEntity whose owner is this player.
     *
     * @param level   the current level
     * @param player  the player to match
     * @return the active hook, or {@code null} if none found
     */
    private static GrapplingHookEntity findPlayerHook(Level level, Player player) {
        List<GrapplingHookEntity> hooks = level.getEntitiesOfClass(
            GrapplingHookEntity.class,
            player.getBoundingBox().inflate(64.0),   // wide search – hook travels far
            hook -> {
                net.minecraft.world.entity.Entity owner = hook.getOwner();
                return owner != null && owner.getUUID().equals(player.getUUID());
            }
        );
        return hooks.isEmpty() ? null : hooks.get(0);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Tooltip
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public void appendHoverText(ItemStack stack,
                                 TooltipContext context,
                                 java.util.List<net.minecraft.network.chat.Component> lines,
                                 net.minecraft.world.item.TooltipFlag flag) {
        lines.add(net.minecraft.network.chat.Component
            .translatable("item.raftmod.grappling_hook.tooltip.use"));
        lines.add(net.minecraft.network.chat.Component
            .translatable("item.raftmod.grappling_hook.tooltip.retract"));
    }
}
