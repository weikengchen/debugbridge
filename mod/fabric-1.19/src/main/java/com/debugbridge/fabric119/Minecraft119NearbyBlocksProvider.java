package com.debugbridge.fabric119;

import com.debugbridge.core.block.NearbyBlocksProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Native nearby-blocks query for Minecraft 1.19. Walks loaded chunks within
 * range, collects block entities (signs, chests, banners, etc.), and returns
 * type-specific summary fields where applicable.
 */
public class Minecraft119NearbyBlocksProvider implements NearbyBlocksProvider {

    @Override
    public JsonArray getNearbyBlocks(double range, int limit) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture<JsonArray> future = new CompletableFuture<>();

        mc.execute(() -> {
            try {
                if (mc.player == null || mc.level == null) {
                    future.complete(new JsonArray());
                    return;
                }

                double px = mc.player.getX();
                double py = mc.player.getY();
                double pz = mc.player.getZ();
                double rangeSq = range * range;

                int chunkRadius = (int) Math.ceil(range / 16.0);
                int playerChunkX = (int) Math.floor(px) >> 4;
                int playerChunkZ = (int) Math.floor(pz) >> 4;

                List<Entry> entries = new ArrayList<>();
                for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                    for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                        LevelChunk chunk;
                        try {
                            chunk = mc.level.getChunk(playerChunkX + dx, playerChunkZ + dz);
                        } catch (Exception e) {
                            continue;
                        }
                        if (chunk == null) continue;

                        for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                            BlockPos pos = e.getKey();
                            double bx = pos.getX() + 0.5;
                            double by = pos.getY() + 0.5;
                            double bz = pos.getZ() + 0.5;
                            double distSq = (bx - px) * (bx - px)
                                          + (by - py) * (by - py)
                                          + (bz - pz) * (bz - pz);
                            if (distSq <= rangeSq) {
                                entries.add(new Entry(pos, e.getValue(), Math.sqrt(distSq)));
                            }
                        }
                    }
                }

                entries.sort(Comparator.comparingDouble(en -> en.distance));

                JsonArray arr = new JsonArray();
                int count = 0;
                for (Entry en : entries) {
                    if (count >= limit) break;
                    JsonObject obj = new JsonObject();
                    obj.addProperty("x", en.pos.getX());
                    obj.addProperty("y", en.pos.getY());
                    obj.addProperty("z", en.pos.getZ());
                    obj.addProperty("distance", Math.round(en.distance * 10.0) / 10.0);
                    obj.addProperty("type", en.blockEntity.getClass().getName());

                    var blockKey = Registry.BLOCK.getKey(en.blockEntity.getBlockState().getBlock());
                    obj.addProperty("blockId", blockKey.toString());

                    // Cheap preview field for the list view: first sign line, container
                    // size, etc. Keeps list rows informative without full detail fetches.
                    String preview = previewFor(en.blockEntity);
                    if (preview != null) obj.addProperty("preview", preview);

                    arr.add(obj);
                    count++;
                }
                future.complete(arr);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future.get(5, TimeUnit.SECONDS);
    }

    @Override
    public JsonObject getBlockDetails(int x, int y, int z) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        mc.execute(() -> {
            try {
                if (mc.level == null) {
                    future.complete(null);
                    return;
                }
                BlockPos pos = new BlockPos(x, y, z);
                BlockEntity be = mc.level.getBlockEntity(pos);
                if (be == null) {
                    future.complete(null);
                    return;
                }

                JsonObject obj = new JsonObject();
                obj.addProperty("x", x);
                obj.addProperty("y", y);
                obj.addProperty("z", z);
                obj.addProperty("type", be.getClass().getName());
                var blockKey = Registry.BLOCK.getKey(be.getBlockState().getBlock());
                obj.addProperty("blockId", blockKey.toString());

                if (be instanceof SignBlockEntity sign) {
                    JsonArray lines = new JsonArray();
                    for (int i = 0; i < 4; i++) {
                        var msg = sign.getMessage(i, false);
                        lines.add(msg == null ? "" : msg.getString());
                    }
                    obj.add("signLines", lines);
                }

                if (be instanceof Container container) {
                    JsonArray items = new JsonArray();
                    int size = container.getContainerSize();
                    for (int i = 0; i < size; i++) {
                        ItemStack stack = container.getItem(i);
                        if (stack == null || stack.isEmpty()) continue;
                        JsonObject item = new JsonObject();
                        item.addProperty("slot", i);
                        item.addProperty("itemId", stack.getItem().getDescriptionId());
                        item.addProperty("count", stack.getCount());
                        if (stack.hasCustomHoverName()) {
                            item.addProperty("name", stack.getHoverName().getString());
                        }
                        if (stack.getMaxDamage() > 0) {
                            item.addProperty("damage", stack.getDamageValue());
                            item.addProperty("maxDamage", stack.getMaxDamage());
                        }
                        items.add(item);
                    }
                    obj.add("items", items);
                    obj.addProperty("containerSize", size);
                }

                future.complete(obj);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future.get(5, TimeUnit.SECONDS);
    }

    private static String previewFor(BlockEntity be) {
        if (be instanceof SignBlockEntity sign) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                var msg = sign.getMessage(i, false);
                String s = msg == null ? "" : msg.getString();
                if (!s.isEmpty()) {
                    if (sb.length() > 0) sb.append(" / ");
                    sb.append(s);
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        }
        if (be instanceof Container container) {
            int filled = 0;
            int size = container.getContainerSize();
            for (int i = 0; i < size; i++) {
                ItemStack s = container.getItem(i);
                if (s != null && !s.isEmpty()) filled++;
            }
            return filled + " / " + size;
        }
        return null;
    }

    private record Entry(BlockPos pos, BlockEntity blockEntity, double distance) {}
}
