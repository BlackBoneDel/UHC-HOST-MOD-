package com.blackbone.uhccore.domain;

/** One state machine per server; explicit transitions reject concurrent matches. */
public final class MatchClock {
    public enum State { IDLE, PREGAME, RUNNING, FINISHED }
    private State state = State.IDLE;
    private long pregameStartedAt;
    private long matchStartedAt;
    private int pregameSeconds;

    public State state() { return state; }

    public boolean begin(int seconds, long nowMillis) {
        if (state != State.IDLE || seconds < 0) return false;
        pregameSeconds = seconds;
        pregameStartedAt = nowMillis;
        state = State.PREGAME;
        return true;
    }

    public boolean ready(long nowMillis) {
        return state == State.PREGAME && nowMillis - pregameStartedAt >= pregameSeconds * 1000L;
    }

    public int remaining(long nowMillis) {
        if (state != State.PREGAME) return 0;
        return (int) Math.max(0, (pregameSeconds * 1000L - (nowMillis - pregameStartedAt) + 999) / 1000);
    }

    public void start(long nowMillis) {
        if (state != State.PREGAME || !ready(nowMillis)) throw new IllegalStateException("Pregame not ready");
        matchStartedAt = nowMillis;
        state = State.RUNNING;
    }

    public long elapsed(long nowMillis) {
        return state == State.RUNNING || state == State.FINISHED
            ? Math.max(0L, (nowMillis - matchStartedAt) / 1000L) : 0L;
    }

    public void finish() {
        if (state != State.RUNNING) throw new IllegalStateException("Match is not running");
        state = State.FINISHED;
    }

    public void reset() {
        state = State.IDLE;
        pregameStartedAt = 0;
        matchStartedAt = 0;
        pregameSeconds = 0;
    }
}
