package com.debugbridge.fabric119;

import com.debugbridge.core.BridgeConfig;
import com.debugbridge.core.lua.ThreadDispatcher;
import com.debugbridge.core.mapping.MappingCache;
import com.debugbridge.core.mapping.MappingDownloader;
import com.debugbridge.core.mapping.ProGuardParser;
import com.debugbridge.core.mapping.ParsedMappings;
import com.debugbridge.core.mapping.MappingResolver;
import com.debugbridge.core.server.BridgeServer;
import com.debugbridge.core.snapshot.GameStateProvider;
import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class DebugBridgeMod implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("DebugBridge");

    @Override
    public void onInitializeClient() {
        LOG.info("[DebugBridge] Initializing for Minecraft 1.19...");

        // Load config from .minecraft/config/debugbridge.json
        Path configDir = FabricLoader.getInstance().getConfigDir();
        BridgeConfig config = BridgeConfig.load(configDir);

        // Build mapping resolver
        MappingResolver resolver = buildResolver();

        // Thread dispatcher that posts to MC's main thread
        Minecraft mc = Minecraft.getInstance();
        ThreadDispatcher dispatcher = new ThreadDispatcher() {
            @Override
            public <T> T executeOnGameThread(Callable<T> task, long timeout) throws Exception {
                CompletableFuture<T> future = new CompletableFuture<>();
                mc.execute(() -> {
                    try {
                        future.complete(task.call());
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                });
                return future.get(timeout, TimeUnit.MILLISECONDS);
            }
        };

        // Game state provider
        GameStateProvider stateProvider = new Minecraft119StateProvider();

        // Start the bridge server
        BridgeServer server = new BridgeServer(config.port, resolver, dispatcher, stateProvider);
        server.start();
        LOG.info("[DebugBridge] Server started on port {}", config.port);
    }

    private MappingResolver buildResolver() {
        try {
            MappingCache cache = new MappingCache();
            String proguardContent;

            if (cache.has("1.19")) {
                LOG.info("[DebugBridge] Loading cached 1.19 mappings...");
                proguardContent = cache.load("1.19");
            } else {
                LOG.info("[DebugBridge] Downloading 1.19 mappings from Mojang...");
                MappingDownloader downloader = new MappingDownloader();
                proguardContent = downloader.download("1.19");
                cache.save("1.19", proguardContent);
                LOG.info("[DebugBridge] Mappings downloaded and cached.");
            }

            ParsedMappings mappings = ProGuardParser.parse(proguardContent);
            LOG.info("[DebugBridge] Parsed {} classes from mappings.", mappings.classes.size());
            return new FabricMojangResolver("1.19", mappings);
        } catch (Exception e) {
            LOG.error("[DebugBridge] Failed to load mappings, falling back to passthrough", e);
            return new com.debugbridge.core.mapping.PassthroughResolver("1.19");
        }
    }

    /**
     * Captures game state for the snapshot endpoint.
     */
    private static class Minecraft119StateProvider implements GameStateProvider {
        @Override
        public JsonObject captureSnapshot() {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            JsonObject snap = new JsonObject();

            if (player != null) {
                JsonObject playerObj = new JsonObject();
                playerObj.addProperty("name", player.getName().getString());
                playerObj.addProperty("x", player.getX());
                playerObj.addProperty("y", player.getY());
                playerObj.addProperty("z", player.getZ());
                playerObj.addProperty("health", player.getHealth());
                playerObj.addProperty("maxHealth", player.getMaxHealth());
                playerObj.addProperty("food", player.getFoodData().getFoodLevel());
                playerObj.addProperty("saturation", player.getFoodData().getSaturationLevel());
                playerObj.addProperty("dimension",
                    player.level.dimension().location().toString());
                playerObj.addProperty("biome", ""); // Would need world access
                snap.add("player", playerObj);
            } else {
                snap.addProperty("player", "not in world");
            }

            // World info
            if (mc.level != null) {
                JsonObject world = new JsonObject();
                world.addProperty("dayTime", mc.level.getDayTime());
                world.addProperty("isRaining", mc.level.isRaining());
                world.addProperty("isThundering", mc.level.isThundering());
                snap.add("world", world);
            }

            snap.addProperty("fps", mc.fpsString);
            snap.addProperty("version", "1.19");

            return snap;
        }
    }
}
