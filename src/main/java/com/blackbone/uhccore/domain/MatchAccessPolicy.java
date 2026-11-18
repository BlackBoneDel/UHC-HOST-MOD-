package com.blackbone.uhccore.domain;

/** Competitive arena boundary: only living participants can affect the match world. */
public final class MatchAccessPolicy {
    private MatchAccessPolicy() { }

    public static boolean active(MatchClock.State state) {
        return state == MatchClock.State.PREGAME || state == MatchClock.State.RUNNING;
    }

    public static boolean canAct(MatchClock.State state, boolean participant, boolean inArena) {
        if (!active(state)) return true;
        // Participants cannot gather materials in a lobby, nor gain a head start
        // during scatter and the countdown. Outsiders may use their own worlds.
        return participant ? state == MatchClock.State.RUNNING && inArena : !inArena;
    }

    public static boolean mayTeleport(MatchClock.State state, boolean participant, boolean destinationInArena) {
        return !active(state) || !participant || destinationInArena;
    }
}
