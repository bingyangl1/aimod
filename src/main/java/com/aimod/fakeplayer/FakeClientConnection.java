package com.aimod.fakeplayer;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.PacketFlow;
import org.jetbrains.annotations.NotNull;

public class FakeClientConnection extends Connection {
    public FakeClientConnection(PacketFlow receiving) {
        super(receiving);
        try {
            var channelField = Connection.class.getDeclaredField("channel");
            channelField.setAccessible(true);
            channelField.set(this, new EmbeddedChannel());
        } catch (Exception ignored) {
        }
    }

    @Override public void send(@NotNull net.minecraft.network.protocol.Packet<?> packet) {}
    @Override public void send(@NotNull Packet<?> packet, @org.jetbrains.annotations.Nullable net.minecraft.network.PacketSendListener listener) { if (listener != null) listener.onSuccess(); }
    @Override public void setReadOnly() {}
    @Override public void handleDisconnection() {}
    @Override public void setListenerForServerboundHandshake(@NotNull PacketListener packetListener) {}
    @Override public <T extends PacketListener> void setupInboundProtocol(@NotNull ProtocolInfo<T> protocolInfo, @NotNull T packetListener) {}
    @Override public void disconnect(@NotNull net.minecraft.network.chat.Component reason) {}
    @Override public boolean isConnected() { return true; }
    @Override public boolean isMemoryConnection() { return true; }
    @Override public void flushChannel() {}
    @Override public void setupCompression(int threshold, boolean validateDecompressed) {}
}