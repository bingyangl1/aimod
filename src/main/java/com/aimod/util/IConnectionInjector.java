package com.aimod.util;

import io.netty.channel.Channel;

/**
 * Interface for injecting a Channel into a Connection.
 * Implemented by ConnectionMixin to allow FakeClientConnection
 * to set an EmbeddedChannel without reflection.
 */
public interface IConnectionInjector {
    void aimod$setChannel(Channel channel);
}
