package com.debugbridge.fabric119;

import com.debugbridge.core.BridgeConfig;
import com.debugbridge.core.block.NearbyBlocksProvider;
import com.debugbridge.core.entity.NearbyEntitiesProvider;
import com.debugbridge.core.lua.ThreadDispatcher;
import com.debugbridge.core.mapping.*;
import com.debugbridge.core.screenshot.ScreenshotProvider;
import com.debugbridge.core.server.BridgeServer;
import com.debugbridge.core.snapshot.GameStateProvider;
import com.debugbridge.core.texture.ItemTextureProvider;
import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class DebugBridgeMod implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("DebugBridge");
    private static final String MC_VERSION = "1.19";
    private static final int PORT_RANGE_START = 9876;
    private static final int PORT_RANGE_END = 9886;
    private static DebugBridgeMod INSTANCE;
    private final AtomicBoolean warningShown = new AtomicBoolean(false);
    private final AtomicBoolean serverStarted = new AtomicBoolean(false);
    private BridgeConfig config;
    private BridgeServer server;
    private boolean needsWarning = false;
    private String startupError = null;
    private String startupInfo = null;  // Info message (e.g., port changed)

    /**
     * Called by MinecraftClientMixin on each client tick.
     */
    public static void onClientTick(Minecraft mc) {
        if (INSTANCE != null) {
            INSTANCE.handleTick(mc);
        }
    }

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        LOG.info("[DebugBridge] Initializing for Minecraft {}...", MC_VERSION);

        Path configDir = FabricLoader.getInstance().getConfigDir();
        config = BridgeConfig.load(configDir);

        if (config.developerModeAccepted) {
            // Already accepted, start server immediately
            startServer();
        } else {
            // Need to show warning screen - will be triggered by mixin tick
            LOG.info("[DebugBridge] Developer mode not yet accepted, will show warning screen");
            needsWarning = true;
        }
    }

    private void handleTick(Minecraft mc) {
        // Show startup messages when player is available
        if (startupError != null && mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("[DebugBridge] " + startupError).withStyle(s -> s.withColor(0xFF5555)),
                    false);
            startupError = null;
        }
        if (startupInfo != null && mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("[DebugBridge] " + startupInfo).withStyle(s -> s.withColor(0x55FF55)),
                    false);
            startupInfo = null;
        }

        if (!needsWarning) return;

        // Only show once, and only when no screen is open (game is ready)
        if (!warningShown.get() && mc.screen == null && mc.getOverlay() == null) {
            warningShown.set(true);
            mc.setScreen(new DeveloperWarningScreen(config, accepted -> {
                mc.setScreen(null);
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
            return; // Already started
        }

        MappingResolver resolver = buildResolver();

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

        GameStateProvider stateProvider = new Minecraft119StateProvider();
        ScreenshotProvider screenshotProvider = new Minecraft119ScreenshotProvider();
        NearbyEntitiesProvider entitiesProvider = new Minecraft119NearbyEntitiesProvider();
        NearbyBlocksProvider blocksProvider = new Minecraft119NearbyBlocksProvider();
        ItemTextureProvider textureProvider = new Minecraft119ItemTextureProvider();
        Minecraft119LookedAtEntityProvider lookedAtProvider = new Minecraft119LookedAtEntityProvider();

        // Find available port and start server
        int actualPort = startServerOnAvailablePort(config.port, resolver, dispatcher, stateProvider, screenshotProvider);

        if (actualPort == -1) {
            String msg = "Could not bind to any port in range " + PORT_RANGE_START + "-" + PORT_RANGE_END;
            LOG.error("[DebugBridge] {}", msg);
            startupError = msg;
        } else {
            server.setEntitiesProvider(entitiesProvider);
            server.setBlocksProvider(blocksProvider);
            server.setTextureProvider(textureProvider);
            server.setLookedAtEntityProvider(lookedAtProvider);
            server.setChatHistoryProvider(new Minecraft119ChatHistoryProvider());
            server.setScreenInspectProvider(new Minecraft119ScreenInspectProvider());
            server.setLoggerInjectionEnabled(config.loggerInjectionEnabled);
            server.setRunCommandEnabled(config.runCommandEnabled);

            if (actualPort != config.port) {
                startupInfo = "Server started on port " + actualPort + " (default " + config.port + " was in use)";
            }
            LOG.info("[DebugBridge] Server started on port {}", actualPort);
        }
    }

    /**
     * Try to start server on preferred port, scanning range if needed.
     * Returns actual port used, or -1 if all ports occupied.
     */
    private int startServerOnAvailablePort(int preferredPort, MappingResolver resolver,
                                           ThreadDispatcher dispatcher, GameStateProvider stateProvider,
                                           ScreenshotProvider screenshotProvider) {
        int startPort = Math.max(PORT_RANGE_START, Math.min(preferredPort, PORT_RANGE_END));

        // First pass: preferred port -> end of range
        for (int port = startPort; port <= PORT_RANGE_END; port++) {
            if (tryStartOnPort(port, resolver, dispatcher, stateProvider, screenshotProvider)) {
                return port;
            }
        }

        // Second pass (wraparound): start of range -> preferred port
        for (int port = PORT_RANGE_START; port < startPort; port++) {
            if (tryStartOnPort(port, resolver, dispatcher, stateProvider, screenshotProvider)) {
                return port;
            }
        }

        return -1;
    }

    private boolean tryStartOnPort(int port, MappingResolver resolver, ThreadDispatcher dispatcher,
                                   GameStateProvider stateProvider, ScreenshotProvider screenshotProvider) {
        LOG.info("[DebugBridge] Checking if port {} is available...", port);
        if (!isPortAvailable(port)) {
            LOG.info("[DebugBridge] Port {} is not available, skipping", port);
            return false;
        }
        LOG.info("[DebugBridge] Port {} appears available, starting server...", port);

        try {
            server = new BridgeServer(port, resolver, dispatcher, stateProvider, screenshotProvider);
            server.setReuseAddr(true);
            server.setGameDir(FabricLoader.getInstance().getGameDir());
            server.start();
            LOG.info("[DebugBridge] Server.start() called on port {}", port);
            return true;
        } catch (Exception e) {
            LOG.error("[DebugBridge] Failed to start server on port {}", port, e);
            return false;
        }
    }

    private boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);  // Must be set BEFORE bind
            socket.bind(new InetSocketAddress("127.0.0.1", port));  // Same address as WebSocketServer
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private MappingResolver buildResolver() {
        try {
            MappingCache cache = new MappingCache();
            String proguardContent;

            if (cache.has(MC_VERSION)) {
                LOG.info("[DebugBridge] Loading cached {} mappings...", MC_VERSION);
                proguardContent = cache.load(MC_VERSION);
            } else {
                LOG.info("[DebugBridge] Downloading {} mappings from Mojang...", MC_VERSION);
                MappingDownloader downloader = new MappingDownloader();
                proguardContent = downloader.download(MC_VERSION);
                cache.save(MC_VERSION, proguardContent);
                LOG.info("[DebugBridge] Mappings downloaded and cached.");
            }

            ParsedMappings mappings = ProGuardParser.parse(proguardContent);
            LOG.info("[DebugBridge] Parsed {} classes from mappings.", mappings.classes.size());
            return new FabricMojangResolver(MC_VERSION, mappings);
        } catch (Exception e) {
            LOG.error("[DebugBridge] Failed to load mappings, falling back to passthrough", e);
            return new com.debugbridge.core.mapping.PassthroughResolver(MC_VERSION);
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
                playerObj.addProperty("yaw", player.getYRot());
                playerObj.addProperty("pitch", player.getXRot());
                playerObj.addProperty("hotbarSlot", player.getInventory().selected);
                playerObj.addProperty("health", player.getHealth());
                playerObj.addProperty("maxHealth", player.getMaxHealth());
                playerObj.addProperty("food", player.getFoodData().getFoodLevel());
                playerObj.addProperty("saturation", player.getFoodData().getSaturationLevel());
                playerObj.addProperty("dimension",
                        player.level.dimension().location().toString());
                playerObj.addProperty("biome", ""); // Would need world access
                Vec3 vel = player.getDeltaMovement();
                JsonObject velObj = new JsonObject();
                velObj.addProperty("x", vel.x);
                velObj.addProperty("y", vel.y);
                velObj.addProperty("z", vel.z);
                playerObj.add("velocity", velObj);
                Vec3 look = player.getLookAngle();
                JsonObject lookObj = new JsonObject();
                lookObj.addProperty("x", look.x);
                lookObj.addProperty("y", look.y);
                lookObj.addProperty("z", look.z);
                playerObj.add("look", lookObj);
                Entity vehicle = player.getVehicle();
                if (vehicle != null) {
                    JsonObject vObj = new JsonObject();
                    vObj.addProperty("entityId", vehicle.getId());
                    vObj.addProperty("type", vehicle.getClass().getName());
                    playerObj.add("vehicle", vObj);
                }
                snap.add("player", playerObj);
            } else {
                snap.addProperty("player", "not in world");
            }

            HitResult hit = mc.hitResult;
            if (hit != null && hit.getType() != HitResult.Type.MISS) {
                JsonObject target = new JsonObject();
                target.addProperty("type", hit.getType().name().toLowerCase());
                if (hit instanceof BlockHitResult bhr) {
                    BlockPos pos = bhr.getBlockPos();
                    target.addProperty("x", pos.getX());
                    target.addProperty("y", pos.getY());
                    target.addProperty("z", pos.getZ());
                    target.addProperty("face", bhr.getDirection().name().toLowerCase());
                } else if (hit instanceof EntityHitResult ehr) {
                    target.addProperty("entityId", ehr.getEntity().getId());
                    target.addProperty("entityType", ehr.getEntity().getClass().getName());
                }
                snap.add("target", target);
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
