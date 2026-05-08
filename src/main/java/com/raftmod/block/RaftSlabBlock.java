package com.raftmod.block;

import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * RaftSlabBlock  –  The half-slab that makes up the raft surface.
 *
 * Extends vanilla SlabBlock so we inherit:
 *   • The HALF block-state property (TOP / BOTTOM / DOUBLE)
 *   • Correct slab hit-box and rendering
 *   • The block-item drop behaviour
 *
 * Tool gating (axe-only) is handled by the data tag at:
 *   data/minecraft/tags/blocks/mineable/axe.json
 *
 * Hand-break cancellation is handled by RaftEventHandlers.onLeftClickBlock.
 */
public class RaftSlabBlock extends SlabBlock {

    public RaftSlabBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }
}
