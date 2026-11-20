package com.blackbone.uhccore.domain;

public record BorderPhase(int startSeconds, int durationSeconds, int targetRadius) {
    public BorderPhase {
        if (startSeconds < 0 || durationSeconds < 1 || targetRadius < 5) {
            throw new IllegalArgumentException("Invalid border phase");
        }
    }

    public long endSeconds() {
        return (long) startSeconds + durationSeconds;
    }
}
