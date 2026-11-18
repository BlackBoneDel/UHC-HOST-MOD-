package com.blackbone.uhccore.network;

import com.blackbone.uhccore.config.Settings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** One bounded worker: Discord rate limits cannot stall the Minecraft server thread. */
public final class DiscordAnnouncer implements AutoCloseable {
    private final URI webhook;
    private final Logger logger;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(256), runnable -> {
            Thread thread = new Thread(runnable, "uhccore-discord"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());

    public DiscordAnnouncer(Settings settings, Logger logger) {
        this.logger = logger;
        String url = settings.string("discord.webhook-url");
        webhook = url.isBlank() ? null : URI.create(url);
        if (webhook != null && !"https".equalsIgnoreCase(webhook.getScheme())) {
            throw new IllegalArgumentException("Discord webhook must use HTTPS");
        }
    }

    public void announce(String content) {
        if (webhook == null) return;
        try {
            worker.execute(() -> {
            try {
                String body = "{\"content\":\"" + jsonEscape(content) + "\"}";
                HttpRequest request = HttpRequest.newBuilder(webhook).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 429) {
                    long delay = response.headers().firstValue("retry-after").map(value -> {
                        try { return (long) Math.ceil(Double.parseDouble(value) * 1000); }
                        catch (NumberFormatException ignored) { return 2000L; }
                    }).orElse(2000L);
                    Thread.sleep(Math.clamp(delay, 1000L, 10000L));
                    response = client.send(request, HttpResponse.BodyHandlers.discarding());
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    logger.warning("Discord webhook returned HTTP " + response.statusCode());
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                logger.warning("Discord announcement failed: " + ex.getClass().getSimpleName());
            }
            });
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            logger.warning("Discord queue full; announcement not sent");
        }
    }

    private static String jsonEscape(String input) {
        StringBuilder result = new StringBuilder();
        for (char c : input.toCharArray()) {
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> { if (c < 32) result.append(String.format("\\u%04x", (int)c)); else result.append(c); }
            }
        }
        return result.toString();
    }

    @Override public void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); worker.shutdownNow();
        }
        client.close();
    }
}
