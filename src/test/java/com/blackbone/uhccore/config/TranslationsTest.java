package com.blackbone.uhccore.config;

import static org.junit.jupiter.api.Assertions.*;
import com.blackbone.uhccore.i18n.LocaleManager;
import java.io.File;
import java.util.Set;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.mockbukkit.mockbukkit.MockBukkit;
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

    @Test void guiTextSwitchesLanguageAndUntrustedPlaceholdersStayLiteral() {
        var server = MockBukkit.mock();
        try {
            var player = server.addPlayer("Reader");
            var locales = new LocaleManager(new File("src/main/resources/lang"));
            var plain = PlainTextComponentSerializer.plainText();
            assertEquals("Reglas activas de la partida", plain.serialize(locales.text(player, "info.rules")));
            locales.select(player, "en");
            assertEquals("Active match rules", plain.serialize(locales.text(player, "info.rules")));
            var result = locales.text(player, "match.finished", "winner", "<red>spoof</red>",
                "ranking", "Reader");
            assertTrue(plain.serialize(result).contains("<red>spoof</red>"));
        } finally {
            MockBukkit.unmock();
        }
    }
}
