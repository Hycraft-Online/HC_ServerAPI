package com.hcserverapi;

import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.command.system.CommandOwner;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.DefaultArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.FlagArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgumentType;
import com.hypixel.hytale.server.core.console.ConsoleSender;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import com.hypixel.hytale.common.util.GCUtil;
import com.hypixel.hytale.logger.HytaleLogger;

public class HttpService {

    private final HttpServer server;
    private final String apiKey;
    private final HytaleLogger logger;

    // GC tracking
    private long lastGcCount = 0;
    private long lastGcTimeMs = 0;
    private volatile long lastPauseDurationMs = 0;
    private volatile long maxPauseMs = 0;
    private volatile long pauseResetTime = System.currentTimeMillis();

    public HttpService(int port, String apiKey, HytaleLogger logger) throws IOException {
        this.apiKey = apiKey;
        this.logger = logger;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.setExecutor(Executors.newCachedThreadPool());
        this.server.createContext("/command", new CommandHandler());
        this.server.createContext("/commands", new CommandsHandler());
        this.server.createContext("/health", new HealthHandler());
        this.server.createContext("/stats", new StatsHandler());

        // Register GC event listener for pause tracking
        GCUtil.register(info -> {
            lastPauseDurationMs = info.getGcInfo().getDuration();
            long now = System.currentTimeMillis();
            if (now - pauseResetTime > 10_000) {
                maxPauseMs = 0;
                pauseResetTime = now;
            }
            maxPauseMs = Math.max(maxPauseMs, lastPauseDurationMs);
        });
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(2);
    }

