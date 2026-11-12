package org.example.model;

public enum Hand {
    ROCK,
    PAPER,
    SCISSORS;

    public static Hand parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Hand.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Returns 1 if this beats other, 0 if draw, -1 if loses. Assumes both non-null. */
    public int compareToHand(Hand other) {
        if (this == other) {
            return 0;
        }
        if ((this == ROCK && other == SCISSORS)
                || (this == PAPER && other == ROCK)
                || (this == SCISSORS && other == PAPER)) {
            return 1;
        }
        return -1;
    }
}
