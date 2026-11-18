package com.blackbone.uhccore.i18n;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** MiniMessage templates live on disk; placeholder values are unparsed, never interpreted as markup. */
public final class LocaleManager {
    private final YamlConfiguration spanish;
    private final YamlConfiguration english;
    private final Map<UUID, String> selections = new ConcurrentHashMap<>();
    private final MiniMessage mini = MiniMessage.miniMessage();

    public LocaleManager(JavaPlugin plugin) {
        plugin.saveResource("lang/es.yml", false);
        plugin.saveResource("lang/en.yml", false);
        File folder = new File(plugin.getDataFolder(), "lang");
        spanish = YamlConfiguration.loadConfiguration(new File(folder, "es.yml"));
        english = YamlConfiguration.loadConfiguration(new File(folder, "en.yml"));
    }

    /** File-backed constructor also permits validating shipping translations without a server. */
    public LocaleManager(File folder) {
        spanish = YamlConfiguration.loadConfiguration(new File(folder, "es.yml"));
        english = YamlConfiguration.loadConfiguration(new File(folder, "en.yml"));
    }

    public void select(Player player, String code) {
        if (!code.equals("es") && !code.equals("en")) throw new IllegalArgumentException("Unsupported language");
        selections.put(player.getUniqueId(), code);
    }

    public void detect(Player player) {
        selections.putIfAbsent(player.getUniqueId(), player.locale().getLanguage().equalsIgnoreCase("en") ? "en" : "es");
    }

    public String language(Player player) { return selections.getOrDefault(player.getUniqueId(), "es"); }
    public void leave(Player player) { selections.remove(player.getUniqueId()); }

    public Component text(Player player, String key, String... replacements) {
        String language = player == null ? "es" : language(player);
        YamlConfiguration source = language.equals("en") ? english : spanish;
        String template = source.getString(key, spanish.getString(key));
        if (template == null) throw new IllegalArgumentException("Missing translation: " + key);
        if (replacements.length % 2 != 0) throw new IllegalArgumentException("Unpaired translation placeholders");
        TagResolver.Builder tags = TagResolver.builder();
        for (int i = 0; i < replacements.length; i += 2) {
            tags.resolver(Placeholder.unparsed(replacements[i], replacements[i + 1]));
        }
        return mini.deserialize(template, tags.build());
    }

    public void send(CommandSender sender, String key, String... replacements) {
        sender.sendMessage(text(sender instanceof Player player ? player : null, key, replacements));
    }

    public Component prefix(String miniMessage) { return mini.deserialize(miniMessage); }
}
