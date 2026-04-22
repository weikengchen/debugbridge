package com.debugbridge.fabric262;

import com.debugbridge.core.BridgeConfig;
import com.debugbridge.core.lua.ThreadDispatcher;
import com.debugbridge.core.mapping.PassthroughResolver;
import com.debugbridge.core.screenshot.ScreenshotProvider;
import com.debugbridge.core.server.BridgeServer;
import com.debugbridge.core.snapshot.GameStateProvider;
import com.debugbridge.core.texture.ItemTextureProvider;
import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.network.chat.Component;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class DebugBridgeMod implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("DebugBridge");
    private static final String MC_VERSION = "26.2-snapshot-4";

    private static final int PORT_RANGE_START = 9876;
    private static final int PORT_RANGE_END = 9886;

    private static DebugBridgeMod INSTANCE;

    private BridgeConfig config;
    private BridgeServer server;
    private final AtomicBoolean warningShown = new AtomicBoolean(false);
    private final AtomicBoolean serverStarted = new AtomicBoolean(false);
    private boolean needsWarning = false;
    private String startupError = null;
    private String startupInfo = null;

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        LOG.info("[DebugBridge] Initializing for Minecraft {}...", MC_VERSION);

        Path configDir = FabricLoader.getInstance().getConfigDir();
        config = BridgeConfig.load(configDir);

        if (config.developerModeAccepted) {
            startServer();
        } else {
            LOG.info("[DebugBridge] Developer mode not yet accepted, will show warning screen");
            needsWarning = true;
        }
    }

    public static void onClientTick(Minecraft mc) {
        if (INSTANCE != null) {
            INSTANCE.handleTick(mc);
        }
    }

    private void handleTick(Minecraft mc) {
        if (startupError != null && mc.player != null) {
            mc.player.sendSystemMessage(
                Component.literal("[DebugBridge] " + startupError).withStyle(s -> s.withColor(0xFF5555)));
            startupError = null;
        }
        if (startupInfo != null && mc.player != null) {
            mc.player.sendSystemMessage(
                Component.literal("[DebugBridge] " + startupInfo).withStyle(s -> s.withColor(0x55FF55)));
            startupInfo = null;
        }

        if (!needsWarning) {
            return;
        }

        if (!warningShown.get() && mc.gui.screen() == null && mc.gui.overlay() == null) {
            warningShown.set(true);
            mc.gui.setScreen(new DeveloperWarningScreen(config, accepted -> {
                mc.gui.setScreen(null);
                if (accepted) {
                    LOG.info("[DebugBridge] Developer mode accepted by user");
                    startServer();
                } else {
                    LOG.info("[DebugBridge] Developer mode declined, mod disabled");
                }
                needsWarning = false;
            }));
        }
    }

    private void startServer() {
        if (serverStarted.getAndSet(true)) {
            return;
        }

        PassthroughResolver resolver = new PassthroughResolver(MC_VERSION);
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

        GameStateProvider stateProvider = new Minecraft262StateProvider();
        ScreenshotProvider screenshotProvider = new Minecraft262ScreenshotProvider();
        ItemTextureProvider textureProvider = new Minecraft262ItemTextureProvider();
        Minecraft262NearbyEntitiesProvider entitiesProvider = new Minecraft262NearbyEntitiesProvider();
        Minecraft262LookedAtEntityProvider lookedAtProvider = new Minecraft262LookedAtEntityProvider();

        int actualPort = startServerOnAvailablePort(
            config.port, resolver, dispatcher, stateProvider, screenshotProvider);

        if (actualPort == -1) {
            String msg = "Could not bind to any port in range " + PORT_RANGE_START + "-" + PORT_RANGE_END;
            LOG.error("[DebugBridge] {}", msg);
            startupError = msg;
            return;
        }

        server.setTextureProvider(textureProvider);
        server.setEntitiesProvider(entitiesProvider);
        server.setLookedAtEntityProvider(lookedAtProvider);

        if (actualPort != config.port) {
            startupInfo = "Server started on port " + actualPort + " (default " + config.port + " was in use)";
        }
        LOG.info("[DebugBridge] Server started on port {}", actualPort);
    }

    private int startServerOnAvailablePort(int preferredPort, PassthroughResolver resolver,
                                           ThreadDispatcher dispatcher, GameStateProvider stateProvider,
                                           ScreenshotProvider screenshotProvider) {
        int startPort = Math.max(PORT_RANGE_START, Math.min(PORT_RANGE_END, preferredPort));

        for (int port = startPort; port <= PORT_RANGE_END; port++) {
            if (tryStartOnPort(port, resolver, dispatcher, stateProvider, screenshotProvider)) {
                return port;
            }
        }

        for (int port = PORT_RANGE_START; port < startPort; port++) {
            if (tryStartOnPort(port, resolver, dispatcher, stateProvider, screenshotProvider)) {
                return port;
            }
        }

        return -1;
    }

    private boolean tryStartOnPort(int port, PassthroughResolver resolver, ThreadDispatcher dispatcher,
                                   GameStateProvider stateProvider, ScreenshotProvider screenshotProvider) {
        if (!isPortAvailable(port)) {
            LOG.info("[DebugBridge] Port {} is not available", port);
            return false;
        }

        try {
            server = new BridgeServer(port, resolver, dispatcher, stateProvider, screenshotProvider);
            server.setReuseAddr(true);
            server.setGameDir(FabricLoader.getInstance().getGameDir());
            server.start();
            return true;
        } catch (Exception e) {
            LOG.error("[DebugBridge] Failed to start server on port {}", port, e);
            return false;
        }
    }

    private boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static class Minecraft262StateProvider implements GameStateProvider {
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
                playerObj.addProperty("dimension", player.level().dimension().identifier().toString());
                playerObj.addProperty("biome", "");
                snap.add("player", playerObj);
            } else {
                snap.addProperty("player", "not in world");
            }

            if (mc.level != null) {
                JsonObject world = new JsonObject();
                world.addProperty("dayTime", mc.level.getOverworldClockTime());
                world.addProperty("isRaining", mc.level.isRaining());
                world.addProperty("isThundering", mc.level.isThundering());
                snap.add("world", world);
            }

            snap.addProperty("fps", mc.getFps());
            snap.addProperty("version", MC_VERSION);

            return snap;
        }
    }
}
