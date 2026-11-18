package com.blackbone.uhccore.config;

import static org.junit.jupiter.api.Assertions.*;
import com.blackbone.uhccore.domain.ProtocolPolicy;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsTest {
    @TempDir Path folder;

    private Settings settings() throws Exception {
        File file = folder.resolve("settings.yml").toFile();
        Files.copy(Path.of("src/main/resources/settings.yml"), file.toPath());
        return new Settings(file, Logger.getLogger("test"));
    }

    @Test void persistsAtomicRuleAndBorderEdits() throws Exception {
        Settings settings = settings();
        settings.validate();
        assertEquals(Settings.Topology.STANDALONE, settings.topology());
        assertEquals(3, settings.borderSchedule().phases().size());
        settings.set("topologia", "PROXY");
        settings.setPhases(settings.borderSchedule().withoutPhase(2));
        Settings reloaded = new Settings(folder.resolve("settings.yml").toFile(), Logger.getLogger("test"));
        assertEquals(Settings.Topology.PROXY, reloaded.topology());
        assertEquals(2, reloaded.borderSchedule().phases().size());
    }

    @Test void clampedProtocolFloorAndStrictRangePersist() throws Exception {
        Settings config = settings();
        config.set("compatibilidad.protocolo-minimo", 10);
        config.validate();
        assertEquals(755, config.integer("compatibilidad.protocolo-minimo"));
        config.setProtocolPolicy(ProtocolPolicy.resolve(ProtocolPolicy.Mode.ESTRICTO, 755, 777, true, true));
        assertEquals(777, new Settings(folder.resolve("settings.yml").toFile(), Logger.getLogger("test"))
            .integer("compatibilidad.protocolo-minimo"));
    }
}
