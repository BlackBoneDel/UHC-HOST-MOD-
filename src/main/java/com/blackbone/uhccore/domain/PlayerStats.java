package com.blackbone.uhccore.domain;

import java.util.UUID;

public record PlayerStats(UUID uuid, String name, int kills, int deaths, int assists,
                          int wins, int played, int elo) {
    public PlayerStats {
        if (uuid == null || name == null || kills < 0 || deaths < 0 || assists < 0
                || wins < 0 || played < 0 || elo < 0) throw new IllegalArgumentException("Invalid stats");
    }

    public static PlayerStats fresh(UUID uuid, String name) {
        return new PlayerStats(uuid, name, 0, 0, 0, 0, 0, RatingMath.INITIAL_ELO);
    }

    public PlayerStats renamed(String currentName) {
        return new PlayerStats(uuid, currentName, kills, deaths, assists, wins, played, elo);
    }

    public PlayerStats participation() {
        return new PlayerStats(uuid, name, kills, deaths, assists, wins, played + 1, elo);
    }

    public PlayerStats death(int newElo) {
        return new PlayerStats(uuid, name, kills, deaths + 1, assists, wins, played, newElo);
    }

    public PlayerStats kill(int newElo) {
        return new PlayerStats(uuid, name, kills + 1, deaths, assists, wins, played, newElo);
    }

    public PlayerStats assist() {
        return new PlayerStats(uuid, name, kills, deaths, assists + 1, wins, played, elo);
    }

    public PlayerStats victory() {
        return new PlayerStats(uuid, name, kills, deaths, assists, wins + 1, played, elo + 32);
    }
}
