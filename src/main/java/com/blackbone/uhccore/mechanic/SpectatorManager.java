package com.blackbone.uhccore.mechanic;

import com.blackbone.uhccore.domain.MatchClock;
import com.blackbone.uhccore.game.UhcGameManager;
import com.blackbone.uhccore.i18n.LocaleManager;
import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import com.blackbone.uhccore.gui.UhcMenu;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Immediate respawn, spectator camera compass, and an isolated Adventure chat channel. */
public final class SpectatorManager implements Listener {
    private static final int PAGE_SIZE = 45;
    private static final int BACKUP_FORMAT = 1;
    private static final int MAX_BACKUP_BYTES = 8 * 1024 * 1024;
    private record LateJoinBackup(GameMode mode, ItemStack[] inventory, ItemStack[] armor,
                                  ItemStack[] offhand, ItemStack[] enderChest) { }
    private final Plugin plugin;
    private final UhcGameManager game;
    private final LocaleManager locales;
    private final NamespacedKey compassKey;
    private final Path backupFolder;
    private final Consumer<Player> savePlayerData;
    private final Set<UUID> spectatorIds = ConcurrentHashMap.newKeySet();

    private static final class Holder implements UhcMenu {
        Inventory inventory;
        final int page;
        final List<UUID> targets;
        Holder(int page, List<UUID> targets) { this.page = page; this.targets = targets; }
        @Override public Inventory getInventory() { return inventory; }
    }

    public SpectatorManager(Plugin plugin, UhcGameManager game, LocaleManager locales) {
        this(plugin, game, locales, Player::saveData);
    }

    // MockBukkit cannot implement Player.saveData(); tests inject only that last acknowledgement.
    SpectatorManager(Plugin plugin, UhcGameManager game, LocaleManager locales,
                     Consumer<Player> savePlayerData) {
        this.plugin = plugin; this.game = game; this.locales = locales;
        this.savePlayerData = java.util.Objects.requireNonNull(savePlayerData);
        compassKey = new NamespacedKey(plugin, "spectator_compass");
        backupFolder = plugin.getDataFolder().toPath().resolve("late-join-inventories");
    }

    @EventHandler public void respawnLocation(PlayerRespawnEvent event) {
        if (game.isEliminated(event.getPlayer().getUniqueId())) {
            event.setRespawnLocation(event.getPlayer().getWorld().getSpawnLocation());
        }
    }

    @EventHandler public void respawn(PlayerPostRespawnEvent event) {
        if (game.isEliminated(event.getPlayer().getUniqueId())) equip(event.getPlayer());
    }

