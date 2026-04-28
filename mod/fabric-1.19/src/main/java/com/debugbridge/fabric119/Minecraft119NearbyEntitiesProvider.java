package com.debugbridge.fabric119;

import com.debugbridge.core.entity.NearbyEntitiesProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Native nearby-entities query for Minecraft 1.19.
 */
public class Minecraft119NearbyEntitiesProvider implements NearbyEntitiesProvider {

    @Override
    public JsonArray getNearbyEntities(double range, int limit) throws Exception {
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

                List<EntityEntry> entries = new ArrayList<>();
                for (Entity entity : mc.level.entitiesForRendering()) {
                    if (entity == mc.player) continue;

                    double dx = entity.getX() - px;
                    double dy = entity.getY() - py;
                    double dz = entity.getZ() - pz;
                    double distSq = dx * dx + dy * dy + dz * dz;

                    if (distSq <= rangeSq) {
                        entries.add(new EntityEntry(entity, Math.sqrt(distSq)));
                    }
                }

                entries.sort(Comparator.comparingDouble(e -> e.distance));

                JsonArray arr = new JsonArray();
                int count = 0;
                for (EntityEntry entry : entries) {
                    if (count >= limit) break;
                    Entity entity = entry.entity;

                    JsonObject obj = new JsonObject();
                    obj.addProperty("id", entity.getId());
                    obj.addProperty("type", entity.getClass().getName());
                    obj.addProperty("distance", Math.round(entry.distance * 10.0) / 10.0);
                    obj.addProperty("x", Math.round(entity.getX() * 10.0) / 10.0);
                    obj.addProperty("y", Math.round(entity.getY() * 10.0) / 10.0);
                    obj.addProperty("z", Math.round(entity.getZ() * 10.0) / 10.0);

                    var customName = entity.getCustomName();
                    if (customName != null) {
                        obj.addProperty("customName", customName.getString());
                    }

                    var typeKey = entity.getType().getDescriptionId();
                    if (typeKey != null) {
                        obj.addProperty("typeId", typeKey);
                    }

                    // Primary equipment / framed item for thumbnail rendering
                    JsonObject primary = null;
                    if (entity instanceof LivingEntity living) {
                        primary = pickPrimaryEquipment(living);
                    } else if (entity instanceof ItemFrame frame) {
                        primary = buildPrimary("FRAME", frame.getItem());
                    }
                    if (primary != null) {
                        obj.add("primaryEquipment", primary);
                    }

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
    public JsonObject getEntityDetails(int entityId) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        mc.execute(() -> {
            try {
                if (mc.player == null || mc.level == null) {
                    future.complete(null);
                    return;
                }

                Entity target = null;
                for (Entity entity : mc.level.entitiesForRendering()) {
                    if (entity.getId() == entityId) {
                        target = entity;
                        break;
                    }
                }

                if (target == null) {
                    future.complete(null);
                    return;
                }

                JsonObject obj = new JsonObject();
                obj.addProperty("entityId", target.getId());
                obj.addProperty("type", target.getClass().getName());

                var customName = target.getCustomName();
                if (customName != null) {
                    obj.addProperty("customName", customName.getString());
                }

                obj.addProperty("x", target.getX());
                obj.addProperty("y", target.getY());
                obj.addProperty("z", target.getZ());

                obj.addProperty("distance",
                    Math.round(target.distanceTo(mc.player) * 10.0) / 10.0);

                if (target instanceof ItemFrame frame) {
                    ItemStack framed = frame.getItem();
                    if (framed != null && !framed.isEmpty()) {
                        JsonObject item = new JsonObject();
                        item.addProperty("itemId", framed.getItem().getDescriptionId());
                        item.addProperty("count", framed.getCount());
                        if (framed.getMaxDamage() > 0) {
                            item.addProperty("damage", framed.getDamageValue());
                            item.addProperty("maxDamage", framed.getMaxDamage());
                        }
                        var hoverName = framed.getHoverName();
                        if (hoverName != null) {
                            item.addProperty("name", hoverName.getString());
                        }
                        obj.add("frameItem", item);
                    }
                }

                if (target instanceof LivingEntity living) {
                    obj.addProperty("health", Math.round(living.getHealth() * 10.0) / 10.0);
                    obj.addProperty("maxHealth", Math.round(living.getMaxHealth() * 10.0) / 10.0);
                    obj.addProperty("armor", living.getArmorValue());

                    JsonObject equipment = new JsonObject();
                    addEquipment(equipment, "MAINHAND", living, EquipmentSlot.MAINHAND);
                    addEquipment(equipment, "OFFHAND", living, EquipmentSlot.OFFHAND);
                    addEquipment(equipment, "HEAD", living, EquipmentSlot.HEAD);
                    addEquipment(equipment, "CHEST", living, EquipmentSlot.CHEST);
                    addEquipment(equipment, "LEGS", living, EquipmentSlot.LEGS);
                    addEquipment(equipment, "FEET", living, EquipmentSlot.FEET);
                    if (equipment.size() > 0) {
                        obj.add("equipment", equipment);
                    }
                }

                obj.addProperty("isOnFire", target.isOnFire());
                obj.addProperty("isSprinting", target.isSprinting());

                Entity vehicle = target.getVehicle();
                if (vehicle != null) {
                    obj.addProperty("vehicle", vehicle.getClass().getName());
                }

                if (!target.getPassengers().isEmpty()) {
                    JsonArray passengers = new JsonArray();
                    for (Entity p : target.getPassengers()) {
                        passengers.add(p.getClass().getName());
                    }
                    obj.add("passengers", passengers);
                }

                if (!target.getTags().isEmpty()) {
                    JsonArray tags = new JsonArray();
                    for (String tag : target.getTags()) {
                        tags.add(tag);
                    }
                    obj.add("tags", tags);
                }

                if (target instanceof Player player) {
                    obj.addProperty("isPlayer", true);
                    obj.addProperty("playerName", player.getGameProfile().getName());
                }

                future.complete(obj);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future.get(5, TimeUnit.SECONDS);
    }

    private void addEquipment(JsonObject equipment, String slotName, LivingEntity living, EquipmentSlot slot) {
        ItemStack stack = living.getItemBySlot(slot);
        if (stack != null && !stack.isEmpty()) {
            JsonObject item = new JsonObject();
            item.addProperty("itemId", stack.getItem().getDescriptionId());
            if (stack.getMaxDamage() > 0) {
                item.addProperty("damage", stack.getDamageValue());
                item.addProperty("maxDamage", stack.getMaxDamage());
            }
            if (stack.hasCustomHoverName()) {
                item.addProperty("name", stack.getHoverName().getString());
            }
            equipment.add(slotName, item);
        }
    }

    private static final EquipmentSlot[] PRIMARY_SLOT_ORDER = {
        EquipmentSlot.HEAD,
        EquipmentSlot.MAINHAND,
        EquipmentSlot.OFFHAND,
        EquipmentSlot.CHEST,
        EquipmentSlot.LEGS,
        EquipmentSlot.FEET,
    };

    private JsonObject pickPrimaryEquipment(LivingEntity living) {
        for (EquipmentSlot slot : PRIMARY_SLOT_ORDER) {
            JsonObject obj = buildPrimary(slot.name(), living.getItemBySlot(slot));
            if (obj != null) return obj;
        }
        return null;
    }

    private JsonObject buildPrimary(String slot, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        var key = Registry.ITEM.getKey(stack.getItem());
        JsonObject obj = new JsonObject();
        obj.addProperty("slot", slot);
        obj.addProperty("itemId", key.toString());
        return obj;
    }

    private record EntityEntry(Entity entity, double distance) {}
}
