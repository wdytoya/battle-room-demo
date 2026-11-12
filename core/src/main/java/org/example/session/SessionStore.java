package org.example.session;

/**
 * Boundary for connection↔player binding.
 */
public interface SessionStore {

    PlayerSession getByUsername(String username);

    PlayerSession getByConnId(String connId);

    PlayerSession bind(String username, String connId, String gatewayId, PlayerSession existingOrNew);

    void unbindConn(String connId);

    void send(PlayerSession session, String json);

    void removeUsername(String username);
}
