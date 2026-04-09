package com.debugbridge.fabric261;

import com.debugbridge.core.BridgeConfig;
import com.debugbridge.core.lua.ThreadDispatcher;
import com.debugbridge.core.mapping.PassthroughResolver;
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
        LOG.info("[DebugBridge] Initializing for Minecraft 26.1 (unobfuscated)...");

        Path configDir = FabricLoader.getInstance().getConfigDir();
        BridgeConfig config = BridgeConfig.load(configDir);

        MappingResolver resolver = new PassthroughResolver("26.1");

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

        GameStateProvider stateProvider = new Minecraft261StateProvider();

        BridgeServer server = new BridgeServer(config.port, resolver, dispatcher, stateProvider);
        server.start();
        LOG.info("[DebugBridge] Server started on port {}", config.port);
    }

    private static class Minecraft261StateProvider implements GameStateProvider {
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
                    player.level().dimension().location().toString());
                snap.add("player", playerObj);
            } else {
                snap.addProperty("player", "not in world");
            }

            if (mc.level != null) {
                JsonObject world = new JsonObject();
                world.addProperty("dayTime", mc.level.getDayTime());
                world.addProperty("isRaining", mc.level.isRaining());
                world.addProperty("isThundering", mc.level.isThundering());
                snap.add("world", world);
            }

            snap.addProperty("fps", mc.getFps());
            snap.addProperty("version", "26.1");

            return snap;
        }
    }
}
