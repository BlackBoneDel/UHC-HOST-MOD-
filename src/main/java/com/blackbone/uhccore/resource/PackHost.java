package com.blackbone.uhccore.resource;

import com.blackbone.uhccore.config.Settings;
import com.blackbone.uhccore.i18n.LocaleManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Self-hosted mandatory resource packs; the legacy font-only pack supports flexible clients. */
public final class PackHost implements AutoCloseable {
    private record Pack(File file, String hash, String url) { }
    private final Plugin plugin;
    private final Settings settings;
    private final LocaleManager locales;
    private final Map<String, Pack> packs;
    private final HttpServer server;
    private final ExecutorService workers;

    public PackHost(Plugin plugin, Settings settings, LocaleManager locales) throws IOException {
        this.plugin = plugin; this.settings = settings; this.locales = locales;
        int port = settings.integer("resource-pack.puerto");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid resource pack port");
        File nativeFile = extract("uhccore-pack.zip");
        File legacyFile = extract("uhccore-legacy-pack.zip");
        String address = settings.string("resource-pack.host-publico");
        if (address.isBlank()) {
            address = Bukkit.getIp();
            if (address.isBlank()) address = InetAddress.getLocalHost().getHostAddress();
            plugin.getLogger().warning("resource-pack.host-publico unset: auto-detected " + address
                + "; configure your public DNS/IP if players cannot download the pack");
        }
        if (!address.matches("[A-Za-z0-9.:-]+")) throw new IllegalArgumentException("Invalid resource pack public host");
        String base = "http://" + (address.contains(":") ? "[" + address + "]" : address) + ":" + port + "/";
        packs = Map.of("/uhccore-pack.zip", new Pack(nativeFile, sha1(nativeFile), base + "uhccore-pack.zip"),
            "/uhccore-legacy-pack.zip", new Pack(legacyFile, sha1(legacyFile), base + "uhccore-legacy-pack.zip"));
        HttpServer host = null;
        ExecutorService executor = null;
        try {
            host = HttpServer.create(new InetSocketAddress(settings.string("resource-pack.bind"), port), 64);
            executor = Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "uhccore-pack-http"); thread.setDaemon(true); return thread;
            });
            host.setExecutor(executor);
            host.createContext("/", this::serve);
            host.start();
        } catch (IOException ex) {
            if (host != null) host.stop(0);
            if (executor != null) executor.shutdownNow();
            host = null;
            executor = null;
            if (settings.string("resource-pack.url-externa").isBlank()) throw ex;
            plugin.getLogger().warning("HTTP pack host failed; explicit external URL fallback enabled: " + ex.getMessage());
        }
        server = host;
        workers = executor;
    }

    private File extract(String name) throws IOException {
        File output = new File(plugin.getDataFolder(), name);
        try (InputStream input = plugin.getResource("packs/" + name)) {
            if (input == null) throw new IOException("Missing bundled pack: " + name);
            Files.copy(input, output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return output;
    }

    private static String sha1(File file) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(file.toPath()));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            Pack pack = packs.get(exchange.getRequestURI().getPath());
            if (pack == null) { exchange.sendResponseHeaders(404, -1); return; }
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1); return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
            exchange.getResponseHeaders().set("ETag", "\"" + pack.hash() + "\"");
            if (method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(pack.file().length()));
                exchange.sendResponseHeaders(200, -1); return;
            }
            exchange.sendResponseHeaders(200, pack.file().length());
            try (var stream = Files.newInputStream(pack.file().toPath())) { stream.transferTo(exchange.getResponseBody()); }
        }
    }

    public void send(Player player, int protocol) {
        boolean legacy = protocol < 777;
        Pack pack = packs.get(legacy ? "/uhccore-legacy-pack.zip" : "/uhccore-pack.zip");
        String external = settings.string("resource-pack.url-externa");
        if (legacy) {
            String legacyExternal = settings.string("resource-pack.url-externa-legacy");
            external = !legacyExternal.isBlank() ? legacyExternal
                : external.endsWith("/") ? external + "uhccore-legacy-pack.zip" : "";
        } else if (external.endsWith("/")) {
            external += "uhccore-pack.zip";
        }
        String url = server == null ? external : pack.url();
        if (!url.startsWith("https://") && !url.startsWith("http://")) throw new IllegalStateException("Invalid resource pack URL");
        player.setResourcePack(UUID.nameUUIDFromBytes(("uhccore:" + protocol).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            url, pack.hash(), locales.text(player, "pack.prompt"), true);
    }

    @Override public void close() {
        if (server != null) server.stop(0);
        if (workers != null) workers.shutdownNow();
    }
}
