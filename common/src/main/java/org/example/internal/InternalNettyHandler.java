package org.example.internal;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

public final class InternalNettyHandler extends SimpleChannelInboundHandler<InternalMessage> {

    private final InternalHandler handler;
    private final String role;

    public InternalNettyHandler(String role, InternalHandler handler) {
        this.role = role;
        this.handler = handler;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        System.out.println("[" + role + "] peer connected " + ctx.channel().remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        System.out.println("[" + role + "] peer disconnected " + ctx.channel().remoteAddress());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, InternalMessage msg) throws Exception {
        handler.onMessage(ctx, msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("[" + role + "] error: " + cause.getMessage());
        cause.printStackTrace(System.err);
        ctx.close();
    }
}
