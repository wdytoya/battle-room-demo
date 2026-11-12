package org.example.internal;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.nio.charset.StandardCharsets;

/**
 * Encodes {@link InternalMessage} as: 4-byte big-endian length + UTF-8 JSON body.
 */
public final class InternalMessageEncoder extends MessageToByteEncoder<InternalMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, InternalMessage msg, ByteBuf out) {
        byte[] body = InternalJson.toJson(msg).getBytes(StandardCharsets.UTF_8);
        out.writeInt(body.length);
        out.writeBytes(body);
    }
}
