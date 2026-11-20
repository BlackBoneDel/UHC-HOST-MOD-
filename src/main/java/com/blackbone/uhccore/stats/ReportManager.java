package com.blackbone.uhccore.stats;

import com.blackbone.uhccore.db.Database;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Reports are a review queue, intentionally without push notifications. */
public final class ReportManager {
    private final Database database;
    public ReportManager(Database database) { this.database = database; }
    public record Report(long id, UUID reporter, UUID target, String reason, long createdAt) { }

    public CompletableFuture<Void> submit(UUID reporter, UUID target, String reason) {
        if (reporter.equals(target) || reason.length() < 5 || reason.length() > 255) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid report"));
        }
        return database.async(connection -> {
            try (PreparedStatement stmt = connection.prepareStatement("INSERT INTO reports(reporter,target,reason,created_ms) VALUES(?,?,?,?)")) {
                stmt.setString(1, reporter.toString()); stmt.setString(2, target.toString());
                stmt.setString(3, reason); stmt.setLong(4, System.currentTimeMillis());
                stmt.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<List<Report>> pending() {
        return database.async(connection -> {
            List<Report> reports = new ArrayList<>();
            try (PreparedStatement stmt = connection.prepareStatement("SELECT id,reporter,target,reason,created_ms FROM reports WHERE resolved_ms IS NULL ORDER BY created_ms LIMIT 45");
                 ResultSet rows = stmt.executeQuery()) {
                while (rows.next()) reports.add(new Report(rows.getLong("id"), UUID.fromString(rows.getString("reporter")),
                    UUID.fromString(rows.getString("target")), rows.getString("reason"), rows.getLong("created_ms")));
            }
            return reports;
        });
    }

    public CompletableFuture<Boolean> resolve(long id) {
        return database.async(connection -> {
            try (PreparedStatement stmt = connection.prepareStatement("UPDATE reports SET resolved_ms=? WHERE id=? AND resolved_ms IS NULL")) {
                stmt.setLong(1, System.currentTimeMillis()); stmt.setLong(2, id);
                return stmt.executeUpdate() == 1;
            }
        });
    }
}
