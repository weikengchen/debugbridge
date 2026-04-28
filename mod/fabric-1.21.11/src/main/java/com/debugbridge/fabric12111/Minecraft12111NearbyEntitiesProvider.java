package com.debugbridge.fabric12111;

import com.debugbridge.core.entity.NearbyEntitiesProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Display;
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
 * Native nearby-entities query for Minecraft 1.21.11.
 * Runs entity iteration directly in Java, avoiding the overhead of
 * per-entity Lua-to-Java bridge calls.
 */
public class Minecraft12111NearbyEntitiesProvider implements NearbyEntitiesProvider {
    
    private static final EquipmentSlot[] PRIMARY_SLOT_ORDER = {
            EquipmentSlot.HEAD,
            EquipmentSlot.MAINHAND,
            EquipmentSlot.OFFHAND,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET,
    };
    
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
                
                // Collect and sort by distance
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
                    
                    // Custom name (cheap for most entities)
                    var customName = entity.getCustomName();
                    if (customName != null) {
                        obj.addProperty("customName", customName.getString());
                    }
                    
                    // Entity type registry name
                    var typeKey = entity.getType().getDescriptionId();
                    if (typeKey != null) {
                        obj.addProperty("typeId", typeKey);
                    }
                    
                    // Primary equipment / framed / displayed item for thumbnail rendering
                    JsonObject primary = null;
                    switch (entity) {
                        case LivingEntity living -> primary = pickPrimaryEquipment(living);
                        case ItemFrame frame -> primary = buildPrimary("FRAME", frame.getItem());
                        case Display.ItemDisplay itemDisplay -> {
                            var renderState = itemDisplay.itemRenderState();
                            if (renderState != null) {
                                primary = buildPrimary("DISPLAY", renderState.itemStack());
                            }
                        }
                        default -> {
                        }
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
                
                // Custom name
                var customName = target.getCustomName();
                if (customName != null) {
                    obj.addProperty("customName", customName.getString());
                }
                
                // Position
                obj.addProperty("x", target.getX());
                obj.addProperty("y", target.getY());
                obj.addProperty("z", target.getZ());
                
                // Distance from player
                obj.addProperty("distance",
                        Math.round(target.distanceTo(mc.player) * 10.0) / 10.0);
                
                // ItemFrame-specific: the framed item, with damage if applicable.
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
                
                // LivingEntity-specific fields
                if (target instanceof LivingEntity living) {
                    obj.addProperty("health", Math.round(living.getHealth() * 10.0) / 10.0);
                    obj.addProperty("maxHealth", Math.round(living.getMaxHealth() * 10.0) / 10.0);
                    obj.addProperty("armor", living.getArmorValue());
                    
                    // Equipment
                    JsonObject equipment = new JsonObject();
                    addEquipment(equipment, "MAINHAND", living, EquipmentSlot.MAINHAND);
                    addEquipment(equipment, "OFFHAND", living, EquipmentSlot.OFFHAND);
                    addEquipment(equipment, "HEAD", living, EquipmentSlot.HEAD);
                    addEquipment(equipment, "CHEST", living, EquipmentSlot.CHEST);
                    addEquipment(equipment, "LEGS", living, EquipmentSlot.LEGS);
                    addEquipment(equipment, "FEET", living, EquipmentSlot.FEET);
                    if (!equipment.isEmpty()) {
                        obj.add("equipment", equipment);
                    }
                }
                
                // Display entity data (text/item/block displays)
                extractDisplayData(obj, target);
                
                // State flags
                obj.addProperty("isOnFire", target.isOnFire());
                obj.addProperty("isSprinting", target.isSprinting());
                
                // Vehicle (full class name so BridgeServer can map it)
                Entity vehicle = target.getVehicle();
                if (vehicle != null) {
                    obj.addProperty("vehicle", vehicle.getClass().getName());
                }
                
                // Passengers (full class names so BridgeServer can map them)
                if (!target.getPassengers().isEmpty()) {
                    JsonArray passengers = new JsonArray();
                    for (Entity p : target.getPassengers()) {
                        passengers.add(p.getClass().getName());
                    }
                    obj.add("passengers", passengers);
                }
                
                // Tags
                if (!target.getTags().isEmpty()) {
                    JsonArray tags = new JsonArray();
                    for (String tag : target.getTags()) {
                        tags.add(tag);
                    }
                    obj.add("tags", tags);
                }
                
                // Player-specific
                if (target instanceof Player player) {
                    obj.addProperty("isPlayer", true);
                    obj.addProperty("playerName", player.getGameProfile().name());
                }
                
                future.complete(obj);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        
        return future.get(5, TimeUnit.SECONDS);
    }
    
    private void extractDisplayData(JsonObject obj, Entity target) {
        try {
            if (target instanceof Display.ItemDisplay itemDisplay) {
                var renderState = itemDisplay.itemRenderState();
                if (renderState != null) {
                    ItemStack stack = renderState.itemStack();
                    if (stack != null && !stack.isEmpty()) {
                        JsonObject item = new JsonObject();
                        item.addProperty("itemId", stack.getItem().getDescriptionId());
                        item.addProperty("count", stack.getCount());
                        var hoverName = stack.getHoverName();
                        if (hoverName != null) {
                            item.addProperty("name", hoverName.getString());
                        }
                        obj.add("displayItem", item);
                    }
                }
            } else if (target instanceof Display.TextDisplay textDisplay) {
                var renderState = textDisplay.textRenderState();
                if (renderState != null && renderState.text() != null) {
                    obj.addProperty("displayText", renderState.text().getString());
                }
            } else if (target instanceof Display.BlockDisplay blockDisplay) {
                var renderState = blockDisplay.blockRenderState();
                if (renderState != null && renderState.blockState() != null) {
                    obj.addProperty("displayBlock",
                            renderState.blockState().getBlock().getDescriptionId());
                }
            }
        } catch (Exception ignored) {
            // Render states may not be populated yet; ignore silently
        }
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
            if (stack.has(DataComponents.CUSTOM_NAME)) {
                item.addProperty("name", stack.getHoverName().getString());
            }
            equipment.add(slotName, item);
        }
    }
    
    private JsonObject pickPrimaryEquipment(LivingEntity living) {
        for (EquipmentSlot slot : PRIMARY_SLOT_ORDER) {
            JsonObject obj = buildPrimary(slot.name(), living.getItemBySlot(slot));
            if (obj != null) return obj;
        }
        return null;
    }
    
    private JsonObject buildPrimary(String slot, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        JsonObject obj = new JsonObject();
        obj.addProperty("slot", slot);
        obj.addProperty("itemId", key.toString());
        return obj;
    }
    
    private record EntityEntry(Entity entity, double distance) {
    }
}
