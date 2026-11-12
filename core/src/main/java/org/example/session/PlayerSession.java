package org.example.session;

import io.netty.channel.Channel;
import org.example.model.PlayerData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Logged-in player binding. Multi-process path uses {@link #connId} + gateway routing.
 */
public final class PlayerSession {
    private static final int RELIABLE_CAP = 64;

    private final String username;
    private volatile String connId;
    private volatile String gatewayId;
    private volatile Channel channel;
    private volatile PlayerData cachedData;
    private volatile String roomId;
    private volatile boolean inQueue;
    private volatile ConnState connState = ConnState.ONLINE;
    private volatile String sessionToken;
    private volatile String reconnectToken;
    private volatile long disconnectAtMs;
    private long outboundSeq;
    private final Deque<ReliableMsg> reliableRing = new ArrayDeque<>();

    public PlayerSession(String username, String connId, PlayerData data) {
        this.username = username;
        this.connId = connId;
        this.cachedData = data;
    }

    public String getUsername() {
        return username;
    }

    public String getConnId() {
        return connId;
    }

    public void setConnId(String connId) {
        this.connId = connId;
    }

    public String getGatewayId() {
        return gatewayId;
    }

    public void setGatewayId(String gatewayId) {
        this.gatewayId = gatewayId;
    }

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    public PlayerData getCachedData() {
        return cachedData;
    }

    public void setCachedData(PlayerData cachedData) {
        this.cachedData = cachedData;
    }

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public boolean isInQueue() {
        return inQueue;
    }

    public void setInQueue(boolean inQueue) {
        this.inQueue = inQueue;
    }

    /** Session may accept normal traffic only when ONLINE. */
    public boolean isActive() {
        return connState == ConnState.ONLINE;
    }

    /** Invalidate session (kick / abandon reconnect) — equivalent to {@link ConnState#GONE}. */
    public void markInactive() {
        this.connState = ConnState.GONE;
    }

    /** Mark reachable again — equivalent to {@link ConnState#ONLINE}. */
    public void markActive() {
        this.connState = ConnState.ONLINE;
    }

    public ConnState getConnState() {
        return connState;
    }

    public void setConnState(ConnState connState) {
        this.connState = connState;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public void setSessionToken(String sessionToken) {
        this.sessionToken = sessionToken;
    }

    public String getReconnectToken() {
        return reconnectToken;
    }

    public void setReconnectToken(String reconnectToken) {
        this.reconnectToken = reconnectToken;
    }

    public long getDisconnectAtMs() {
        return disconnectAtMs;
    }

    public void setDisconnectAtMs(long disconnectAtMs) {
        this.disconnectAtMs = disconnectAtMs;
    }

    public synchronized long nextOutboundSeq() {
        return ++outboundSeq;
    }

    public synchronized long currentOutboundSeq() {
        return outboundSeq;
    }

    public synchronized void rememberReliable(long seq, String json) {
        reliableRing.addLast(new ReliableMsg(seq, json));
        while (reliableRing.size() > RELIABLE_CAP) {
            reliableRing.removeFirst();
        }
    }

    public synchronized List<String> reliableSince(long lastAckSeq) {
        List<String> out = new ArrayList<>();
        for (ReliableMsg m : reliableRing) {
            if (m.seq > lastAckSeq) {
                out.add(m.json);
            }
        }
        return out;
    }

    public boolean isConn(String id) {
        return id != null && id.equals(connId);
    }

    public boolean isChannel(Channel ch) {
        Channel c = this.channel;
        return c != null && c == ch;
    }

    public record ReliableMsg(long seq, String json) {
    }
}
