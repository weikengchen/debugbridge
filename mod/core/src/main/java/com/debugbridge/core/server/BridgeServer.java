package com.debugbridge.core.server;

import com.debugbridge.core.logging.LoggerService;
import com.debugbridge.core.lua.LuaRuntime;
import com.debugbridge.core.lua.ThreadDispatcher;
import com.debugbridge.core.mapping.MappingResolver;
import com.debugbridge.core.protocol.BridgeRequest;
import com.debugbridge.core.protocol.BridgeResponse;
import com.debugbridge.core.refs.ObjectRefStore;
import com.debugbridge.core.screenshot.ScreenshotProvider;
import com.debugbridge.core.snapshot.GameStateProvider;
import com.google.gson.*;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * WebSocket server that accepts Lua script execution requests and other commands.
 * Runs inside the Minecraft JVM. Accepts one client (the MCP server) at a time.
 */
public class BridgeServer extends WebSocketServer {
    private static final Logger LOG = Logger.getLogger("DebugBridge");
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private final LuaRuntime lua;
    private final MappingResolver resolver;
    private final ObjectRefStore refs;
    private final ResultSerializer serializer;
    private final GameStateProvider stateProvider;
    private final ScreenshotProvider screenshotProvider;
    /**
     * Absolute path to the game run directory (the .minecraft / profile root),
     * or {@code null} if the embedder didn't provide one. When non-null, the
     * status endpoint exposes {@code gameDir} / {@code latestLog} / {@code debugLog}
     * so a connecting MCP client can read logs via its own file-read tools.
     */
    private volatile Path gameDir;

    /**
     * Runtime logger injection service. Defaults to UNAVAILABLE until the
     * agent module registers itself.
     */
    private volatile LoggerService loggerService = LoggerService.UNAVAILABLE;

    public BridgeServer(int port, MappingResolver resolver, ThreadDispatcher dispatcher) {
        this(port, resolver, dispatcher, null, null);
    }

    public BridgeServer(int port, MappingResolver resolver, ThreadDispatcher dispatcher,
                        GameStateProvider stateProvider) {
        this(port, resolver, dispatcher, stateProvider, null);
    }

    public BridgeServer(int port, MappingResolver resolver, ThreadDispatcher dispatcher,
                        GameStateProvider stateProvider, ScreenshotProvider screenshotProvider) {
        super(new InetSocketAddress("127.0.0.1", port));
        this.resolver = resolver;
        this.refs = new ObjectRefStore();
        this.lua = new LuaRuntime(resolver, dispatcher, refs);
        this.serializer = new ResultSerializer(resolver, refs);
        this.stateProvider = stateProvider;
        this.screenshotProvider = screenshotProvider;
        setReuseAddr(true);
    }

    public LuaRuntime getLuaRuntime() { return lua; }

    /**
     * Tell the server where the game run directory is so it can surface log
     * paths in its status response. Call once, after construction, before
     * {@link #start()}.
     */
    public void setGameDir(Path gameDir) {
        this.gameDir = gameDir;
    }

