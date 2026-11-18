package com.blackbone.uhccore.config;

import com.blackbone.uhccore.domain.BorderPhase;
import com.blackbone.uhccore.domain.BorderSchedule;
import com.blackbone.uhccore.domain.ProtocolPolicy;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** All mutating methods run on Paper's main thread. Writes are atomic to survive restarts. */
public final class Settings {
    public enum Topology { STANDALONE, PROXY }
    private final File file;
    private final Logger logger;
    private final YamlConfiguration yaml;

    public Settings(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
        this.yaml = YamlConfiguration.loadConfiguration(file);
    }

    public String string(String key) { return yaml.getString(key, ""); }
    public int integer(String key) { return yaml.getInt(key); }
    public boolean bool(String key) { return yaml.getBoolean(key); }
    public double decimal(String key) { return yaml.getDouble(key); }
    public long seed() { return yaml.getLong("mundo.semilla"); }
    public Topology topology() { return Topology.valueOf(string("topologia").toUpperCase(java.util.Locale.ROOT)); }

    public BorderSchedule borderSchedule() {
        List<BorderPhase> phases = new ArrayList<>();
        for (var map : yaml.getMapList("borde.fases")) {
            phases.add(new BorderPhase(number(map.get("inicio")), number(map.get("duracion")), number(map.get("radio"))));
        }
        return new BorderSchedule(integer("mundo.radio"), phases);
    }

    private static int number(Object value) {
        if (value instanceof Number number) return number.intValue();
        throw new IllegalArgumentException("Border phase must contain numeric fields");
    }

    public void setPhases(BorderSchedule schedule) {
        if (schedule.initialRadius() != integer("mundo.radio")) throw new IllegalArgumentException("World radius changed");
        List<java.util.Map<String, Object>> list = new ArrayList<>();
        for (BorderPhase phase : schedule.phases()) {
            list.add(java.util.Map.of("inicio", phase.startSeconds(), "duracion", phase.durationSeconds(), "radio", phase.targetRadius()));
        }
        set("borde.fases", list);
    }

    public void set(String key, Object value) {
        yaml.set(key, value);
        save();
    }

    public void setProtocolPolicy(ProtocolPolicy policy) {
        yaml.set("compatibilidad.modo", policy.mode().name());
        yaml.set("compatibilidad.protocolo-minimo", policy.minimum());
        yaml.set("compatibilidad.protocolo-maximo", policy.maximum());
        save();
    }

    public void validate() {
        int radius = integer("mundo.radio");
        int separation = integer("mundo.min-distancia-scatter");
        if (radius < 128 || radius > 20000 || integer("pre-game-seconds") < 0
            || integer("pre-game-seconds") > 600 || separation < 16
            || separation > 2 * (radius - 32)) {
            throw new IllegalArgumentException("World radius, scatter separation or pregame duration is invalid");
        }
        String worldName = string("mundo.nombre");
        if (worldName.isBlank() || worldName.contains("/") || worldName.contains("\\")
            || worldName.equals("..") || worldName.equals(".")) {
            throw new IllegalArgumentException("Unsafe UHC world name");
        }
        if (integer("mecanicas.random-killer-recompensa-elo") < 0) {
            throw new IllegalArgumentException("Random killer Elo reward cannot be negative");
        }
        if (decimal("mecanicas.golden-heads-probabilidad") < 0 || decimal("mecanicas.golden-heads-probabilidad") > 1) {
            throw new IllegalArgumentException("Golden head chance must be in [0,1]");
        }
        if (integer("mecanicas.timebomb-segundos") < 1 || integer("mecanicas.random-killer-periodo-segundos") < 1) {
            throw new IllegalArgumentException("Mechanic timers must be positive");
        }
        topology();
        borderSchedule();
        ProtocolPolicy.Mode.valueOf(string("compatibilidad.modo").toUpperCase(java.util.Locale.ROOT));
        String expiry = string("mecanicas.combat-logger-expiracion");
        if (!expiry.equals("AUTO_DEATH") && !expiry.equals("FORFEIT")) throw new IllegalArgumentException("Unknown combat logger expiry rule");
        int floor = integer("compatibilidad.protocolo-minimo");
        if (floor < ProtocolPolicy.OLDEST_SUPPORTED) {
            logger.warning("Configured protocol floor below 1.17 (755): clamped to 755");
            set("compatibilidad.protocolo-minimo", ProtocolPolicy.OLDEST_SUPPORTED);
        }
    }

    public void save() {
        try {
            File temp = new File(file.getParentFile(), file.getName() + ".tmp");
            yaml.save(temp);
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to save " + file, ex);
        }
    }
}