    private void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private boolean authenticate(HttpExchange exchange) throws IOException {
        String key = exchange.getRequestHeaders().getFirst("X-API-Key");
        if (key == null || !key.equals(apiKey)) {
            sendJson(exchange, 401, "{\"success\":false,\"error\":\"Unauthorized\"}");
            return false;
        }
        return true;
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private class CommandHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"success\":false,\"error\":\"Method not allowed\"}");
                return;
            }

            if (!authenticate(exchange)) return;

            String body = readBody(exchange);

            // Simple JSON parsing - extract "command" field
            String command = extractJsonString(body, "command");
            if (command == null || command.isBlank()) {
                sendJson(exchange, 400, "{\"success\":false,\"error\":\"Missing 'command' field\"}");
                return;
            }

            // Sanitize: strip leading slash if present
            command = command.strip();
            if (command.startsWith("/")) {
                command = command.substring(1);
            }

            logger.at(Level.INFO).log("API executing command: " + command);

            try {
                CompletableFuture<Void> future = CommandManager.get()
                        .handleCommand(ConsoleSender.INSTANCE, command);
                future.get(5, TimeUnit.SECONDS);

                sendJson(exchange, 200,
                        "{\"success\":true,\"command\":\"" + escapeJson(command) + "\"}");
            } catch (TimeoutException e) {
                sendJson(exchange, 200,
                        "{\"success\":true,\"command\":\"" + escapeJson(command) + "\",\"message\":\"Command sent (timed out waiting for completion)\"}");
            } catch (Exception e) {
                String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                sendJson(exchange, 500,
                        "{\"success\":false,\"command\":\"" + escapeJson(command) + "\",\"error\":\"" + escapeJson(msg) + "\"}");
            }
        }
    }

    private class CommandsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"success\":false,\"error\":\"Method not allowed\"}");
                return;
            }

            if (!authenticate(exchange)) return;

            try {
                Map<String, AbstractCommand> commands = CommandManager.get().getCommandRegistration();
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;

                for (Map.Entry<String, AbstractCommand> entry : commands.entrySet()) {
                    AbstractCommand cmd = entry.getValue();
                    if (cmd.getName() == null) continue;

                    if (!first) sb.append(",");
                    first = false;

                    serializeCommandNode(cmd, null, sb, 0);
                }

                sb.append("]");
                sendJson(exchange, 200, sb.toString());
            } catch (Exception e) {
                logger.at(Level.SEVERE).log("Failed to list commands: " + e.getMessage());
                sendJson(exchange, 500, "{\"success\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        }
    }

    private String resolveI18n(String key) {
        if (key == null || key.isEmpty()) return "";
        try {
            String resolved = I18nModule.get().getMessage("en-US", key);
            if (resolved != null && !resolved.isEmpty()) return resolved;
        } catch (Exception ignored) {}
        // For unresolved i18n keys like "server.commands.parsing.argtype.enum.name",
        // extract just the meaningful last segment(s)
        int lastDot = key.lastIndexOf('.');
        if (lastDot >= 0) {
            String tail = key.substring(lastDot + 1);
            // Capitalize first letter
            if (!tail.isEmpty()) return tail.substring(0, 1).toUpperCase() + tail.substring(1);
        }
        return key.replace(".", " ");
    }

    private String getArgTypeName(ArgumentType<?> type) {
        try {
            // getRawText() is null for translation-based Messages, so try multiple fallbacks
            String raw = type.getName().getRawText();
            if (raw != null && !raw.isEmpty()) return raw;

            // Try resolving the i18n key
            String msgId = type.getName().getMessageId();
            if (msgId != null && !msgId.isEmpty()) {
                String resolved = resolveI18n(msgId);
                if (resolved != null && !resolved.isEmpty()) return resolved;
            }

            // Last resort: use the class simple name (e.g. "StringArgumentType" -> "string")
            String className = type.getClass().getSimpleName()
                .replace("ArgumentType", "")
                .replace("Argument", "");
            if (!className.isEmpty()) return className.toLowerCase();
        } catch (Exception ignored) {}
        return "unknown";
    }

    @SuppressWarnings("unchecked")
    private void serializeCommandNode(AbstractCommand cmd, String nameOverride, StringBuilder sb, int depth) {
        if (depth > 6) { sb.append("{}"); return; }
        sb.append("{");

        String name = nameOverride != null ? nameOverride : (cmd.getName() != null ? cmd.getName() : "");
        sb.append("\"name\":\"").append(escapeJson(name)).append("\"");

        // Description
        sb.append(",\"description\":\"").append(escapeJson(resolveI18n(cmd.getDescription()))).append("\"");

        // Aliases
        Set<String> aliases = cmd.getAliases();
        sb.append(",\"aliases\":[");
        if (aliases != null && !aliases.isEmpty()) {
            boolean f = true;
            for (String a : aliases) {
                if (!f) sb.append(",");
                f = false;
                sb.append("\"").append(escapeJson(a)).append("\"");
            }
        }
        sb.append("]");

        // Owner (top-level only)
        if (depth == 0) {
            CommandOwner owner = cmd.getOwner();
            sb.append(",\"owner\":\"").append(escapeJson(owner != null ? owner.getName() : "Unknown")).append("\"");
        }

        // Required args with enriched info
        sb.append(",\"args\":[");
        List<RequiredArg<?>> reqArgs = cmd.getRequiredArguments();
        if (reqArgs != null && !reqArgs.isEmpty()) {
            boolean f = true;
            for (RequiredArg<?> arg : reqArgs) {
                if (!f) sb.append(",");
                f = false;
                sb.append("{\"name\":\"").append(escapeJson(arg.getName())).append("\"");
                sb.append(",\"type\":\"").append(escapeJson(getArgTypeName(arg.getArgumentType()))).append("\"");
                sb.append(",\"description\":\"").append(escapeJson(resolveI18n(arg.getDescription()))).append("\"");
                try {
                    String usage = arg.getArgumentType().getArgumentUsage().getRawText();
                    if (usage != null && !usage.isEmpty()) {
                        sb.append(",\"usage\":\"").append(escapeJson(resolveI18n(usage))).append("\"");
                    }
                } catch (Exception ignored) {}
                String[] examples = arg.getArgumentType().getExamples();
                if (examples != null && examples.length > 0) {
                    sb.append(",\"examples\":[");
                    for (int i = 0; i < Math.min(examples.length, 5); i++) {
                        if (i > 0) sb.append(",");
                        sb.append("\"").append(escapeJson(examples[i])).append("\"");
                    }
                    sb.append("]");
                }
                sb.append("}");
            }
        }
        sb.append("]");

        // Optional args (via reflection)
        sb.append(",\"optionalArgs\":[");
        try {
            Field optField = AbstractCommand.class.getDeclaredField("optionalArguments");
            optField.setAccessible(true);
            Map<String, Object> optArgs = (Map<String, Object>) optField.get(cmd);
            if (optArgs != null && !optArgs.isEmpty()) {
                boolean f = true;
                for (Map.Entry<String, Object> entry : optArgs.entrySet()) {
                    Object arg = entry.getValue();
                    if (!f) sb.append(",");
                    f = false;
                    sb.append("{\"name\":\"").append(escapeJson(entry.getKey())).append("\"");
                    if (arg instanceof FlagArg flagArg) {
                        sb.append(",\"kind\":\"flag\"");
                        sb.append(",\"description\":\"").append(escapeJson(resolveI18n(flagArg.getDescription()))).append("\"");
                    } else if (arg instanceof DefaultArg<?> defArg) {
                        sb.append(",\"kind\":\"default\"");
                        sb.append(",\"type\":\"").append(escapeJson(getArgTypeName(defArg.getArgumentType()))).append("\"");
                        sb.append(",\"default\":\"").append(escapeJson(defArg.getDefaultValueDescription())).append("\"");
                        sb.append(",\"description\":\"").append(escapeJson(resolveI18n(defArg.getDescription()))).append("\"");
                    } else if (arg instanceof OptionalArg<?> optArg) {
                        sb.append(",\"kind\":\"optional\"");
                        sb.append(",\"type\":\"").append(escapeJson(getArgTypeName(optArg.getArgumentType()))).append("\"");
                        sb.append(",\"description\":\"").append(escapeJson(resolveI18n(optArg.getDescription()))).append("\"");
                    }
                    sb.append("}");
                }
            }
        } catch (Exception ignored) {}
        sb.append("]");

        // Subcommands (recursive)
        Map<String, AbstractCommand> subCmds = cmd.getSubCommands();
        sb.append(",\"subcommands\":[");
        if (subCmds != null && !subCmds.isEmpty()) {
            boolean f = true;
            for (Map.Entry<String, AbstractCommand> sub : subCmds.entrySet()) {
                if (!f) sb.append(",");
                f = false;
                serializeCommandNode(sub.getValue(), sub.getKey(), sb, depth + 1);
            }
        }
        sb.append("]");

        sb.append("}");
    }

    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"success\":false,\"error\":\"Method not allowed\"}");
                return;
            }
            sendJson(exchange, 200, "{\"status\":\"ok\"}");
        }
    }

    private class StatsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"success\":false,\"error\":\"Method not allowed\"}");
                return;
            }

            if (!authenticate(exchange)) return;

            try {
                StringBuilder sb = new StringBuilder("{");

                // Memory
                MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                long heapUsedMb = heap.getUsed() / (1024 * 1024);
                long heapMaxMb = heap.getMax() / (1024 * 1024);
                sb.append("\"memory\":{\"heapUsedMb\":").append(heapUsedMb)
                  .append(",\"heapMaxMb\":").append(heapMaxMb).append("}");

                // CPU
                double cpuLoad = -1;
                try {
                    var osBean = (com.sun.management.OperatingSystemMXBean)
                            ManagementFactory.getOperatingSystemMXBean();
                    cpuLoad = osBean.getProcessCpuLoad();
                } catch (Exception ignored) {}
                sb.append(",\"cpu\":").append(String.format("%.4f", cpuLoad));

                // Uptime
                long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
                sb.append(",\"uptimeSeconds\":").append(uptimeSeconds);

                // Players
                int playerCount = Universe.get().getPlayerCount();
                sb.append(",\"players\":").append(playerCount);

                // TPS & MSPT from first available world, plus per-world info
                Collection<World> worlds = Universe.get().getWorlds().values();
                int tps = 0;
                double msptAvg10s = 0, msptAvg1m = 0, msptAvg5m = 0, msptMin10s = 0, msptMax10s = 0;
                boolean msptSet = false;

                sb.append(",\"worlds\":[");
                boolean firstWorld = true;
                for (World world : worlds) {
                    if (!firstWorld) sb.append(",");
                    firstWorld = false;

                    if (!msptSet) {
                        tps = world.getTps();
                        try {
                            var metrics = world.getBufferedTickLengthMetricSet();
                            msptAvg10s = metrics.getAverage(0) / 1e6;
                            msptAvg1m = metrics.getAverage(1) / 1e6;
                            msptAvg5m = metrics.getAverage(2) / 1e6;
                            msptMin10s = metrics.calculateMin(0) / 1e6;
                            msptMax10s = metrics.calculateMax(0) / 1e6;
                        } catch (Exception e) {
                            logger.at(Level.WARNING).log("Failed to read MSPT metrics: " + e.getMessage());
                        }
                        msptSet = true;
                    }

                    int worldPlayers = world.getPlayerCount();
                    int chunks = 0;
                    try {
                        chunks = world.getChunkStore().getLoadedChunksCount();
                    } catch (Exception ignored) {}
                    long tick = world.getTick();

                    sb.append("{\"name\":\"").append(escapeJson(world.getName())).append("\"")
                      .append(",\"players\":").append(worldPlayers)
                      .append(",\"chunks\":").append(chunks)
                      .append(",\"tick\":").append(tick)
                      .append("}");
                }
                sb.append("]");

                sb.append(",\"tps\":").append(tps);
                sb.append(",\"mspt\":{")
                  .append("\"avg10s\":").append(String.format("%.1f", msptAvg10s))
                  .append(",\"avg1m\":").append(String.format("%.1f", msptAvg1m))
                  .append(",\"avg5m\":").append(String.format("%.1f", msptAvg5m))
                  .append(",\"min10s\":").append(String.format("%.1f", msptMin10s))
                  .append(",\"max10s\":").append(String.format("%.1f", msptMax10s))
                  .append("}");

                // GC stats
                long totalGcCount = 0;
                long totalGcTimeMs = 0;
                for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
                    long c = gcBean.getCollectionCount();
                    long t = gcBean.getCollectionTime();
                    if (c >= 0) totalGcCount += c;
                    if (t >= 0) totalGcTimeMs += t;
                }
                long deltaCounts = totalGcCount - lastGcCount;
                long deltaTimeMs = totalGcTimeMs - lastGcTimeMs;
                lastGcCount = totalGcCount;
                lastGcTimeMs = totalGcTimeMs;

                sb.append(",\"gc\":{")
                  .append("\"collections\":").append(totalGcCount)
                  .append(",\"totalTimeMs\":").append(totalGcTimeMs)
                  .append(",\"collectionsSinceLast\":").append(deltaCounts)
                  .append(",\"timeSinceLastMs\":").append(deltaTimeMs)
                  .append(",\"lastPauseMs\":").append(lastPauseDurationMs)
                  .append(",\"maxPause10sMs\":").append(maxPauseMs)
                  .append("}");

                // Disk stats
                File root = new File(".");
                double totalGb = root.getTotalSpace() / (1024.0 * 1024.0 * 1024.0);
                double freeGb = root.getFreeSpace() / (1024.0 * 1024.0 * 1024.0);
                double usableGb = root.getUsableSpace() / (1024.0 * 1024.0 * 1024.0);
                sb.append(",\"disk\":{")
                  .append("\"totalGb\":").append(String.format("%.2f", totalGb))
                  .append(",\"freeGb\":").append(String.format("%.2f", freeGb))
                  .append(",\"usableGb\":").append(String.format("%.2f", usableGb))
                  .append("}");

                // Network stats (Linux /proc/net/dev)
                try {
                    File procNetDev = new File("/proc/net/dev");
                    if (procNetDev.exists()) {
                        long bytesIn = 0, bytesOut = 0;
                        try (BufferedReader br = Files.newBufferedReader(procNetDev.toPath())) {
                            String line;
                            while ((line = br.readLine()) != null) {
                                line = line.trim();
                                if (!line.contains(":") || line.startsWith("Inter") || line.startsWith("face")) continue;
                                String[] parts = line.split("\\s+");
                                String iface = parts[0].replace(":", "");
                                if ("lo".equals(iface)) continue;
                                // field 1 = bytes received, field 9 = bytes transmitted
                                if (parts.length >= 10) {
                                    bytesIn += Long.parseLong(parts[1]);
                                    bytesOut += Long.parseLong(parts[9]);
                                }
                            }
                        }
                        sb.append(",\"network\":{")
                          .append("\"bytesIn\":").append(bytesIn)
                          .append(",\"bytesOut\":").append(bytesOut)
                          .append("}");
                    }
                } catch (Exception ignored) {}

                sb.append("}");
                sendJson(exchange, 200, sb.toString());
            } catch (Exception e) {
                logger.at(Level.SEVERE).log("Failed to collect stats: " + e.getMessage());
                sendJson(exchange, 500, "{\"success\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        }
    }

    /**
     * Minimal JSON string field extractor. Avoids needing Gson dependency.
     */
    private static String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int keyIndex = json.indexOf(pattern);
        if (keyIndex < 0) return null;

        int colonIndex = json.indexOf(':', keyIndex + pattern.length());
        if (colonIndex < 0) return null;

        int firstQuote = json.indexOf('"', colonIndex + 1);
        if (firstQuote < 0) return null;

        StringBuilder sb = new StringBuilder();
        for (int i = firstQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case '"' -> { sb.append('"'); i++; }
                    case '\\' -> { sb.append('\\'); i++; }
                    case 'n' -> { sb.append('\n'); i++; }
                    case 'r' -> { sb.append('\r'); i++; }
                    case 't' -> { sb.append('\t'); i++; }
                    default -> sb.append(c);
                }
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null;
    }
}
