package org.example.session;

/**
 * Delivers JSON to a client. Monolith writes Netty Channel; Logic pushes via Gateway RPC.
 */
public interface ClientTransport {

    void deliver(PlayerSession session, String json);

    /**
     * Deliver to an explicit gateway/conn route (e.g. kick old conn during rebind
     * without mutating the rebound session's routing fields).
     */
    default void deliverTo(String gatewayId, String connId, String json) {
        // Optional; Gateway/monolith transports override.
    }
}
