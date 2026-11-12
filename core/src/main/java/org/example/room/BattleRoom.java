package org.example.room;

import org.example.config.ServerConfig;
import org.example.elo.EloCalculator;
import org.example.model.Hand;
import org.example.model.PlayerData;
import org.example.model.RoomPhase;
import org.example.model.Thrust;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 1D arena: two balls, thrust physics, collision rebound + RPS light damage.
 */
public final class BattleRoom implements PhysicsTick {

    public enum Outcome {
        WIN, LOSS, DRAW
    }

    public enum EndReason {
        LIGHTS, TIME, NONE
    }

    private final String roomId;
    private final String playerA;
    private final String playerB;

    private volatile RoomPhase phase = RoomPhase.MATCHED;
    private volatile long playingStartMs;
    private final AtomicLong tick = new AtomicLong(0);

    private volatile Hand handA;
    private volatile Hand handB;
    private volatile Thrust thrustA = Thrust.NONE;
    private volatile Thrust thrustB = Thrust.NONE;

    private double xA = ServerConfig.START_X_A;
    private double xB = ServerConfig.START_X_B;
    private double vA;
    private double vB;
    private int lightsA = ServerConfig.MAX_LIGHTS;
    private int lightsB = ServerConfig.MAX_LIGHTS;

    private long lastCollisionMs;
    private volatile CollisionEvent lastCollision;
    private volatile EndReason endReason = EndReason.NONE;

    private volatile SettleResult settleResult;

    public BattleRoom(String playerA, String playerB) {
        this(UUID.randomUUID().toString().replace("-", "").substring(0, 12), playerA, playerB);
    }

    public BattleRoom(String roomId, String playerA, String playerB) {
        this.roomId = roomId;
        this.playerA = playerA;
        this.playerB = playerB;
    }

    public String getRoomId() {
        return roomId;
    }

    public String getPlayerA() {
        return playerA;
    }

    public String getPlayerB() {
        return playerB;
    }

    public boolean contains(String username) {
        return playerA.equals(username) || playerB.equals(username);
    }

    public RoomPhase getPhase() {
        return phase;
    }

    public long getTick() {
        return tick.get();
    }

    public Hand getHand(String username) {
        if (playerA.equals(username)) {
            return handA;
        }
        if (playerB.equals(username)) {
            return handB;
        }
        return null;
    }

    public Thrust getThrust(String username) {
        if (playerA.equals(username)) {
            return thrustA;
        }
        if (playerB.equals(username)) {
            return thrustB;
        }
        return Thrust.NONE;
    }

    public double getX(String username) {
        return playerA.equals(username) ? xA : xB;
    }

    public double getVx(String username) {
        return playerA.equals(username) ? vA : vB;
    }

    public int getLights(String username) {
        return playerA.equals(username) ? lightsA : lightsB;
    }

    public int getLightsA() {
        return lightsA;
    }

    public int getLightsB() {
        return lightsB;
    }

    public double getXA() {
        return xA;
    }

    public double getXB() {
        return xB;
    }

    public double getVA() {
        return vA;
    }

    public double getVB() {
        return vB;
    }

    public CollisionEvent getLastCollision() {
        return lastCollision;
    }

    public EndReason getEndReason() {
        return endReason;
    }

    public SettleResult getSettleResult() {
        return settleResult;
    }

    public void startPlaying() {
        startPlaying(System.currentTimeMillis());
    }

    public void startPlaying(long playingStartMs) {
        this.phase = RoomPhase.PLAYING;
        this.playingStartMs = playingStartMs;
        this.tick.set(0);
        this.xA = ServerConfig.START_X_A;
        this.xB = ServerConfig.START_X_B;
        this.vA = 0;
        this.vB = 0;
        this.lightsA = ServerConfig.MAX_LIGHTS;
        this.lightsB = ServerConfig.MAX_LIGHTS;
        this.thrustA = Thrust.NONE;
        this.thrustB = Thrust.NONE;
        this.handA = null;
        this.handB = null;
        this.lastCollision = null;
        this.lastCollisionMs = 0;
        this.endReason = EndReason.NONE;
        this.settleResult = null;
    }

    public long getPlayingStartMs() {
        return playingStartMs;
    }

    /**
     * Apply authoritative physics tick from the Physics process (Logic-side mirror).
     */
    public synchronized void applyPhysicsTick(
            long tickValue,
            double xA, double vA, double xB, double vB,
            int lightsA, int lightsB,
            Hand handA, Hand handB,
            Thrust thrustA, Thrust thrustB,
            CollisionEvent collision,
            EndReason endReason) {
        this.tick.set(tickValue);
        this.xA = xA;
        this.vA = vA;
        this.xB = xB;
        this.vB = vB;
        this.lightsA = lightsA;
        this.lightsB = lightsB;
        this.handA = handA;
        this.handB = handB;
        this.thrustA = thrustA == null ? Thrust.NONE : thrustA;
        this.thrustB = thrustB == null ? Thrust.NONE : thrustB;
        if (collision != null) {
            this.lastCollision = collision;
        }
        if (endReason != null && endReason != EndReason.NONE) {
            this.endReason = endReason;
        }
    }

