package com.blackbone.uhccore.config;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class TranslationsTest {
    @Test void englishAndSpanishHaveExactlyTheSameKeysAndNonEmptyMessages() {
        var es = YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/es.yml"));
        var en = YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/en.yml"));
        Set<String> spanish = es.getKeys(true);
        Set<String> english = en.getKeys(true);
        assertEquals(spanish, english);
        assertTrue(spanish.size() >= 140);
        for (String key : spanish) {
            if (es.isConfigurationSection(key)) continue;
            assertFalse(es.getString(key, "").isBlank(), key);
            assertFalse(en.getString(key, "").isBlank(), key);
        }
    }
}
