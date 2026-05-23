package com.aimod.mixin;

import com.aimod.fakeplayer.FakePlayer;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Second-layer packet defense: intercepts send() at the
 * ServerCommonPacketListenerImpl level. If the target player
 * is a FakePlayer, the packet is silently dropped.
 *
 * <p>This complements FakePlayerNetHandler.send() (first-layer no-op)
 * for code paths that bypass the handler override.</p>
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin {

    @Shadow
    @Final
    protected MinecraftServer server;

    @Shadow
    public abstract GameProfile getOwner();

    @Inject(
        method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void interceptSend(Packet<?> packet, PacketSendListener listener, CallbackInfo ci) {
        ServerPlayer player = this.server.getPlayerList().getPlayer(this.getOwner().getId());
        if (player instanceof FakePlayer) {
            ci.cancel();
        }
    }
}