    public long remainingMs(long now) {
        if (phase != RoomPhase.PLAYING) {
            if (phase == RoomPhase.MATCHED) {
                return ServerConfig.PLAYING_DURATION_MS;
            }
            return 0L;
        }
        long elapsed = now - playingStartMs;
        return Math.max(0L, ServerConfig.PLAYING_DURATION_MS - elapsed);
    }

    public long nextTick() {
        return tick.incrementAndGet();
    }

    public String inputHand(String username, Hand hand) {
        if (phase != RoomPhase.PLAYING) {
            return "ROOM_NOT_PLAYING";
        }
        if (hand == null) {
            return "INVALID_HAND";
        }
        if (playerA.equals(username)) {
            handA = hand;
            return null;
        }
        if (playerB.equals(username)) {
            handB = hand;
            return null;
        }
        return "NOT_IN_ROOM";
    }

    public String inputThrust(String username, Thrust thrust) {
        if (phase != RoomPhase.PLAYING) {
            return "ROOM_NOT_PLAYING";
        }
        if (thrust == null) {
            return "INVALID_THRUST";
        }
        if (playerA.equals(username)) {
            thrustA = thrust;
            return null;
        }
        if (playerB.equals(username)) {
            thrustB = thrust;
            return null;
        }
        return "NOT_IN_ROOM";
    }

    /**
     * Advance physics one tick. Call only while PLAYING.
     *
     * @return true if match should settle now (lights or time)
     */
    public synchronized boolean simulateTick(long now) {
        if (phase != RoomPhase.PLAYING) {
            return false;
        }
        double dt = ServerConfig.tickDtSeconds();
        applyThrustAndIntegrate(true, dt);
        applyThrustAndIntegrate(false, dt);
        resolveWalls();
        maybeCollide(now);
        nextTick();
        if (lightsA <= 0 || lightsB <= 0) {
            endReason = EndReason.LIGHTS;
            return true;
        }
        if (remainingMs(now) <= 0) {
            endReason = EndReason.TIME;
            return true;
        }
        return false;
    }

    private void applyThrustAndIntegrate(boolean sideA, double dt) {
        Thrust thrust = sideA ? thrustA : thrustB;
        double v = sideA ? vA : vB;
        // Absolute arena axes: LEFT = -X, RIGHT = +X (independent of spawn side)
        double ax = 0;
        if (thrust == Thrust.RIGHT) {
            ax = ServerConfig.ACCEL;
        } else if (thrust == Thrust.LEFT) {
            ax = -ServerConfig.ACCEL;
        } else {
            if (v > 0) {
                ax = -ServerConfig.FRICTION;
            } else if (v < 0) {
                ax = ServerConfig.FRICTION;
            }
        }
        v += ax * dt;
        if (thrust == Thrust.NONE) {
            // prevent friction overshoot
            double prev = sideA ? vA : vB;
            if (prev > 0 && v < 0) {
                v = 0;
            }
            if (prev < 0 && v > 0) {
                v = 0;
            }
        }
        if (v > ServerConfig.MAX_SPEED) {
            v = ServerConfig.MAX_SPEED;
        }
        if (v < -ServerConfig.MAX_SPEED) {
            v = -ServerConfig.MAX_SPEED;
        }
        double x = (sideA ? xA : xB) + v * dt;
        if (sideA) {
            vA = v;
            xA = x;
        } else {
            vB = v;
            xB = x;
        }
    }

    private void resolveWalls() {
        double min = ServerConfig.BALL_RADIUS;
        double max = ServerConfig.ARENA_WIDTH - ServerConfig.BALL_RADIUS;
        if (xA < min) {
            xA = min;
            vA = Math.abs(vA) * ServerConfig.WALL_RESTITUTION;
        } else if (xA > max) {
            xA = max;
            vA = -Math.abs(vA) * ServerConfig.WALL_RESTITUTION;
        }
        if (xB < min) {
            xB = min;
            vB = Math.abs(vB) * ServerConfig.WALL_RESTITUTION;
        } else if (xB > max) {
            xB = max;
            vB = -Math.abs(vB) * ServerConfig.WALL_RESTITUTION;
        }
    }

    private void maybeCollide(long now) {
        double minDist = ServerConfig.BALL_RADIUS * 2;
        double dx = xB - xA;
        if (Math.abs(dx) >= minDist) {
            return;
        }
        if (now - lastCollisionMs < ServerConfig.COLLISION_COOLDOWN_MS) {
            separateOverlapping();
            return;
        }

        lastCollisionMs = now;
        double uA = vA;
        double uB = vB;
        double e = ServerConfig.COLLISION_RESTITUTION;
        // Equal-mass elastic exchange, then ensure separation direction
        vA = uB * e;
        vB = uA * e;
        if (xA <= xB) {
            if (vA > -40) {
                vA = -Math.max(40, Math.abs(uA) * e + 30);
            }
            if (vB < 40) {
                vB = Math.max(40, Math.abs(uB) * e + 30);
            }
        } else {
            if (vB > -40) {
                vB = -Math.max(40, Math.abs(uB) * e + 30);
            }
            if (vA < 40) {
                vA = Math.max(40, Math.abs(uA) * e + 30);
            }
        }
        separateOverlapping();

        int rps = resolveCollisionRps(handA, handB);
        String loser = null;
        String winner = null;
        if (rps > 0) {
            lightsB = Math.max(0, lightsB - 1);
            loser = playerB;
            winner = playerA;
        } else if (rps < 0) {
            lightsA = Math.max(0, lightsA - 1);
            loser = playerA;
            winner = playerB;
        }
        lastCollision = new CollisionEvent(
                tick.get() + 1,
                now,
                handA == null ? "NONE" : handA.name(),
                handB == null ? "NONE" : handB.name(),
                rps,
                winner,
                loser,
                lightsA,
                lightsB);
    }

