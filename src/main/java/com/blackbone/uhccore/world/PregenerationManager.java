package com.blackbone.uhccore.world;

import com.blackbone.uhccore.db.Database;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/** Bounded async chunk pipeline with a contiguous, crash-safe DB checkpoint. */
public final class PregenerationManager {
    public enum State { RUNNING, PAUSED, CANCELLED, COMPLETED }
    public record Progress(State state, int generated, int total, double perSecond, long etaSeconds) {
        public float fraction() { return total == 0 ? 0 : (float) generated / total; }
    }

    private static final int PARALLEL = 4;
    private final Plugin plugin;
    private final Database database;
    private final WorldManager worlds;
    private Job job;

    private static final class Job {
        final String worldName;
        final int radius;
        final int side;
        final int total;
        final long startedAt;
        final Set<Integer> done = new HashSet<>();
        int cursor;
        int checkpoint;
        int committed;
        int generated;
        int inflight;
        long lastSaved;
        State state;
        Job(String name, int radius, int checkpoint, int generated, State state, long startedAt) {
            worldName = name; this.radius = radius; side = radius * 2 + 1;
            int count = 0;
            for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
                if ((long) x * x + (long) z * z <= (long) radius * radius) count++;
            }
            total = count;
            cursor = checkpoint; this.checkpoint = checkpoint; committed = generated;
            this.generated = generated; this.state = state; this.startedAt = startedAt;
        }
    }

    public PregenerationManager(Plugin plugin, Database database, WorldManager worlds) {
        this.plugin = plugin; this.database = database; this.worlds = worlds;
    }

    public void restore() {
        database.async(connection -> {
            try (PreparedStatement stmt = connection.prepareStatement("SELECT * FROM generation WHERE id=1");
                 ResultSet row = stmt.executeQuery()) {
                if (!row.next()) return null;
                return new Job(row.getString("world_name"), row.getInt("radius"), row.getInt("next_index"),
                    row.getInt("generated"), State.valueOf(row.getString("state")), row.getLong("started_ms"));
            }
        }).thenAccept(restored -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!plugin.isEnabled() || restored == null || job != null) return;
            if (!restored.worldName.equals(worlds.ensureWorld().getName())) {
                plugin.getLogger().warning("Saved pregeneration world differs from configured world; job paused");
                restored.state = State.PAUSED;
            }
            job = restored;
            plugin.getLogger().info("Restored world generation: " + restored.state + " (" + restored.generated + "/" + restored.total + ")");
        }));
    }

    public void start(int radiusBlocks) {
        if (job != null && (job.state == State.RUNNING || job.state == State.PAUSED)) {
            throw new IllegalStateException("Generation already active; resume or cancel it");
        }
        if (radiusBlocks < 128 || radiusBlocks > 20000) throw new IllegalArgumentException("Generation radius out of range");
        World world = worlds.ensureWorld();
        job = new Job(world.getName(), (radiusBlocks + 15) / 16, 0, 0, State.RUNNING, System.currentTimeMillis());
        persist(job);
    }

    public void pause() {
        if (job == null || job.state != State.RUNNING) throw new IllegalStateException("Generation not running");
        job.state = State.PAUSED; persist(job);
    }

    public void resume() {
        if (job == null || job.state != State.PAUSED) throw new IllegalStateException("Generation not paused");
        if (!worlds.ensureWorld().getName().equals(job.worldName)) throw new IllegalStateException("Wrong world");
        job.state = State.RUNNING; persist(job);
    }

    public void cancel() {
        if (job == null || job.state == State.COMPLETED || job.state == State.CANCELLED) throw new IllegalStateException("No generation to cancel");
        job.state = State.CANCELLED; persist(job);
    }

    public Progress progress() {
        if (job == null) return null;
        long elapsed = Math.max(1, (System.currentTimeMillis() - job.startedAt) / 1000);
        double rate = job.generated / (double) elapsed;
        long eta = rate <= 0 ? -1 : (long) Math.ceil((job.total - job.generated) / rate);
        return new Progress(job.state, job.generated, job.total, rate, eta);
    }

    public void tick() {
        Job current = job;
        if (current == null || current.state != State.RUNNING) return;
        World world = worlds.ensureWorld();
        int cells = current.side * current.side;
        while (current.inflight < PARALLEL && current.cursor < cells) {
            int index = current.cursor++;
            int x = index / current.side - current.radius;
            int z = index % current.side - current.radius;
            if ((long) x * x + (long) z * z > (long) current.radius * current.radius) {
                mark(current, index, false);
                continue;
            }
            current.inflight++;
            world.getChunkAtAsync(x, z, true, false, chunk -> {
                Runnable completed = () -> {
                    current.inflight--;
                    if (current.state == State.CANCELLED) return;
                    current.generated++;
                    mark(current, index, true);
                    world.unloadChunkRequest(x, z);
                    if (current.generated % 64 == 0 || System.currentTimeMillis() - current.lastSaved >= 5000) persist(current);
                };
                if (Bukkit.isPrimaryThread()) completed.run();
                else Bukkit.getScheduler().runTask(plugin, completed);
            });
        }
        if (current.cursor == cells && current.inflight == 0) {
            current.state = State.COMPLETED;
            persist(current);
        }
    }

    private void mark(Job current, int index, boolean inside) {
        current.done.add(index);
        while (current.done.remove(current.checkpoint)) {
            int x = current.checkpoint / current.side - current.radius;
            int z = current.checkpoint % current.side - current.radius;
            if ((long) x * x + (long) z * z <= (long) current.radius * current.radius) current.committed++;
            current.checkpoint++;
        }
    }

    public void flush() { if (job != null) persist(job); }

    private void persist(Job current) {
        current.lastSaved = System.currentTimeMillis();
        int checkpoint = current.checkpoint;
        int completed = current.committed;
        State state = current.state;
        database.async(connection -> {
            String sql = database.driver().equals("mysql")
                ? "INSERT INTO generation(id,world_name,radius,next_index,generated,state,started_ms) VALUES(1,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE world_name=VALUES(world_name),radius=VALUES(radius),next_index=VALUES(next_index),generated=VALUES(generated),state=VALUES(state),started_ms=VALUES(started_ms)"
                : "INSERT INTO generation(id,world_name,radius,next_index,generated,state,started_ms) VALUES(1,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET world_name=excluded.world_name,radius=excluded.radius,next_index=excluded.next_index,generated=excluded.generated,state=excluded.state,started_ms=excluded.started_ms";
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, current.worldName); stmt.setInt(2, current.radius);
                stmt.setInt(3, checkpoint); stmt.setInt(4, completed);
                stmt.setString(5, state.name()); stmt.setLong(6, current.startedAt);
                stmt.executeUpdate();
            }
            return null;
        });
    }
}
