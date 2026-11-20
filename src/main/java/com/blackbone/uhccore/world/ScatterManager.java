package com.blackbone.uhccore.world;

import com.blackbone.uhccore.domain.ScatterRules;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Async chunk IO/generation; all Bukkit world and block reads stay on the main thread. */
public final class ScatterManager {
    private final Plugin plugin;
    public ScatterManager(Plugin plugin) { this.plugin = plugin; }

    public CompletableFuture<Void> scatter(Collection<Player> players, World world, int radius, int separation) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Deque<Player> queue = new ArrayDeque<>(players);
        assign(queue, new ArrayList<>(), world, radius, separation, 0, result);
        return result;
    }

    private void assign(Deque<Player> queue, List<ScatterRules.Spot> selected, World world, int radius,
                        int separation, int attempt, CompletableFuture<Void> result) {
        if (result.isDone()) return;
        if (queue.isEmpty()) { result.complete(null); return; }
        if (attempt >= 96) {
            result.completeExceptionally(new IllegalStateException("No safe scatter position for " + queue.peek().getName()));
            return;
        }
        int bound = Math.max(32, radius - 32);
        int x = ThreadLocalRandom.current().nextInt(-bound, bound + 1);
        int z = ThreadLocalRandom.current().nextInt(-bound, bound + 1);
        if ((long) x * x + (long) z * z >= (long) bound * bound) {
            retry(queue, selected, world, radius, separation, attempt + 1, result);
            return;
        }
        try {
            world.getChunkAtAsync(x >> 4, z >> 4, true, false).whenComplete((chunk, error) -> {
                if (!plugin.isEnabled()) return;
                Runnable validation = () -> {
                    if (result.isDone()) {
                        if (chunk != null) world.unloadChunkRequest(x >> 4, z >> 4);
                        return;
                    }
                    if (error != null || chunk == null) {
                        result.completeExceptionally(new IllegalStateException("Scatter chunk generation failed", error));
                        return;
                    }
                    try {
                        validate(queue, selected, world, radius, separation, attempt, result, x, z);
                    } catch (RuntimeException ex) {
                        result.completeExceptionally(ex);
                    }
                };
                if (Bukkit.isPrimaryThread()) validation.run();
                else Bukkit.getScheduler().runTask(plugin, validation);
            });
        } catch (RuntimeException ex) {
            result.completeExceptionally(ex);
        }
    }

    private void validate(Deque<Player> queue, List<ScatterRules.Spot> selected, World world, int radius,
                          int separation, int attempt, CompletableFuture<Void> result, int x, int z) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        Block floor = world.getBlockAt(x, y, z);
        Material type = floor.getType();
        boolean hazardous = type == Material.LAVA || type == Material.WATER || type == Material.CACTUS
            || type == Material.MAGMA_BLOCK || type == Material.POWDER_SNOW || type == Material.FIRE
            || type == Material.SOUL_FIRE || type == Material.CAMPFIRE
            || !world.getBlockAt(x, y + 1, z).isPassable()
            || !world.getBlockAt(x, y + 2, z).isPassable()
            || world.getBiome(x, y, z).getKey().getKey().contains("ocean");
        ScatterRules.Spot spot = new ScatterRules.Spot(x, y + 1, z);
        if (!ScatterRules.acceptable(spot, selected, separation, world.getMinHeight(), world.getMaxHeight(),
            type.isSolid(), hazardous)) {
            world.unloadChunkRequest(x >> 4, z >> 4);
            retry(queue, selected, world, radius, separation, attempt + 1, result);
            return;
        }
        Player player = queue.removeFirst();
        if (!player.isOnline()) {
            world.unloadChunkRequest(x >> 4, z >> 4);
            retry(queue, selected, world, radius, separation, 0, result);
            return;
        }
        Location origin = player.getLocation().clone();
        Location location = new Location(world, x + .5, y + 1, z + .5);
        player.teleportAsync(location).whenComplete((success, error) -> {
            if (!plugin.isEnabled()) return;
            Runnable completed = () -> {
                if (result.isDone()) {
                    // Cancellation can race a teleport already loading its destination.
                    if (Boolean.TRUE.equals(success) && player.isOnline()) player.teleportAsync(origin);
                    return;
                }
                if (!player.isOnline()) {
                    assign(queue, selected, world, radius, separation, 0, result);
                    return;
                }
                if (error != null || !Boolean.TRUE.equals(success)) {
                    result.completeExceptionally(new IllegalStateException("Scatter teleport failed", error));
                    return;
                }
                selected.add(spot);
                assign(queue, selected, world, radius, separation, 0, result);
            };
            if (Bukkit.isPrimaryThread()) completed.run();
            else Bukkit.getScheduler().runTask(plugin, completed);
        });
    }

    private void retry(Deque<Player> queue, List<ScatterRules.Spot> selected, World world, int radius,
                       int separation, int attempt, CompletableFuture<Void> result) {
        if (!result.isDone() && plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, () -> assign(queue, selected, world, radius, separation, attempt, result));
        }
    }
}
