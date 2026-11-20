package com.blackbone.uhccore.domain;

public final class RatingMath {
    public static final int INITIAL_ELO = 1000;
    private RatingMath() { }

    public record Result(int winner, int loser) { }

    public static Result duel(int winner, int loser) {
        if (winner < 0 || loser < 0) throw new IllegalArgumentException("Negative rating");
        double expected = 1.0 / (1.0 + Math.pow(10, (loser - winner) / 400.0));
        int delta = (int) Math.round(32 * (1 - expected));
        return new Result(winner + delta, Math.max(0, loser - delta));
    }
}
