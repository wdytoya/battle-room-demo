package org.example.internal;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Decodes a length-stripped JSON frame into {@link InternalMessage}.
 * {@link io.netty.handler.codec.LengthFieldBasedFrameDecoder} must run ahead.
 */
public final class InternalMessageDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        byte[] body = new byte[in.readableBytes()];
        in.readBytes(body);
        out.add(InternalJson.fromJson(new String(body, StandardCharsets.UTF_8)));
    }
}
