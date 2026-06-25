package com.aimod.mixin;

import com.aimod.ai.BlockChangeTracker;
import com.aimod.ai.OreIndex;
import com.aimod.ai.OreIndexHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to track block changes in real-time for the OreIndex.
 *
 * <p>Inspired by XRay's LevelMixin — intercepts Level.setBlock() to detect
 * when blocks change, so the ore index can be updated without re-scanning.</p>
 *
 * <p>This is only active on the server side (ServerLevel).</p>
 */
@Mixin(Level.class)
public abstract class BlockChangeMixin {

    /**
     * Intercept setBlock() to detect block changes.
     * When a block changes to/from an ore, update the OreIndex.
     */
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("RETURN"))
    private void onBlockChange(BlockPos pos, BlockState newState, int flags, int recursionLimit,
                               CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) return; // setBlock failed

        // Only track on server side
        Level self = (Level) (Object) this;
        if (self.isClientSide()) return;

        // Get the OreIndex from the level (if available)
        // The OreIndex is stored as a level capability or accessed via the server
        // For now, we use a static accessor pattern
        OreIndex oreIndex = OreIndexHolder.getForLevel(self);
        if (oreIndex == null) return;

        BlockChangeTracker.onBlockChange(self, pos, newState, oreIndex);
    }
}