    @EventHandler public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (player.getGameMode() == GameMode.SPECTATOR) spectatorIds.add(id);
        if (!matchActive() && Files.exists(backupFile(id))) {
            restoreIfStored(player); // failure kicks; never follow it by clearing the inventory
            return;
        }
        if (!spectatorOnJoin(id)) return;
        if (!game.isEliminated(id) && !game.isAlive(id)) {
            try {
                // Save BEFORE equip, in the same join tick: a one-tick gap could
                // let a late joiner move or drop the original items after backup.
                saveLateJoinBackup(player);
                equip(player);
            } catch (IOException | RuntimeException error) {
                plugin.getLogger().log(Level.SEVERE, "Late-join inventory backup/equip failed for " + id, error);
                player.kick(locales.text(player, "command.persistence-error"));
            }
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && spectatorOnJoin(id)) equip(player);
        });
    }

    private boolean matchActive() {
        return game.state() == MatchClock.State.PREGAME || game.state() == MatchClock.State.RUNNING;
    }

    private boolean spectatorOnJoin(UUID id) {
        return game.isEliminated(id) || (matchActive() && !game.isAlive(id));
    }

    private Path backupFile(UUID id) { return backupFolder.resolve(id + ".dat"); }

    private void saveLateJoinBackup(Player player) throws IOException {
        Path file = backupFile(player.getUniqueId());
        if (Files.exists(file)) {
            readBackup(file, player.getUniqueId()); // never replace the original with a spectator compass
            return;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(BACKUP_FORMAT);
            output.writeUTF(player.getUniqueId().toString());
            output.writeUTF(player.getGameMode().name());
            writeItems(output, player.getInventory().getContents());
            writeItems(output, player.getInventory().getArmorContents());
            writeItems(output, new ItemStack[] { player.getInventory().getItemInOffHand() });
            writeItems(output, player.getEnderChest().getContents());
        }
        if (bytes.size() > MAX_BACKUP_BYTES) throw new IOException("Late-join inventory exceeds backup size limit");
        Files.createDirectories(backupFolder);
        Path temporary = Files.createTempFile(backupFolder, player.getUniqueId().toString(), ".tmp");
        try {
            Files.write(temporary, bytes.toByteArray());
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeItems(DataOutputStream output, ItemStack[] items) throws IOException {
        byte[] bytes = ItemStack.serializeItemsAsBytes(items);
        if (bytes.length > MAX_BACKUP_BYTES) throw new IOException("Item data exceeds backup size limit");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static ItemStack[] readItems(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > MAX_BACKUP_BYTES) throw new IOException("Invalid saved inventory length");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        try {
            return ItemStack.deserializeItemsFromBytes(bytes);
        } catch (RuntimeException error) {
            throw new IOException("Invalid saved inventory items", error);
        }
    }

    private LateJoinBackup readBackup(Path file, UUID id) throws IOException {
        if (Files.size(file) > MAX_BACKUP_BYTES) throw new IOException("Oversized late-join inventory backup");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(file)))) {
            if (input.readInt() != BACKUP_FORMAT || !input.readUTF().equals(id.toString())) {
                throw new IOException("Late-join inventory version or UUID mismatch");
            }
            GameMode mode;
            try { mode = GameMode.valueOf(input.readUTF()); }
            catch (IllegalArgumentException error) { throw new IOException("Invalid saved game mode", error); }
            LateJoinBackup saved = new LateJoinBackup(mode, readItems(input), readItems(input),
                readItems(input), readItems(input));
            if (saved.offhand().length != 1 || input.available() != 0) {
                throw new IOException("Malformed late-join inventory backup");
            }
            return saved;
        }
    }

    private boolean restoreIfStored(Player player) {
        Path file = backupFile(player.getUniqueId());
        if (!Files.exists(file)) return false;
        try {
            LateJoinBackup saved = readBackup(file, player.getUniqueId());
            player.closeInventory();
            player.getInventory().setContents(saved.inventory());
            player.getInventory().setArmorContents(saved.armor());
            player.getInventory().setItemInOffHand(saved.offhand()[0]);
            player.getEnderChest().setContents(saved.enderChest());
            player.setGameMode(saved.mode());
            // Commit playerdata before deleting the only independent copy of these items.
            savePlayerData.accept(player);
            Files.delete(file);
            if (saved.mode() != GameMode.SPECTATOR) spectatorIds.remove(player.getUniqueId());
            return true;
        } catch (IOException | RuntimeException error) {
            plugin.getLogger().log(Level.SEVERE,
                "Late-join inventory restoration NOT CONFIRMED for " + player.getUniqueId(), error);
            player.kick(locales.text(player, "command.persistence-error"));
            return false;
        }
    }

    /** End of match or aborted countdown: online late joiners get their original items back. */
    public void restoreLateJoiners() {
        for (Player player : Bukkit.getOnlinePlayers()) restoreIfStored(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void mode(PlayerGameModeChangeEvent event) {
        if (event.getNewGameMode() == GameMode.SPECTATOR) spectatorIds.add(event.getPlayer().getUniqueId());
        else spectatorIds.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler public void leave(PlayerQuitEvent event) { spectatorIds.remove(event.getPlayer().getUniqueId()); }

    /** Proxy deaths can happen while the original player's data is online again. */
    public void retireConnectedParticipant(Player player) {
        player.getEnderChest().clear();
        equip(player);
    }

    public void equip(Player player) {
        player.setGameMode(GameMode.SPECTATOR);
        spectatorIds.add(player.getUniqueId());
        player.getInventory().clear();
        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta meta = compass.getItemMeta();
        meta.displayName(locales.text(player, "spectator.compass"));
        meta.getPersistentDataContainer().set(compassKey, PersistentDataType.BYTE, (byte) 1);
        compass.setItemMeta(meta);
        player.getInventory().setItem(0, compass);
    }

    @EventHandler(ignoreCancelled = true)
    public void interact(PlayerInteractEvent event) {
        if (event.getPlayer().getGameMode() != GameMode.SPECTATOR || event.getItem() == null
            || !event.getAction().isRightClick()) return;
        if (event.getItem().hasItemMeta()
            && event.getItem().getItemMeta().getPersistentDataContainer().has(compassKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
            open(event.getPlayer(), 0);
        }
    }

    public void open(Player player, int page) {
        List<UUID> targets = game.alive().stream().filter(id -> Bukkit.getPlayer(id) != null).toList();
        int clamped = Math.clamp(page, 0, Math.max(0, (targets.size() - 1) / PAGE_SIZE));
        Holder holder = new Holder(clamped, targets);
        Inventory inventory = Bukkit.createInventory(holder, 54, locales.text(player, "spectator.title"));
        holder.inventory = inventory;
        for (int i = 0; i < PAGE_SIZE && clamped * PAGE_SIZE + i < targets.size(); i++) {
            Player target = Bukkit.getPlayer(targets.get(clamped * PAGE_SIZE + i));
            if (target == null) continue;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setPlayerProfile(target.getPlayerProfile());
            meta.displayName(Component.text(target.getName(), TextColor.fromHexString("#57D9E8")));
            head.setItemMeta(meta); inventory.setItem(i, head);
        }
        if (clamped > 0) inventory.setItem(45, nav(player, "menu.previous"));
        if ((clamped + 1) * PAGE_SIZE < targets.size()) inventory.setItem(53, nav(player, "menu.next"));
        player.openInventory(inventory);
    }

    private ItemStack nav(Player player, String key) {
        ItemStack arrow = new ItemStack(Material.ARROW);
        ItemMeta meta = arrow.getItemMeta(); meta.displayName(locales.text(player, key));
        arrow.setItemMeta(meta); return arrow;
    }

    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= 54) return;
        int slot = event.getRawSlot();
        if (slot == 45 && holder.page > 0) { open(player, holder.page - 1); return; }
        if (slot == 53 && (holder.page + 1) * PAGE_SIZE < holder.targets.size()) {
            open(player, holder.page + 1); return;
        }
        int index = holder.page * PAGE_SIZE + slot;
        if (slot >= PAGE_SIZE || index >= holder.targets.size()) return;
        Player target = Bukkit.getPlayer(holder.targets.get(index));
        if (target == null || !game.isAlive(target.getUniqueId()) || player.getGameMode() != GameMode.SPECTATOR) return;
        player.closeInventory();
        player.teleportAsync(target.getLocation()).thenAccept(success -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (success && player.isOnline() && target.isOnline() && player.getGameMode() == GameMode.SPECTATOR)
                player.setSpectatorTarget(target);
        }));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void chat(AsyncChatEvent event) {
        boolean spectator = spectatorIds.contains(event.getPlayer().getUniqueId());
        event.viewers().removeIf(viewer -> viewer instanceof Player player
            && spectatorIds.contains(player.getUniqueId()) != spectator);
        if (spectator) {
            var previous = event.renderer();
            event.renderer((source, display, message, viewer) ->
                Component.text("[Spectator] ", TextColor.fromHexString("#8B96A8"))
                    .append(previous.render(source, display, message, viewer)));
        }
    }
}
