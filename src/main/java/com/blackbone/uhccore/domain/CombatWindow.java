package com.blackbone.uhccore.domain;

public final class CombatWindow {
    public static final long NANOS = 10_000_000_000L;
    private CombatWindow() { }

    public static boolean isTagged(long lastHitNanos, long nowNanos) {
        long difference = nowNanos - lastHitNanos;
        return lastHitNanos != Long.MIN_VALUE && difference >= 0 && difference < NANOS;
    }

    public static boolean proxyExpired(long logoutNanos, long nowNanos) {
        return nowNanos - logoutNanos >= NANOS;
    }
}
