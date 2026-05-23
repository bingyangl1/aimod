package com.aimod.mixin;

import com.aimod.fakeplayer.FakePlayer;
import com.aimod.fakeplayer.FakePlayerNetHandler;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @WrapOperation(
        method = "placeNewPlayer",
        at = @At(
            value = "NEW",
            target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/network/Connection;" +
                     "Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)" +
                     "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;"
        )
    )
    private @NotNull ServerGamePacketListenerImpl replaceNetworkHandler(
        MinecraftServer server, Connection connection, ServerPlayer player,
        CommonListenerCookie cookie, Operation<ServerGamePacketListenerImpl> original
    ) {
        if (player instanceof FakePlayer fake) {
            return new FakePlayerNetHandler(server, connection, fake, cookie);
        } else {
            return original.call(server, connection, player, cookie);
        }
    }
}
