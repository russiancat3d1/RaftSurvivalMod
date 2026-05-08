package com.raftmod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.raftmod.entity.RaftEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Map;

/**
 * ══════════════════════════════════════════════════════════════════════════════
 *  RaftEntityRenderer  –  Draw the Block Grid Inside the Entity
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *  Rendering pipeline
 *  ──────────────────
 *  For every cell in {@link RaftEntity#getRaftBlocks()} we:
 *    1. Push a PoseStack transformation to position the block relative to the
 *       entity's interpolated render position.
 *    2. Call {@link BlockRenderDispatcher#renderBatched} using the vanilla
 *       solid-block render type (cutout_mipped for wood slabs).
 *    3. Pop the stack.
 *
 *  Lighting is sampled at the block position directly below the slab so that
 *  ocean lighting is used rather than sky.
 *
 *  Note on interpolated positions
 *  ───────────────────────────────
 *  We receive the partial-tick interpolated position as (x, y, z) parameters –
 *  do NOT use entity.getX() / getZ() here or you will see jitter at non-zero
 *  TPS headroom.
 */
@OnlyIn(Dist.CLIENT)
public class RaftEntityRenderer extends EntityRenderer<RaftEntity> {

    private final BlockRenderDispatcher blockRenderer;

    public RaftEntityRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.blockRenderer = Minecraft.getInstance().getBlockRenderer();
        // No shadow – the raft is on water so a drop-shadow makes no sense.
        this.shadowRadius = 0f;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Main render entry point
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public void render(RaftEntity raft, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource,
                       int packedLight) {

        Map<BlockPos, BlockState> blocks = raft.getRaftBlocks();
        if (blocks.isEmpty()) return;

        poseStack.pushPose();

        // All block positions are expressed relative to the entity origin,
        // which is already at (0,0,0) in the pose stack at this point because
        // the entity renderer sets up the pose stack for us.
        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            renderCell(entry.getKey(), entry.getValue(), raft,
                       poseStack, bufferSource, packedLight);
        }

        poseStack.popPose();

        super.render(raft, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Per-cell rendering
    // ══════════════════════════════════════════════════════════════════════

    private void renderCell(BlockPos gridPos, BlockState state,
                             RaftEntity raft,
                             PoseStack poseStack, MultiBufferSource bufferSource,
                             int packedLight) {
        poseStack.pushPose();

        // Translate to the cell's relative position in entity-space.
        // gridPos is (col, 0, row); Y = 0 puts the slab at the entity's Y.
        poseStack.translate(
            gridPos.getX(),   // col offset
            0.0,              // entity Y is already the slab surface base
            gridPos.getZ()    // row offset
        );

        // Sample combined light at the world position of this block
        BlockPos worldPos = raft.gridToWorld(gridPos);
        int light = LevelLightEngine.getLightValue(raft.level(), worldPos);

        // Batched solid-block rendering.
        // The second argument is the random seed used for model rotation;
        // using the hash of the grid position gives stable per-block models.
        blockRenderer.renderBatched(
            state,
            worldPos,
            raft.level(),
            poseStack,
            bufferSource.getBuffer(RenderType.cutoutMipped()),
            false,                  // check sides = false (no neighbour culling)
            raft.level().getRandom(),
            OverlayTexture.NO_OVERLAY
        );

        poseStack.popPose();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  EntityRenderer contract
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public ResourceLocation getTextureLocation(RaftEntity entity) {
        // Not used – blocks have their own textures via their model.
        return new ResourceLocation("minecraft", "textures/block/oak_planks.png");
    }
}
