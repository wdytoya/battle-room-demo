package org.example.service;

/**
 * Client-facing message entry after Gateway (or monolith) has identified the connection.
 */
public interface ClientMessageHandler {

    void handleText(String connId, String gatewayId, String text);

    void onConnInactive(String connId);

    default void onConnInactive(String connId, String reason) {
        onConnInactive(connId);
    }
}
