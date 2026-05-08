package com.raftmod.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * ══════════════════════════════════════════════════════════════════════════════
 *  FloatingLootEntity  –  Buoyant Item on the Ocean Surface
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *  Physics model
 *  ─────────────
 *  Each tick we:
 *    1. Apply a small upward spring force toward the water surface (Y target).
 *    2. Apply drag to kill excess vertical velocity.
 *    3. Add a gentle sinusoidal bob so the item visually bobs on waves.
 *    4. Suppress the vanilla item's gravity and despawn logic.
 *
 *  The "hooked" flag is set by GrapplingHookEntity.  While hooked, instead
 *  of bobbing we accelerate toward the hook owner (the player).
 */
public class FloatingLootEntity extends ItemEntity {

    /** Spring constant for the buoyancy force (higher = snappier return to surface). */
    private static final double BUOYANCY_SPRING = 0.06;
    /** Damping applied to vertical velocity each tick (0 = no damping, 1 = instant stop). */
    private static final double VERTICAL_DAMPING = 0.85;
    /** Bob amplitude in blocks. */
    private static final double BOB_AMPLITUDE = 0.06;
    /** Bob frequency in radians/tick.  At 20 ticks/s this ≈ 0.5 Hz. */
    private static final double BOB_FREQUENCY  = Math.PI / 20.0;
    /** Pull acceleration while hooked (blocks/tick²). */
    private static final double HOOK_PULL_ACCEL = 0.12;

    // ── Hooked state ───────────────────────────────────────────────────────
    /** The entity that is pulling this loot (null when free-floating). */
    private net.minecraft.world.entity.Entity hookOwner = null;

    private int age = 0;  // personal tick counter for bob phase

    // ── Constructor ────────────────────────────────────────────────────────

    public FloatingLootEntity(EntityType<? extends ItemEntity> type, Level level) {
        super(type, level);
        this.setNoGravity(true);       // we handle gravity ourselves
        this.lifespan = Integer.MAX_VALUE; // never auto-despawn
    }

    /** Convenience factory that sets the item stack. */
    public static FloatingLootEntity create(EntityType<FloatingLootEntity> type,
                                             Level level, ItemStack stack) {
        FloatingLootEntity entity = new FloatingLootEntity(type, level);
        entity.setItem(stack);
        return entity;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Tick – buoyancy + hooking physics
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public void tick() {
        age++;

        if (level().isClientSide()) {
            super.tick();
            return;
        }

        if (hookOwner != null && hookOwner.isAlive()) {
            // ── HOOKED MODE: accelerate toward the owner's position ────────
            tickHookedMovement();
        } else {
            // ── FREE FLOAT MODE: bob on the water surface ─────────────────
            tickBuoyancy();
        }

        // Move by current delta
        Vec3 motion = getDeltaMovement();
        setPos(getX() + motion.x, getY() + motion.y, getZ() + motion.z);

        // Apply horizontal drag (ocean resistance)
        setDeltaMovement(motion.x * 0.92, getDeltaMovement().y, motion.z * 0.92);
    }

    // ── Buoyancy calculation ───────────────────────────────────────────────

    private void tickBuoyancy() {
        double targetY = findWaterSurfaceY();
        if (targetY < 0) {
            // Not over water – apply normal gravity
            addDeltaMovement(new Vec3(0, -0.04, 0));
            return;
        }

        // Bob target oscillates sinusoidally above the surface
        double bobOffset = BOB_AMPLITUDE * Math.sin(age * BOB_FREQUENCY);
        double desiredY = targetY + bobOffset;

        // Spring force drives us toward desiredY
        double currentVY   = getDeltaMovement().y;
        double displacement = desiredY - getY();
        double springForce  = displacement * BUOYANCY_SPRING;

        double newVY = (currentVY + springForce) * VERTICAL_DAMPING;
        setDeltaMovement(getDeltaMovement().x, newVY, getDeltaMovement().z);
    }

    /**
     * Scans upward from below the entity to find the water/air boundary.
     * Returns the Y of the topmost water block, or -1 if not found.
     */
    private double findWaterSurfaceY() {
        int searchX = blockPosition().getX();
        int searchZ = blockPosition().getZ();

        for (int y = blockPosition().getY() + 4; y >= blockPosition().getY() - 4; y--) {
            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(searchX, y, searchZ);
            FluidState fluid = level().getBlockState(pos).getFluidState();
            FluidState above = level().getBlockState(pos.above()).getFluidState();

            if (!fluid.isEmpty() && above.isEmpty()) {
                return y + 0.9;  // float just at the surface
            }
        }
        return -1;
    }

    // ── Hooked movement calculation ────────────────────────────────────────

    private void tickHookedMovement() {
        Vec3 toOwner = hookOwner.position().subtract(position()).normalize();
        addDeltaMovement(toOwner.scale(HOOK_PULL_ACCEL));

        // Clamp speed so the item doesn't overshoot
        Vec3 motion = getDeltaMovement();
        double speed = motion.length();
        if (speed > 0.8) {
            setDeltaMovement(motion.scale(0.8 / speed));
        }

        // Auto-collect when close enough to the owner
        if (distanceTo(hookOwner) < 1.5) {
            if (hookOwner instanceof net.minecraft.world.entity.player.Player player) {
                net.minecraft.world.entity.ItemEntity clone =
                    new net.minecraft.world.entity.item.ItemEntity(
                        level(), getX(), getY(), getZ(), getItem());
                clone.setPickUpDelay(0);
                player.take(clone, 1);
                if (!player.getInventory().add(getItem())) {
                    player.drop(getItem(), false);
                }
            }
            discard();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Public API
    // ══════════════════════════════════════════════════════════════════════

    /** Called by GrapplingHookEntity when it collides with this loot. */
    public void setHookOwner(net.minecraft.world.entity.Entity owner) {
        this.hookOwner = owner;
    }

    public boolean isHooked() {
        return hookOwner != null && hookOwner.isAlive();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Serialisation
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("FloatAge", age);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        age = tag.getInt("FloatAge");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Vanilla overrides
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public boolean isAttackable() { return false; }

    /** Players cannot simply walk over and pick this up – use the grappling hook. */
    @Override
    protected void tryToPickUp(net.minecraft.world.entity.player.Player player) {
        // Intentionally suppressed; collection is handled via hook or 'E' raycast
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        // No additional synced data needed; item stack is handled by super
    }
}
