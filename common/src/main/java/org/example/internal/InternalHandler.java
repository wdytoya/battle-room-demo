package org.example.internal;

import io.netty.channel.ChannelHandlerContext;

/**
 * Application handler for one internal TCP connection.
 */
@FunctionalInterface
public interface InternalHandler {

    void onMessage(ChannelHandlerContext ctx, InternalMessage msg) throws Exception;
}
