package org.example.model;

/**
 * Absolute horizontal thrust on the arena: LEFT / RIGHT / NONE.
 */
public enum Thrust {
    NONE,
    LEFT,
    RIGHT;

    public static Thrust parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        try {
            return Thrust.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
