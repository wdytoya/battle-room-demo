package org.example.internal;

import com.google.gson.JsonObject;
import io.netty.channel.ChannelHandlerContext;

/**
 * Default sys.ping → sys.pong. Compose with business handlers.
 */
public final class SysPingHandler implements InternalHandler {

    private final String serviceName;
    private final InternalHandler next;

    public SysPingHandler(String serviceName, InternalHandler next) {
        this.serviceName = serviceName;
        this.next = next;
    }

    public SysPingHandler(String serviceName) {
        this(serviceName, null);
    }

    @Override
    public void onMessage(ChannelHandlerContext ctx, InternalMessage msg) throws Exception {
        if (InternalOps.SYS_PING.equals(msg.getOp())) {
            JsonObject payload = new JsonObject();
            payload.addProperty("service", serviceName);
            payload.addProperty("echoAtMs", System.currentTimeMillis());
            if (msg.getPayload().has("t")) {
                payload.add("t", msg.getPayload().get("t"));
            }
            ctx.writeAndFlush(msg.reply(InternalOps.SYS_PONG, payload));
            return;
        }
        if (next != null) {
            next.onMessage(ctx, msg);
        }
    }
}
