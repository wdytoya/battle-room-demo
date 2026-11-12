package org.example.room;

/**
 * Boundary for one authoritative physics step. Physics process will own this later;
 * {@link BattleRoom} remains the in-process implementation for the monolith.
 */
public interface PhysicsTick {

    /**
     * @return true if the match should settle now (lights or time)
     */
    boolean simulateTick(long now);
}
