package org.example.internal;

/**
 * Internal RPC op names (Gateway / Match / Logic / Physics).
 */
public final class InternalOps {

    public static final String SYS_PING = "sys.ping";
    public static final String SYS_PONG = "sys.pong";
    public static final String SYS_HELLO = "sys.hello";

    /** Gateway → Logic: client WebSocket text. */
    public static final String CLIENT_TEXT = "client.text";
    /** Gateway → Logic: client disconnected. */
    public static final String CLIENT_INACTIVE = "client.inactive";
    /** Logic → Gateway: push JSON text frame to a connId. */
    public static final String CLIENT_SEND = "client.send";
    /** Logic → Gateway: close a client connection (e.g. kicked). */
    public static final String CLIENT_CLOSE = "client.close";

    public static final String MATCH_ENQUEUE = "match.enqueue";
    public static final String MATCH_CANCEL = "match.cancel";
    public static final String MATCH_RESULT = "match.result";
    public static final String MATCH_QUEUE_STATUS = "match.queue_status";
    public static final String MATCH_CONTAINS = "match.contains";

    public static final String PHYS_ROOM_CREATE = "phys.room_create";
    public static final String PHYS_ROOM_DESTROY = "phys.room_destroy";
    public static final String PHYS_INPUT_HAND = "phys.input_hand";
    public static final String PHYS_INPUT_THRUST = "phys.input_thrust";
    public static final String PHYS_TICK_RESULT = "phys.tick_result";

    private InternalOps() {
    }
}
