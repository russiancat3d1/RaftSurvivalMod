package com.raftmod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.raftmod.entity.RaftEntity;
import com.raftmod.item.HammerItem;
import com.raftmod.registry.ModRegistries;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

import java.util.List;

/**
 * ══════════════════════════════════════════════════════════════════════════════
 *  HammerGhostOverlay  –  Client-Side Ghost Block Preview
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *  This overlay is registered on the HUD layer stack in RaftMod.  It runs every
 *  frame (between game-render and GUI-render) so it can draw directly into
 *  3D world space before the GUI flattens onto the screen.
 *
 *  Strategy
 *  ─────────
 *  1. Check if the local player is holding a HammerItem.
 *  2. Find the nearest RaftEntity.
 *  3. Ask HammerItem.getPreviewGridPos() for the grid cell that would be placed.
 *  4. Render a translucent ghost slab outline at that world position using
 *     LevelRenderer's block outline drawing utilities.
 *
 *  The outline is drawn using the same technique as vanilla's block-break
 *  highlight: a line-strip wire cube rendered into the LINES render type.
 */
@OnlyIn(Dist.CLIENT)
public class HammerGhostOverlay implements LayeredDraw.Layer {

    public static final HammerGhostOverlay INSTANCE = new HammerGhostOverlay();

    // Ghost tint: semi-transparent green
    private static final float GHOST_R = 0.4f;
    private static final float GHOST_G = 1.0f;
    private static final float GHOST_B = 0.4f;
    private static final float GHOST_A = 0.4f;

    // ══════════════════════════════════════════════════════════════════════
    //  LayeredDraw.Layer implementation
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        // Only show ghost when holding the hammer
        if (!(player.getMainHandItem().getItem() instanceof HammerItem) &&
            !(player.getOffhandItem().getItem() instanceof HammerItem)) return;

        // Find nearest raft
        List<RaftEntity> rafts = mc.level.getEntitiesOfClass(
            RaftEntity.class,
            player.getBoundingBox().inflate(12.0),
            e -> true
        );
        if (rafts.isEmpty()) return;
        RaftEntity raft = rafts.get(0);

        // Ask the item logic for the preview cell
        BlockPos previewGrid = HammerItem.getPreviewGridPos(player, raft);
        if (previewGrid == null) return;

        // Convert grid → world position
        BlockPos worldPos = raft.gridToWorld(previewGrid);
        BlockState ghostState = ModRegistries.RAFT_SLAB.get().defaultBlockState();

        // Draw the ghost in world space
        renderGhostBlock(mc, worldPos, ghostState, deltaTracker.getGameTimeDeltaPartialTick(true));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  World-space ghost block drawing
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Draws a translucent wire-frame box matching the block's collision shape
     * at the given world position.
     *
     * Uses the vanilla outline AABB renderer that backs LevelRenderer's
     * selected-block highlight.  We draw TWO passes:
     *   Pass 1: filled translucent quads using TRANSLUCENT_MOVING_BLOCK render type.
     *   Pass 2: solid white line outline.
     */
    private void renderGhostBlock(Minecraft mc, BlockPos worldPos,
                                   BlockState state, float partialTick) {
        // Camera offset – all world rendering is relative to the camera pos
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();

        double dx = worldPos.getX() - cam.x;
        double dy = worldPos.getY() - cam.y;
        double dz = worldPos.getZ() - cam.z;

        // Ghost block is a half-slab (0–0.5 Y)
        double x0 = dx,       y0 = dy,       z0 = dz;
        double x1 = dx + 1.0, y1 = dy + 0.5, z1 = dz + 1.0;

        // Inset slightly so the outline appears just outside the block
        double e = 0.001;
        x0 -= e; y0 -= e; z0 -= e;
        x1 += e; y1 += e; z1 += e;

        // ── Filled ghost quads (translucent) ─────────────────────────────
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();  // render through terrain

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        // 6 faces
        float r = GHOST_R, g = GHOST_G, b = GHOST_B, a = GHOST_A;
        addQuad(buf, (float)x0,(float)y0,(float)z0, (float)x1,(float)y0,(float)z0, (float)x1,(float)y0,(float)z1, (float)x0,(float)y0,(float)z1, r,g,b,a); // bottom
        addQuad(buf, (float)x0,(float)y1,(float)z0, (float)x0,(float)y1,(float)z1, (float)x1,(float)y1,(float)z1, (float)x1,(float)y1,(float)z0, r,g,b,a); // top
        addQuad(buf, (float)x0,(float)y0,(float)z0, (float)x0,(float)y1,(float)z0, (float)x1,(float)y1,(float)z0, (float)x1,(float)y0,(float)z0, r,g,b,a); // north
        addQuad(buf, (float)x0,(float)y0,(float)z1, (float)x1,(float)y0,(float)z1, (float)x1,(float)y1,(float)z1, (float)x0,(float)y1,(float)z1, r,g,b,a); // south
        addQuad(buf, (float)x0,(float)y0,(float)z0, (float)x0,(float)y0,(float)z1, (float)x0,(float)y1,(float)z1, (float)x0,(float)y1,(float)z0, r,g,b,a); // west
        addQuad(buf, (float)x1,(float)y0,(float)z0, (float)x1,(float)y1,(float)z0, (float)x1,(float)y1,(float)z1, (float)x1,(float)y0,(float)z1, r,g,b,a); // east

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferUploader.drawWithShader(buf.buildOrThrow());

        // ── Wire-frame outline ────────────────────────────────────────────
        BufferBuilder lines = tess.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        float la = 0.8f;
        // 12 edges of the box
        addEdge(lines, x0,y0,z0, x1,y0,z0, la);
        addEdge(lines, x1,y0,z0, x1,y0,z1, la);
        addEdge(lines, x1,y0,z1, x0,y0,z1, la);
        addEdge(lines, x0,y0,z1, x0,y0,z0, la);
        addEdge(lines, x0,y1,z0, x1,y1,z0, la);
        addEdge(lines, x1,y1,z0, x1,y1,z1, la);
        addEdge(lines, x1,y1,z1, x0,y1,z1, la);
        addEdge(lines, x0,y1,z1, x0,y1,z0, la);
        addEdge(lines, x0,y0,z0, x0,y1,z0, la);
        addEdge(lines, x1,y0,z0, x1,y1,z0, la);
        addEdge(lines, x1,y0,z1, x1,y1,z1, la);
        addEdge(lines, x0,y0,z1, x0,y1,z1, la);

        BufferUploader.drawWithShader(lines.buildOrThrow());

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    // ── Vertex helpers ─────────────────────────────────────────────────────

    private void addQuad(BufferBuilder buf,
                          float x0, float y0, float z0,
                          float x1, float y1, float z1,
                          float x2, float y2, float z2,
                          float x3, float y3, float z3,
                          float r, float g, float b, float a) {
        buf.addVertex(x0, y0, z0).setColor(r, g, b, a);
        buf.addVertex(x1, y1, z1).setColor(r, g, b, a);
        buf.addVertex(x2, y2, z2).setColor(r, g, b, a);
        buf.addVertex(x3, y3, z3).setColor(r, g, b, a);
    }

    private void addEdge(BufferBuilder buf,
                          double x0, double y0, double z0,
                          double x1, double y1, double z1, float a) {
        buf.addVertex((float)x0, (float)y0, (float)z0).setColor(1f, 1f, 1f, a);
        buf.addVertex((float)x1, (float)y1, (float)z1).setColor(1f, 1f, 1f, a);
    }
}
