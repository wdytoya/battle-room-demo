package org.example.match;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.example.config.ClusterConfig;
import org.example.internal.InternalMessage;
import org.example.internal.InternalOps;
import org.example.internal.InternalRpcClient;
import org.example.msg.Messages;
import org.example.session.PlayerSession;
import org.example.session.SessionStore;
import org.example.spi.ServiceRoles;

import java.util.function.BiConsumer;

/**
 * Logic-side client to the Match process. Duplex: enqueue/cancel requests; result/status pushes.
 */
public final class RemoteMatchClient implements AutoCloseable {

    private final InternalRpcClient client;
    private final SessionStore sessions;
    private final BiConsumer<String, String> onMatched;

    public RemoteMatchClient(SessionStore sessions, BiConsumer<String, String> onMatched) throws Exception {
        this.sessions = sessions;
        this.onMatched = onMatched;
        this.client = new InternalRpcClient("logic->" + ServiceRoles.MATCH,
                ClusterConfig.MATCH_HOST, ClusterConfig.MATCH_RPC_PORT);
        this.client.setPushHandler((ctx, msg) -> onPush(msg));
        this.client.connect();
        JsonObject hello = new JsonObject();
        hello.addProperty("from", ServiceRoles.LOGIC);
        client.request(InternalOps.SYS_HELLO, hello, 5000);
        System.out.println("[logic] connected to match "
                + ClusterConfig.MATCH_HOST + ":" + ClusterConfig.MATCH_RPC_PORT);
    }

    private void onPush(InternalMessage msg) {
        switch (msg.getOp()) {
            case InternalOps.MATCH_RESULT -> {
                String left = msg.getPayload().get("left").getAsString();
                String right = msg.getPayload().get("right").getAsString();
                onMatched.accept(left, right);
            }
            case InternalOps.MATCH_QUEUE_STATUS -> {
                JsonArray arr = msg.getPayload().getAsJsonArray("entries");
                for (JsonElement el : arr) {
                    JsonObject row = el.getAsJsonObject();
                    String username = row.get("username").getAsString();
                    PlayerSession s = sessions.getByUsername(username);
                    if (s == null || s.getConnId() == null) {
                        continue;
                    }
                    long waitMs = row.get("waitMs").getAsLong();
                    int range = row.get("range").getAsInt();
                    sessions.send(s, Messages.matchStatus("queued", waitMs, range, null));
                }
            }
            default -> {
            }
        }
    }

    public long enqueue(String username, int elo) throws Exception {
        JsonObject p = new JsonObject();
        p.addProperty("username", username);
        p.addProperty("elo", elo);
        InternalMessage resp = client.request(InternalOps.MATCH_ENQUEUE, p, 3000);
        return resp.getPayload().get("seq").getAsLong();
    }

    public boolean cancel(String username) throws Exception {
        JsonObject p = new JsonObject();
        p.addProperty("username", username);
        InternalMessage resp = client.request(InternalOps.MATCH_CANCEL, p, 3000);
        return resp.getPayload().get("removed").getAsBoolean();
    }

    public ContainsResult contains(String username) throws Exception {
        JsonObject p = new JsonObject();
        p.addProperty("username", username);
        InternalMessage resp = client.request(InternalOps.MATCH_CONTAINS, p, 3000);
        JsonObject body = resp.getPayload();
        boolean c = body.get("contains").getAsBoolean();
        long wait = body.has("waitMs") ? body.get("waitMs").getAsLong() : 0L;
        return new ContainsResult(c, wait);
    }

    public record ContainsResult(boolean contains, long waitMs) {
    }

    @Override
    public void close() {
        client.close();
    }
}
