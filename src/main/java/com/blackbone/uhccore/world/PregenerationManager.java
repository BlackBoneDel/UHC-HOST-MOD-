package com.blackbone.uhccore.world;

import com.blackbone.uhccore.db.Database;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.logging.Level;
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
    // A slow/frozen chunk request must not let the skipped outer-circle cells
    // fill an unbounded HashSet while the contiguous checkpoint is blocked.
    static final int MAX_PENDING_INDICES = 2048;
    private final Plugin plugin;
    private final Database database;
    private final WorldManager worlds;
    private Job job;
    private boolean restored;

    static final class Job {
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
        long sampleAt = System.currentTimeMillis();
        int sampleGenerated;
        double recentRate;
        State state;
        Job(String name, int radius, int checkpoint, int generated, State state, long startedAt) {
            if (name == null || name.isBlank() || radius < 8 || radius > 1250 || state == null) {
                throw new IllegalArgumentException("Invalid saved generation world or radius");
            }
            worldName = name; this.radius = radius; side = radius * 2 + 1;
            // Count the lattice points in O(radius), not O(area) on the server thread.
            int count = 0;
            for (int x = -radius; x <= radius; x++) {
                int maxZ = (int) Math.sqrt((long) radius * radius - (long) x * x);
                count += maxZ * 2 + 1;
            }
            total = count;
            if (checkpoint < 0 || checkpoint > side * side || generated < 0 || generated > count
                || generated > checkpoint || (state == State.COMPLETED
                    && (checkpoint != side * side || generated != count))) {
                throw new IllegalArgumentException("Invalid saved generation checkpoint");
            }
            cursor = checkpoint; this.checkpoint = checkpoint; committed = generated;
            this.generated = generated; this.state = state; this.startedAt = startedAt;
            sampleGenerated = generated;
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
        }).whenComplete((loaded, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!plugin.isEnabled()) return;
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Cannot restore generation checkpoint; disabling rather than overwriting it", error);
                Bukkit.getPluginManager().disablePlugin(plugin);
                return;
            }
            if (loaded != null) {
                if (!loaded.worldName.equals(worlds.ensureWorld().getName())) {
                    plugin.getLogger().warning("Saved pregeneration world differs from configured world; job paused");
                    loaded.state = State.PAUSED;
                }
                job = loaded;
                plugin.getLogger().info("Restored world generation: " + loaded.state + " (" + loaded.generated + "/" + loaded.total + ")");
            } else {
                plugin.getLogger().info("Generation checkpoint ready: no previous job");
            }
            restored = true;
        }));
    }

    public void start(int radiusBlocks) {
        if (!restored) throw new IllegalStateException("Waiting for generation checkpoint restoration");
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
        if (job.inflight != 0) throw new IllegalStateException("Wait for pending chunk requests before resuming");
        // A failed request leaves a hole in the contiguous checkpoint. Revisit
        // every uncommitted cell instead of reporting an incomplete world as done.
        job.cursor = job.checkpoint;
        job.done.clear();
        job.generated = job.committed;
        job.sampleGenerated = job.generated;
        job.sampleAt = System.currentTimeMillis();
        job.recentRate = 0;
        job.state = State.RUNNING; persist(job);
    }

    public void cancel() {
        if (job == null || job.state == State.COMPLETED || job.state == State.CANCELLED) throw new IllegalStateException("No generation to cancel");
        job.state = State.CANCELLED; persist(job);
    }

    public Progress progress() {
        if (job == null) return null;
        long now = System.currentTimeMillis();
        long window = now - job.sampleAt;
        if (window >= 1000) {
            job.recentRate = job.state == State.RUNNING
                ? (job.generated - job.sampleGenerated) * 1000.0 / window : 0;
            job.sampleGenerated = job.generated;
            job.sampleAt = now;
        }
        double rate = job.state == State.RUNNING ? job.recentRate : 0;
        long eta = rate <= 0 ? -1 : (long) Math.ceil((job.total - job.generated) / rate);
        return new Progress(job.state, job.generated, job.total, rate, eta);
    }

    static boolean hasCheckpointRoom(Job current) {
        return current.cursor - current.checkpoint < MAX_PENDING_INDICES;
    }

    public void tick() {
        Job current = job;
        if (current == null || current.state != State.RUNNING) return;
        World world = worlds.ensureWorld();
        int cells = current.side * current.side;
        int inspected = 0;
        int requested = 0;
        // A completed future can run synchronously (already generated chunk).
        // Bound both requests and skipped outer-circle cells per server tick.
        while (current.state == State.RUNNING && current.inflight < PARALLEL
            && current.cursor < cells && hasCheckpointRoom(current)
            && inspected++ < 512 && requested < PARALLEL) {
            int index = current.cursor++;
            int x = index / current.side - current.radius;
            int z = index % current.side - current.radius;
            if ((long) x * x + (long) z * z > (long) current.radius * current.radius) {
                mark(current, index);
                continue;
            }
            current.inflight++;
            requested++;
            try {
                world.getChunkAtAsync(x, z, true, false).whenComplete((chunk, error) -> {
                    Runnable completed = () -> {
                        current.inflight--;
                        if (chunk != null) world.unloadChunkRequest(x, z);
                        if (current.state == State.CANCELLED) return;
                        if (error != null || chunk == null) {
                            current.state = State.PAUSED;
                            plugin.getLogger().log(Level.SEVERE, "Chunk generation failed at " + x + "," + z
                                + "; checkpoint retained for safe retry", error);
                            persist(current);
                            return;
                        }
                        current.generated++;
                        mark(current, index);
                        if (current.generated % 64 == 0 || System.currentTimeMillis() - current.lastSaved >= 5000) persist(current);
                    };
                    if (Bukkit.isPrimaryThread()) completed.run();
                    else if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, completed);
                });
            } catch (RuntimeException error) {
                current.inflight--;
                current.state = State.PAUSED;
                plugin.getLogger().log(Level.SEVERE, "Cannot request generation chunk " + x + "," + z, error);
                persist(current);
            }
        }
        if (current.cursor == cells && current.inflight == 0) {
            if (current.checkpoint != cells) {
                current.state = State.PAUSED;
                plugin.getLogger().severe("Generation checkpoint has an unfilled chunk; paused for retry");
            } else current.state = State.COMPLETED;
            persist(current);
        }
    }

    static void mark(Job current, int index) {
        current.done.add(index);
        while (current.done.remove(current.checkpoint)) {
            int x = current.checkpoint / current.side - current.radius;
            int z = current.checkpoint % current.side - current.radius;
            if ((long) x * x + (long) z * z <= (long) current.radius * current.radius) current.committed++;
            current.checkpoint++;
        }
    }

    public boolean ready() { return restored; }

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
