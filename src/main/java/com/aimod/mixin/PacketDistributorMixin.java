package com.aimod.mixin;

import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Third-layer packet defense: intercepts NeoForge PacketDistributor.sendToPlayer().
 * Prevents custom-mod packets from being sent to FakePlayer instances.
 *
 * <p>NeoForge class, so {@code remap = false} (no obfuscation mapping needed).</p>
 */
@Mixin(value = PacketDistributor.class, remap = false)
public abstract class PacketDistributorMixin {

    @Inject(method = "sendToPlayer", at = @At("HEAD"), cancellable = true)
    private static void interceptSendToPlayer(
        ServerPlayer player,
        @NotNull CustomPacketPayload payload,
        CustomPacketPayload[] payloads,
        CallbackInfo ci
    ) {
        if (player instanceof FakePlayer) {
            ci.cancel();
        }
    }
}
