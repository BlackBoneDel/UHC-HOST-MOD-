package com.blackbone.uhccore.world;

import com.blackbone.uhccore.config.Settings;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.WorldCreator;

/** The core never creates more than one UHC world. Seed changes apply only to a new world. */
public final class WorldManager {
    private final Settings settings;
    private World world;

    public WorldManager(Settings settings) { this.settings = settings; }

    public World ensureWorld() {
        if (world != null) return world;
        String name = settings.string("mundo.nombre");
        if (name.isBlank() || name.contains("/") || name.contains("\\") || name.equals("..")) {
            throw new IllegalArgumentException("Unsafe UHC world name");
        }
        world = Bukkit.getWorld(name);
        if (world == null) {
            WorldCreator creator = new WorldCreator(name).environment(World.Environment.NORMAL);
            if (settings.seed() != 0) creator.seed(settings.seed());
            world = creator.createWorld();
        }
        if (world == null) throw new IllegalStateException("Paper could not create UHC world");
        world.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);
        return world;
    }

    public World world() { return world; }

    public void resetBorder() {
        World current = ensureWorld();
        current.getWorldBorder().setCenter(0, 0);
        current.getWorldBorder().setSize(settings.integer("mundo.radio") * 2.0);
    }
}
