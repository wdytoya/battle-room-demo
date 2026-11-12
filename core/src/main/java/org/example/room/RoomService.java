package org.example.room;

/**
 * Boundary for room lifecycle + tick. Logic process will own this later.
 */
public interface RoomService extends Runnable {

    BattleRoom get(String roomId);

    BattleRoom getByUser(String username);

    BattleRoom create(String userA, String userB);

    void sendCurrentTo(String username);
}
