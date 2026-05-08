package com.raftmod.registry;

import com.raftmod.RaftMod;
import com.raftmod.block.RaftSlabBlock;
import com.raftmod.entity.FloatingLootEntity;
import com.raftmod.entity.GrapplingHookEntity;
import com.raftmod.entity.RaftEntity;
import com.raftmod.item.GrapplingHookItem;
import com.raftmod.item.HammerItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModRegistries {

    // ── DeferredRegisters ─────────────────────────────────────────────────
    public static final DeferredRegister<Block>          BLOCKS   = DeferredRegister.create(BuiltInRegistries.BLOCK,       RaftMod.MOD_ID);
    public static final DeferredRegister<Item>           ITEMS    = DeferredRegister.create(BuiltInRegistries.ITEM,        RaftMod.MOD_ID);
    public static final DeferredRegister<EntityType<?>>  ENTITIES = DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, RaftMod.MOD_ID);

    // ══════════════════════════════════════════════════════════════════════
    //  BLOCKS
    // ══════════════════════════════════════════════════════════════════════

    /** The wooden slab that makes up the raft surface. */
    public static final DeferredHolder<Block, RaftSlabBlock> RAFT_SLAB =
        BLOCKS.register("raft_slab", () -> new RaftSlabBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .strength(2.5f, 3.0f)     // hardness / resistance
                .sound(SoundType.WOOD)
                .noCollission()            // collision handled by entity, not world block
        ));

    // ══════════════════════════════════════════════════════════════════════
    //  ITEMS
    // ══════════════════════════════════════════════════════════════════════

    /** Block item for placing the raft slab (used internally / creative). */
    public static final DeferredHolder<Item, BlockItem> RAFT_SLAB_ITEM =
        ITEMS.register("raft_slab", () -> new BlockItem(RAFT_SLAB.get(), new Item.Properties()));

    /**
     * The Hammer – used to expand the raft by one slab at a time.
     * Shows a ghost-block preview client-side.
     */
    public static final DeferredHolder<Item, HammerItem> HAMMER =
        ITEMS.register("raft_hammer", () -> new HammerItem(
            new Item.Properties().stacksTo(1).durability(200)
        ));

    /**
     * The Grappling Hook – thrown projectile that latches onto floating loot
     * and pulls it toward the player.
     */
    public static final DeferredHolder<Item, GrapplingHookItem> GRAPPLING_HOOK =
        ITEMS.register("grappling_hook", () -> new GrapplingHookItem(
            new Item.Properties().stacksTo(1)
        ));

    // ══════════════════════════════════════════════════════════════════════
    //  ENTITIES
    // ══════════════════════════════════════════════════════════════════════

    /**
     * The raft – a non-living entity that stores and renders a grid of blocks
     * and physically moves players resting on its surface.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<RaftEntity>> RAFT_ENTITY =
        ENTITIES.register("raft", () -> EntityType.Builder
            .<RaftEntity>of(RaftEntity::new, MobCategory.MISC)
            .sized(2.0f, 0.5f)        // will be overridden dynamically as raft expands
            .clientTrackingRange(64)
            .updateInterval(3)        // sync position every 3 ticks for smooth movement
            .build("raft"));

    /**
     * Custom floating loot item – extends ItemEntity with buoyancy logic.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<FloatingLootEntity>> FLOATING_LOOT_ENTITY =
        ENTITIES.register("floating_loot", () -> EntityType.Builder
            .<FloatingLootEntity>of(FloatingLootEntity::new, MobCategory.MISC)
            .sized(0.25f, 0.25f)
            .clientTrackingRange(32)
            .updateInterval(5)
            .build("floating_loot"));

    /**
     * The grappling hook projectile.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<GrapplingHookEntity>> GRAPPLING_HOOK_ENTITY =
        ENTITIES.register("grappling_hook_projectile", () -> EntityType.Builder
            .<GrapplingHookEntity>of(GrapplingHookEntity::new, MobCategory.MISC)
            .sized(0.25f, 0.25f)
            .clientTrackingRange(32)
            .updateInterval(1)         // fast update for precise physics
            .build("grappling_hook_projectile"));

    // ── Wire everything into the mod event bus ────────────────────────────
    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        ENTITIES.register(modEventBus);
    }
}
