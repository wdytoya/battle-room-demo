package org.example.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class SessionManager implements SessionStore {

    private final Map<String, PlayerSession> byUsername = new ConcurrentHashMap<>();
    private final Map<String, String> connToUser = new ConcurrentHashMap<>();
    private final ClientTransport transport;
    /** Optional: after KICKED is delivered, close the old connection (Gateway / local). */
    private final Consumer<PlayerSession> onKickOld;

    public SessionManager(ClientTransport transport) {
        this(transport, null);
    }

    public SessionManager(ClientTransport transport, Consumer<PlayerSession> onKickOld) {
        this.transport = transport;
        this.onKickOld = onKickOld;
    }

    @Override
    public PlayerSession getByUsername(String username) {
        return byUsername.get(username);
    }

    @Override
    public PlayerSession getByConnId(String connId) {
        String u = connToUser.get(connId);
        return u == null ? null : byUsername.get(u);
    }

    public List<PlayerSession> allSessions() {
        return new ArrayList<>(byUsername.values());
    }

    @Override
    public PlayerSession bind(String username, String connId, String gatewayId, PlayerSession existingOrNew) {
        PlayerSession prev = byUsername.put(username, existingOrNew);
        if (prev != null && prev.getConnId() != null && !prev.getConnId().equals(connId)) {
            // Copy old routing; kick via deliverTo so we never markInactive the rebound session.
            final String oldConn = prev.getConnId();
            final String oldGw = prev.getGatewayId();
            connToUser.remove(oldConn, username);
            String kickJson = "{\"type\":\"error\",\"code\":\"KICKED\",\"message\":\"同一用户名在其他连接登录，本连接已失效\"}";
            transport.deliverTo(oldGw, oldConn, kickJson);
            if (onKickOld != null) {
                // Session still carries oldConn/oldGw for closeConn until we overwrite below.
                onKickOld.accept(prev);
            }
            if (prev != existingOrNew) {
                prev.markInactive();
                prev.setConnId(null);
            }
        }
        // Single place for "now online on this conn": routing + ConnState.ONLINE.
        existingOrNew.setConnId(connId);
        existingOrNew.setGatewayId(gatewayId);
        existingOrNew.markActive();
        connToUser.put(connId, username);
        return existingOrNew;
    }

    @Override
    public void unbindConn(String connId) {
        String username = connToUser.remove(connId);
        if (username == null) {
            return;
        }
        PlayerSession session = byUsername.get(username);
        if (session != null && session.isConn(connId)) {
            session.setConnId(null);
            session.setChannel(null);
        }
    }

    @Override
    public void send(PlayerSession session, String json) {
        // isActive() == ConnState.ONLINE; DISCONNECTED/GONE are rejected here.
        if (session == null || !session.isActive() || session.getConnId() == null) {
            return;
        }
        transport.deliver(session, json);
    }

    /** Deliver even when reconnecting / for kick frames on old conn. */
    public void deliverRaw(PlayerSession session, String json) {
        if (session == null) {
            return;
        }
        transport.deliver(session, json);
    }

    public void removeUsername(String username) {
        PlayerSession s = byUsername.remove(username);
        if (s != null && s.getConnId() != null) {
            connToUser.remove(s.getConnId(), username);
        }
    }
}
