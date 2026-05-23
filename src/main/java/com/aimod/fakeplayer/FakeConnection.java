package com.aimod.fakeplayer;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;

/**
 * Fake network connection for FakePlayer.
 * Extends Connection and discards all packets.
 */
public class FakeConnection extends Connection {

    public FakeConnection() {
        super(PacketFlow.CLIENTBOUND);
    }

    @Override
    public void send(Packet<?> packet) {
    }

    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
        if (listener != null) {
            listener.onSuccess();
        }
    }

    @Override
    public void disconnect(Component reason) {
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public boolean isMemoryConnection() {
        return true;
    }

    @Override
    public void flushChannel() {
    }

    @Override
    public void setupCompression(int threshold, boolean validateDecompressed) {
    }

    @Override
    public void handleDisconnection() {
    }
}