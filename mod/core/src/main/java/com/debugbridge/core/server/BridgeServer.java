package com.debugbridge.core.server;

import com.debugbridge.core.lua.LuaRuntime;
import com.debugbridge.core.lua.ThreadDispatcher;
import com.debugbridge.core.mapping.MappingResolver;
import com.debugbridge.core.protocol.BridgeRequest;
import com.debugbridge.core.protocol.BridgeResponse;
import com.debugbridge.core.refs.ObjectRefStore;
import com.debugbridge.core.snapshot.GameStateProvider;
import com.google.gson.*;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
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

    public BridgeServer(int port, MappingResolver resolver, ThreadDispatcher dispatcher) {
        this(port, resolver, dispatcher, null);
    }

    public BridgeServer(int port, MappingResolver resolver, ThreadDispatcher dispatcher,
                        GameStateProvider stateProvider) {
        super(new InetSocketAddress("127.0.0.1", port));
        this.resolver = resolver;
        this.refs = new ObjectRefStore();
        this.lua = new LuaRuntime(resolver, dispatcher, refs);
        this.serializer = new ResultSerializer(resolver, refs);
        this.stateProvider = stateProvider;
        setReuseAddr(true);
    }

    public LuaRuntime getLuaRuntime() { return lua; }

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
                case "runCommand" -> handleRunCommand(req);
                case "status" -> handleStatus(req);
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
        return BridgeResponse.success(req.id, status, null);
    }

    private JsonObject createPayload(String key, String value) {
        JsonObject payload = new JsonObject();
        payload.addProperty(key, value);
        return payload;
    }
}