    private void separateOverlapping() {
        double minDist = ServerConfig.BALL_RADIUS * 2;
        double dx = xB - xA;
        double overlap = minDist - Math.abs(dx);
        if (overlap <= 0) {
            return;
        }
        double push = overlap / 2.0 + 0.5;
        if (dx >= 0) {
            xA -= push;
            xB += push;
        } else {
            xA += push;
            xB -= push;
        }
        resolveWalls();
    }

    /**
     * @return &gt;0 A wins hit, &lt;0 B wins hit, 0 draw / no damage
     */
    static int resolveCollisionRps(Hand a, Hand b) {
        if (a == null && b == null) {
            return 0;
        }
        if (ServerConfig.NO_INPUT_LOSES) {
            if (a == null) {
                return -1;
            }
            if (b == null) {
                return 1;
            }
        } else {
            if (a == null || b == null) {
                return 0;
            }
        }
        return a.compareToHand(b);
    }

    /**
     * Final match outcome from lights / time. Caller persists ELO.
     */
    public synchronized SettleResult settle(PlayerData dataA, PlayerData dataB) {
        if (phase == RoomPhase.RESULT || phase == RoomPhase.CLOSED) {
            return settleResult;
        }
        if (endReason == EndReason.NONE) {
            if (lightsA <= 0 || lightsB <= 0) {
                endReason = EndReason.LIGHTS;
            } else {
                endReason = EndReason.TIME;
            }
        }

        // Lights decide the match; score is only for EloCalculator (1 / 0 / 0.5).
        Outcome outcomeA;
        Outcome outcomeB;
        double scoreA;
        if (lightsA <= 0 && lightsB > 0) {
            outcomeA = Outcome.LOSS;
            outcomeB = Outcome.WIN;
            scoreA = 0.0;
        } else if (lightsB <= 0 && lightsA > 0) {
            outcomeA = Outcome.WIN;
            outcomeB = Outcome.LOSS;
            scoreA = 1.0;
        } else if (lightsA > lightsB) {
            outcomeA = Outcome.WIN;
            outcomeB = Outcome.LOSS;
            scoreA = 1.0;
        } else if (lightsA < lightsB) {
            outcomeA = Outcome.LOSS;
            outcomeB = Outcome.WIN;
            scoreA = 0.0;
        } else {
            outcomeA = Outcome.DRAW;
            outcomeB = Outcome.DRAW;
            scoreA = 0.5;
        }
        double scoreB = 1.0 - scoreA;

        int gamesABefore = dataA.getGamesPlayed();
        int gamesBBefore = dataB.getGamesPlayed();
        int eloABefore = dataA.getElo();
        int eloBBefore = dataB.getElo();

        EloCalculator.UpdateResult updA = EloCalculator.update(eloABefore, eloBBefore, gamesABefore, scoreA);
        EloCalculator.UpdateResult updB = EloCalculator.update(eloBBefore, eloABefore, gamesBBefore, scoreB);

        dataA.setElo(updA.newElo());
        dataA.setGamesPlayed(gamesABefore + 1);
        dataB.setElo(updB.newElo());
        dataB.setGamesPlayed(gamesBBefore + 1);

        settleResult = new SettleResult(
                handA, handB,
                outcomeA, outcomeB,
                eloABefore, eloBBefore,
                updA, updB,
                gamesABefore, gamesBBefore,
                dataA.getGamesPlayed(), dataB.getGamesPlayed(),
                dataA.isPlacementComplete(), dataB.isPlacementComplete(),
                lightsA, lightsB, endReason);

        phase = RoomPhase.RESULT;
        return settleResult;
    }

    public void close() {
        phase = RoomPhase.CLOSED;
    }

    public record CollisionEvent(
            long tick,
            long atMs,
            String handA,
            String handB,
            int rps,
            String winner,
            String loser,
            int lightsA,
            int lightsB
    ) {
    }

    public record SettleResult(
            Hand handA,
            Hand handB,
            Outcome outcomeA,
            Outcome outcomeB,
            int eloABefore,
            int eloBBefore,
            EloCalculator.UpdateResult updA,
            EloCalculator.UpdateResult updB,
            int gamesABefore,
            int gamesBBefore,
            int gamesAAfter,
            int gamesBAfter,
            boolean rankedA,
            boolean rankedB,
            int lightsA,
            int lightsB,
            EndReason endReason
    ) {
    }
}
