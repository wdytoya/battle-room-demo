package org.example.session;

import io.netty.channel.Channel;
import org.example.model.PlayerData;

/**
 * Live connection binding for a logged-in username.
 */
public final class PlayerSession {
    private final String username;
    private volatile Channel channel;
    private volatile PlayerData cachedData;
    private volatile String roomId;
    private volatile boolean inQueue;
    private volatile boolean active = true;

    public PlayerSession(String username, Channel channel, PlayerData data) {
        this.username = username;
        this.channel = channel;
        this.cachedData = data;
    }

    public String getUsername() {
        return username;
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

    public boolean isActive() {
        return active;
    }

    public void markInactive() {
        this.active = false;
    }

    public void markActive() {
        this.active = true;
    }

    public boolean isChannel(Channel ch) {
        Channel c = this.channel;
        return c != null && c == ch;
    }
}
