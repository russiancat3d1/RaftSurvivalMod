package com.raftmod.entity;

import com.raftmod.registry.ModRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * ══════════════════════════════════════════════════════════════════════════════
 *  GrapplingHookEntity  –  Thrown Projectile / Loot Retrieval
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *  Lifecycle
 *  ─────────
 *   1. Thrown by GrapplingHookItem.  The owner (Player) is stored via UUID.
 *   2. Travels as a standard projectile with gravity.
 *   3. On collision with a FloatingLootEntity → sets it as the "hooked" target
 *      and begins pulling it toward the owner each tick.
 *   4. On block collision → the hook embeds (sticks).  Right-clicking with
 *      the hook item retracts it.
 *   5. The hook auto-retracts after MAX_LIFETIME ticks.
 */
public class GrapplingHookEntity extends Projectile {

    // ── Constants ──────────────────────────────────────────────────────────
    /** Ticks before the hook auto-retracts (5 seconds). */
    private static final int MAX_LIFETIME = 100;
    /** Projectile travel speed (blocks/tick). */
    public static final float LAUNCH_SPEED = 1.8f;
    /** Gravity applied to the hook each tick. */
    private static final double GRAVITY = 0.03;

    // ── Synced data ────────────────────────────────────────────────────────
    /** Whether the hook has embedded into a surface or latched a loot item. */
    private static final EntityDataAccessor<Boolean> LATCHED =
        SynchedEntityData.defineId(GrapplingHookEntity.class, EntityDataSerializers.BOOLEAN);

    // ── Server state ───────────────────────────────────────────────────────
    private int lifetime = 0;
    /** The FloatingLootEntity this hook has grabbed, if any. */
    private FloatingLootEntity hookedLoot = null;

    // ── Constructor ────────────────────────────────────────────────────────
    public GrapplingHookEntity(EntityType<? extends Projectile> type, Level level) {
        super(type, level);
        this.noPhysics = true;  // we do our own movement / collision
    }

    /** Factory: launch the hook from a player's eye position. */
    public static GrapplingHookEntity launch(Player shooter, Level level) {
        GrapplingHookEntity hook = new GrapplingHookEntity(
            ModRegistries.GRAPPLING_HOOK_ENTITY.get(), level);
        hook.setOwner(shooter);
        hook.setPos(shooter.getX(), shooter.getEyeY() - 0.1, shooter.getZ());

        // Compute velocity from player look vector
        Vec3 look = shooter.getLookAngle();
        hook.setDeltaMovement(look.scale(LAUNCH_SPEED));
        hook.setYRot(shooter.getYRot());
        hook.setXRot(shooter.getXRot());

        level.addFreshEntity(hook);
        level.playSound(null, shooter.blockPosition(),
            SoundEvents.FISHING_BOBBER_THROW, SoundSource.NEUTRAL, 0.5f, 1.0f);
        return hook;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Tick
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public void tick() {
        super.tick();

        if (level().isClientSide()) return;

        lifetime++;
        if (lifetime > MAX_LIFETIME) {
            discard();
            return;
        }

        boolean latched = entityData.get(LATCHED);

        if (latched && hookedLoot != null) {
            // Hook is pulling a loot item – nothing more for the hook to do
            // (FloatingLootEntity handles its own movement toward owner)
            return;
        }

        if (latched) {
            // Embedded in a block – wait for retraction input
            return;
        }

        // ── Projectile movement + collision detection ───────────────────

        Vec3 pos    = position();
        Vec3 motion = getDeltaMovement();

        // Apply gravity
        motion = motion.add(0, -GRAVITY, 0);
        setDeltaMovement(motion);

        Vec3 nextPos = pos.add(motion);

        // 1. Check entity collisions first (loot has priority)
        checkEntityCollisions(pos, nextPos);

        // 2. If still flying, check block collisions
        if (!entityData.get(LATCHED)) {
            HitResult blockHit = level().clip(new ClipContext(
                pos, nextPos,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                this));
            if (blockHit.getType() == HitResult.Type.BLOCK) {
                onHitBlock(blockHit);
            }
        }

        // Move
        setPos(nextPos.x, nextPos.y, nextPos.z);

        // Update facing direction
        updateRotation();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Collision handling
    // ══════════════════════════════════════════════════════════════════════

    /** Checks for nearby FloatingLootEntity within this tick's movement segment. */
    private void checkEntityCollisions(Vec3 from, Vec3 to) {
        AABB movementBox = new AABB(from, to).inflate(0.3);
        List<FloatingLootEntity> nearbyLoot =
            level().getEntitiesOfClass(FloatingLootEntity.class, movementBox,
                e -> !e.isHooked());

        if (nearbyLoot.isEmpty()) return;

        // Hit the closest one
        FloatingLootEntity closest = null;
        double closestDist = Double.MAX_VALUE;
        for (FloatingLootEntity candidate : nearbyLoot) {
            double dist = candidate.distanceToSqr(this);
            if (dist < closestDist) {
                closestDist = dist;
                closest = candidate;
            }
        }

        if (closest != null) {
            onHitLoot(closest);
        }
    }

    /** Called when the hook hits a FloatingLootEntity. */
    private void onHitLoot(FloatingLootEntity loot) {
        if (!(getOwner() instanceof Player owner)) {
            discard();
            return;
        }

        // Latch the hook's position onto the loot item
        setPos(loot.getX(), loot.getY(), loot.getZ());
        setDeltaMovement(Vec3.ZERO);

        // Instruct the loot to start pulling toward the owner
        loot.setHookOwner(owner);
        hookedLoot = loot;

        entityData.set(LATCHED, true);

        level().playSound(null, blockPosition(),
            SoundEvents.FISHING_BOBBER_SPLASH, SoundSource.NEUTRAL, 0.6f, 1.2f);
    }

    /** Called when the hook embeds in a block. */
    private void onHitBlock(HitResult hit) {
        setDeltaMovement(Vec3.ZERO);
        entityData.set(LATCHED, true);

        level().playSound(null, blockPosition(),
            SoundEvents.WOOD_PLACE, SoundSource.NEUTRAL, 0.4f, 1.5f);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Rotation helper
    // ══════════════════════════════════════════════════════════════════════

    private void updateRotation() {
        Vec3 motion = getDeltaMovement();
        double hSpeed = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        setYRot((float)(Math.toDegrees(Math.atan2(motion.x, motion.z))));
        setXRot((float)(Math.toDegrees(Math.atan2(-motion.y, hSpeed))));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Vanilla overrides
    // ══════════════════════════════════════════════════════════════════════

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        // Handled manually in checkEntityCollisions
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(LATCHED, false);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Lifetime", lifetime);
        tag.putBoolean("Latched", entityData.get(LATCHED));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        lifetime = tag.getInt("Lifetime");
        entityData.set(LATCHED, tag.getBoolean("Latched"));
    }
}
