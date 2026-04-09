package com.debugbridge.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Reads config from .minecraft/config/debugbridge.json (or a provided path).
 * Falls back to sensible defaults if the file doesn't exist.
 */
public class BridgeConfig {
    private static final Logger LOG = Logger.getLogger("DebugBridge");

    public int port = 9876;
    public long timeoutMs = 5000;
    public int maxResults = 100;
    public long luaMaxExecutionTimeMs = 5000;

    /** Load config from a directory (e.g. .minecraft/config/). */
    public static BridgeConfig load(Path configDir) {
        Path file = configDir.resolve("debugbridge.json");
        if (!Files.exists(file)) {
            LOG.info("[DebugBridge] No config file at " + file + ", using defaults (port 9876)");
            return new BridgeConfig();
        }
        try {
            String json = Files.readString(file);
            JsonObject obj = new Gson().fromJson(json, JsonObject.class);
            BridgeConfig config = new BridgeConfig();
            if (obj.has("port")) config.port = obj.get("port").getAsInt();
            if (obj.has("timeout_ms")) config.timeoutMs = obj.get("timeout_ms").getAsLong();
            if (obj.has("max_results")) config.maxResults = obj.get("max_results").getAsInt();
            if (obj.has("lua")) {
                JsonObject lua = obj.getAsJsonObject("lua");
                if (lua.has("max_execution_time_ms"))
                    config.luaMaxExecutionTimeMs = lua.get("max_execution_time_ms").getAsLong();
            }
            LOG.info("[DebugBridge] Config loaded from " + file + " (port " + config.port + ")");
            return config;
        } catch (IOException e) {
            LOG.warning("[DebugBridge] Failed to read config: " + e.getMessage() + ", using defaults");
            return new BridgeConfig();
        }
    }
}
