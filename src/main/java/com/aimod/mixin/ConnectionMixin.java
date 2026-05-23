package com.aimod.mixin;

import com.aimod.util.IConnectionInjector;
import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Connection.class)
public abstract class ConnectionMixin implements IConnectionInjector {
    @Shadow
    private Channel channel;

    @Override
    @SuppressWarnings("AddedMixinMembersNamePattern")
    public void aimod$setChannel(Channel channel) {
        this.channel = channel;
    }
}
