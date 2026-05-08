package com.raftmod;

import com.raftmod.client.HammerGhostOverlay;
import com.raftmod.client.RaftEntityRenderer;
import com.raftmod.entity.RaftEntity;
import com.raftmod.registry.ModRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * ══════════════════════════════════════════════════════════════════════════════
 *  RaftMod  –  @Mod Entry Point
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *  Responsibilities:
 *   • Wires all DeferredRegisters into the mod event bus via ModRegistries.
 *   • On the client lifecycle, registers:
 *       - RaftEntityRenderer  for RaftEntity
 *       - HammerGhostOverlay  as a HUD LayeredDraw.Layer
 *
 *  RaftEventHandlers is registered automatically via its @EventBusSubscriber
 *  annotation, so no manual registration is needed here.
 */
@Mod(RaftMod.MOD_ID)
public class RaftMod {

    public static final String MOD_ID = "raftmod";

    public RaftMod(IEventBus modEventBus) {
        // Register all blocks, items, and entity types
        ModRegistries.register(modEventBus);

        // Register common + client lifecycle listeners on the MOD bus
        modEventBus.addListener(this::onCommonSetup);

        // Client-only listeners (guarded by annotation on the inner class below)
        modEventBus.addListener(ClientSetup::onClientSetup);
        modEventBus.addListener(ClientSetup::onRegisterGuiLayers);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Common setup (both sides)
    // ══════════════════════════════════════════════════════════════════════

    private void onCommonSetup(FMLCommonSetupEvent event) {
        // Nothing needed here yet; placeholder for future common-side setup
        // such as capability attachments or network channel registration.
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Client-only setup
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Inner class isolated to the client dist so server JVMs never load
     * client-only classes (EntityRenderers, LayeredDraw, etc.).
     */
    @EventBusSubscriber(modid = MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientSetup {

        /**
         * Called once after all mods finish loading, on the client only.
         * Binds the custom renderer to RaftEntity's EntityType.
         */
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(() ->
                EntityRenderers.register(
                    ModRegistries.RAFT_ENTITY.get(),
                    RaftEntityRenderer::new
                )
            );
        }

        /**
         * Registers the HammerGhostOverlay into the vanilla HUD layer stack.
         *
         * We insert it ABOVE the crosshair layer so the ghost block is drawn
         * over the world but below the HUD elements (health bar, hotbar, etc.).
         *
         * Layer ordering:
         *   ... → CROSSHAIR → HAMMER_GHOST → HOTBAR → ...
         */
        @SubscribeEvent
        public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
            event.registerAbove(
                VanillaGuiLayers.CROSSHAIR,
                new net.minecraft.resources.ResourceLocation(MOD_ID, "hammer_ghost"),
                HammerGhostOverlay.INSTANCE
            );
        }
    }
}
