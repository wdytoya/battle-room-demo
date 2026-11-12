package org.example.internal;

import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/**
 * Shared codec stack. Wire: {@code [int32 BE bodyLen][UTF-8 JSON]} (len excludes the 4-byte header).
 * Inbound framing via {@link LengthFieldBasedFrameDecoder}; outbound length written in {@link InternalMessageEncoder}.
 */
public final class InternalPipeline {

    private static final int MAX_FRAME = 1024 * 1024;

    public static void addCodec(ChannelPipeline p) {
        // strip=4 → next decoder sees JSON body only
        p.addLast("frameDecoder", new LengthFieldBasedFrameDecoder(MAX_FRAME, 0, 4, 0, 4));
        p.addLast("internalDecoder", new InternalMessageDecoder());
        p.addLast("internalEncoder", new InternalMessageEncoder());
    }

    private InternalPipeline() {
    }
}
