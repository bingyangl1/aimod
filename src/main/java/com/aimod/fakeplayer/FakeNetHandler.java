package com.aimod.fakeplayer;

import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.neoforge.network.connection.ConnectionType;

/**
 * Fake network handler for FakePlayer.
 * All packets sent to the client are discarded.
 */
public class FakeNetHandler extends ServerGamePacketListenerImpl {

    private static final Connection FAKE_CONNECTION = new FakeConnection();

    public FakeNetHandler(ServerPlayer player, ServerLevel level) {
        super(level.getServer(), FAKE_CONNECTION, player,
                new CommonListenerCookie(
                        player.getGameProfile(), 0,
                        ClientInformation.createDefault(),
                        false, ConnectionType.OTHER
                ));
    }

    @Override
    public void send(Packet<?> packet) {
        // Discard all packets
    }

    @Override
    public void send(Packet<?> packet, @org.jetbrains.annotations.Nullable net.minecraft.network.PacketSendListener listener) {
        if (listener != null) {
            listener.onSuccess();
        }
    }

    @Override
    public void onDisconnect(DisconnectionDetails details) {
        // FakePlayer doesn't disconnect
    }

    @Override
    public void handleMovePlayer(net.minecraft.network.protocol.game.ServerboundMovePlayerPacket packet) {
        // FakePlayer is AI-controlled
    }

    @Override
    public void teleport(double x, double y, double z, float yaw, float pitch) {
        // FakePlayer is AI-controlled
    }
}