    /**
     * Register the logger injection service. Called by the agent module
     * when it initializes.
     */
    public void setLoggerService(LoggerService service) {
        this.loggerService = service;
        LOG.info("[DebugBridge] Logger service registered: " + service.getClass().getSimpleName());
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        LOG.info("[DebugBridge] Client connected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        LOG.info("[DebugBridge] Client disconnected: " + reason);
        refs.clear();
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            BridgeRequest req = GSON.fromJson(message, BridgeRequest.class);
            BridgeResponse resp = handleRequest(req);
            conn.send(GSON.toJson(resp.toJson()));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[DebugBridge] Error handling message", e);
            try {
                BridgeResponse resp = BridgeResponse.error("unknown",
                    "Internal error: " + e.getMessage());
                conn.send(GSON.toJson(resp.toJson()));
            } catch (Exception e2) {
                // Connection may be dead
            }
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        LOG.log(Level.WARNING, "[DebugBridge] WebSocket error", ex);
    }

    @Override
    public void onStart() {
        LOG.info("[DebugBridge] Server started on port " + getPort());
    }

    private BridgeResponse handleRequest(BridgeRequest req) {
        try {
            return switch (req.type) {
                case "execute" -> handleExecute(req);
                case "search" -> handleSearch(req);
                case "snapshot" -> handleSnapshot(req);
                case "screenshot" -> handleScreenshot(req);
                case "runCommand" -> handleRunCommand(req);
                case "status" -> handleStatus(req);
                case "injectLogger" -> handleInjectLogger(req);
                case "cancelLogger" -> handleCancelLogger(req);
                case "listLoggers" -> handleListLoggers(req);
                default -> BridgeResponse.error(req.id, "Unknown request type: " + req.type);
            };
        } catch (Exception e) {
            return BridgeResponse.error(req.id,
                e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private BridgeResponse handleExecute(BridgeRequest req) {
        String code = req.payload.get("code").getAsString();
        LuaRuntime.ExecutionResult result = lua.execute(code);

        if (!result.isSuccess()) {
            return BridgeResponse.error(req.id, result.error);
        }

        JsonElement serialized = null;
        if (result.returnValue != null) {
            serialized = serializer.serialize(result.returnValue);
        }
        return BridgeResponse.success(req.id, serialized, result.output);
    }

    private BridgeResponse handleSearch(BridgeRequest req) {
        String pattern = req.payload.get("pattern").getAsString();
        String scope = req.payload.has("scope")
            ? req.payload.get("scope").getAsString() : "all";
        Pattern regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);

        JsonArray results = new JsonArray();
        int limit = 100;

        if (scope.equals("class") || scope.equals("all")) {
            for (String mojangClass : resolver.getAllClassNames()) {
                if (regex.matcher(mojangClass).find()) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("type", "class");
                    entry.addProperty("name", mojangClass);
                    results.add(entry);
                    if (results.size() >= limit) break;
                }
            }
        }

        if (results.size() < limit && (scope.equals("method") || scope.equals("all"))) {
            for (String className : resolver.getAllClassNames()) {
                for (String methodSig : resolver.getMethodSignatures(className)) {
                    if (regex.matcher(methodSig).find()) {
                        JsonObject entry = new JsonObject();
                        entry.addProperty("type", "method");
                        entry.addProperty("owner", className);
                        entry.addProperty("name", methodSig);
                        results.add(entry);
                        if (results.size() >= limit) break;
                    }
                }
                if (results.size() >= limit) break;
            }
        }

        if (results.size() < limit && (scope.equals("field") || scope.equals("all"))) {
            for (String className : resolver.getAllClassNames()) {
                for (String fieldName : resolver.getFieldNames(className)) {
                    if (regex.matcher(fieldName).find()) {
                        JsonObject entry = new JsonObject();
                        entry.addProperty("type", "field");
                        entry.addProperty("owner", className);
                        entry.addProperty("name", fieldName);
                        results.add(entry);
                        if (results.size() >= limit) break;
                    }
                }
                if (results.size() >= limit) break;
            }
        }

        return BridgeResponse.success(req.id, results, null);
    }

    private BridgeResponse handleScreenshot(BridgeRequest req) {
        if (screenshotProvider == null) {
            return BridgeResponse.error(req.id,
                "No screenshot provider configured for this Minecraft version.");
        }
        int downscale = 2;
        float quality = 0.75f;
        long timeoutMs = 5000;
        if (req.payload != null) {
            if (req.payload.has("downscale")) {
                downscale = Math.max(1, req.payload.get("downscale").getAsInt());
            }
            if (req.payload.has("quality")) {
                quality = req.payload.get("quality").getAsFloat();
            }
            if (req.payload.has("timeoutMs")) {
                timeoutMs = Math.max(100, req.payload.get("timeoutMs").getAsLong());
            }
        }
        try {
            ScreenshotProvider.Capture cap = screenshotProvider.capture(downscale, quality, timeoutMs);
            JsonObject result = new JsonObject();
            result.addProperty("path", cap.path);
            result.addProperty("width", cap.width);
            result.addProperty("height", cap.height);
            result.addProperty("sizeBytes", cap.sizeBytes);
            result.addProperty("mimeType", "image/jpeg");
            return BridgeResponse.success(req.id, result, null);
        } catch (Exception e) {
            return BridgeResponse.error(req.id,
                "Screenshot failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private BridgeResponse handleSnapshot(BridgeRequest req) {
        if (stateProvider == null) {
            return BridgeResponse.error(req.id,
                "No game state provider configured. Use mc_execute with Lua instead.");
        }
        try {
            JsonObject snapshot = stateProvider.captureSnapshot();
            return BridgeResponse.success(req.id, snapshot, null);
        } catch (Exception e) {
            return BridgeResponse.error(req.id,
                "Snapshot failed: " + e.getMessage());
        }
    }

    private BridgeResponse handleRunCommand(BridgeRequest req) {
        String command = req.payload.get("command").getAsString();
        // This needs to be implemented via the version-specific module
        // For now, execute via Lua
        String luaCode = String.format(
            "local mc = java.import('net.minecraft.client.Minecraft'):getInstance()\n" +
            "mc.player:connection():sendCommand('%s')\n" +
            "return 'Command sent: %s'",
            command.replace("'", "\\'"),
            command.replace("'", "\\'")
        );
        return handleExecute(new BridgeRequest(req.id, "execute",
            createPayload("code", luaCode)));
    }

    private BridgeResponse handleStatus(BridgeRequest req) {
        JsonObject status = new JsonObject();
        status.addProperty("version", resolver.getVersion());
        status.addProperty("mappingStatus", resolver.isObfuscated() ? "mojang" : "passthrough");
        status.addProperty("obfuscated", resolver.isObfuscated());
        status.addProperty("refs", refs.size());

        // Expose the game dir and log paths so a connecting client can read the
        // log via its own file-read tools. We always expose the path we *would*
        // write to, even if the file doesn't exist yet (a world-load crash
        // might create it between the status call and the Read). Where we can
        // check existence cheaply, we do, and add an explicit "exists" flag.
        Path dir = this.gameDir;
        if (dir != null) {
            Path logsDir = dir.resolve("logs");
            Path latest = logsDir.resolve("latest.log");
            Path debug = logsDir.resolve("debug.log");
            status.addProperty("gameDir", dir.toAbsolutePath().toString());
            status.addProperty("logsDir", logsDir.toAbsolutePath().toString());
            status.addProperty("latestLog", latest.toAbsolutePath().toString());
            status.addProperty("latestLogExists", Files.exists(latest));
            status.addProperty("debugLog", debug.toAbsolutePath().toString());
            status.addProperty("debugLogExists", Files.exists(debug));
        }

        return BridgeResponse.success(req.id, status, null);
    }

    private JsonObject createPayload(String key, String value) {
        JsonObject payload = new JsonObject();
        payload.addProperty(key, value);
        return payload;
    }

    // ==================== Logger Injection Handlers ====================

    private BridgeResponse handleInjectLogger(BridgeRequest req) {
        if (!loggerService.isAvailable()) {
            return BridgeResponse.error(req.id,
                "Logger injection not available. Start Minecraft with " +
                "-javaagent:debugbridge-agent.jar to enable runtime instrumentation.");
        }

        try {
            String method = req.payload.get("method").getAsString();
            int durationSeconds = req.payload.has("duration_seconds")
                ? req.payload.get("duration_seconds").getAsInt() : 60;
            String outputFile = req.payload.has("output_file") && !req.payload.get("output_file").isJsonNull()
                ? req.payload.get("output_file").getAsString() : null;
            boolean logArgs = !req.payload.has("log_args") || req.payload.get("log_args").getAsBoolean();
            boolean logReturn = req.payload.has("log_return") && req.payload.get("log_return").getAsBoolean();
            boolean logTiming = !req.payload.has("log_timing") || req.payload.get("log_timing").getAsBoolean();
            int argDepth = req.payload.has("arg_depth") ? req.payload.get("arg_depth").getAsInt() : 1;

            // Parse filter
            Map<String, Object> filter = null;
            if (req.payload.has("filter") && !req.payload.get("filter").isJsonNull()) {
                filter = parseFilter(req.payload.getAsJsonObject("filter"));
            }

            LoggerService.InstallResult result = loggerService.install(
                method, durationSeconds, outputFile,
                logArgs, logReturn, logTiming, argDepth, filter);

            if (!result.success()) {
                return BridgeResponse.error(req.id, result.error());
            }

            JsonObject response = new JsonObject();
            response.addProperty("logger_id", result.loggerId());
            response.addProperty("output_file", result.outputFile());
            if (result.message() != null) {
                response.addProperty("message", result.message());
            }
            return BridgeResponse.success(req.id, response, null);

        } catch (Exception e) {
            return BridgeResponse.error(req.id,
                "Failed to inject logger: " + e.getMessage());
        }
    }

    private Map<String, Object> parseFilter(JsonObject filterJson) {
        Map<String, Object> filter = new HashMap<>();
        filter.put("type", filterJson.get("type").getAsString());

        if (filterJson.has("interval_ms")) {
            filter.put("interval_ms", filterJson.get("interval_ms").getAsLong());
        }
        if (filterJson.has("index")) {
            filter.put("index", filterJson.get("index").getAsInt());
        }
        if (filterJson.has("substring")) {
            filter.put("substring", filterJson.get("substring").getAsString());
        }
        if (filterJson.has("class_name")) {
            filter.put("class_name", filterJson.get("class_name").getAsString());
        }
        if (filterJson.has("n")) {
            filter.put("n", filterJson.get("n").getAsInt());
        }

        return filter;
    }

    private BridgeResponse handleCancelLogger(BridgeRequest req) {
        if (!loggerService.isAvailable()) {
            return BridgeResponse.error(req.id, "Logger service not available.");
        }

        long id = req.payload.get("id").getAsLong();
        boolean cancelled = loggerService.cancel(id);

        JsonObject response = new JsonObject();
        response.addProperty("cancelled", cancelled);
        return BridgeResponse.success(req.id, response, null);
    }

    private BridgeResponse handleListLoggers(BridgeRequest req) {
        JsonObject response = new JsonObject();

        JsonArray loggersArray = new JsonArray();
        for (LoggerService.LoggerInfo info : loggerService.listActive()) {
            JsonObject logger = new JsonObject();
            logger.addProperty("id", info.id());
            logger.addProperty("method", info.method());
            logger.addProperty("remaining_ms", info.remainingMs());
            logger.addProperty("has_filter", info.hasFilter());
            loggersArray.add(logger);
        }
        response.add("loggers", loggersArray);

        JsonArray injectedArray = new JsonArray();
        for (String method : loggerService.listInjectedMethods()) {
            injectedArray.add(method);
        }
        response.add("injected_methods", injectedArray);

        response.addProperty("available", loggerService.isAvailable());

        return BridgeResponse.success(req.id, response, null);
    }
}
