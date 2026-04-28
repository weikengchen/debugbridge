package com.debugbridge.core.entity;

import com.google.gson.JsonArray;

/**
 * Provides a fast, native query of nearby entities.
 * Each version-specific mod implements this using version-appropriate APIs.
 */
public interface NearbyEntitiesProvider {
    
    /**
     * Get entities within the given range of the local player.
     *
     * @param range maximum distance in blocks
     * @param limit maximum number of entities to return
     * @return JSON array of entity objects, each with id, type, distance, x, y, z, optionally customName,
     * and optionally {@code primaryEquipment: {slot, itemId}} for living entities — the first
     * non-empty slot in priority HEAD → MAINHAND → OFFHAND → CHEST → LEGS → FEET. {@code itemId}
     * is a registry key (e.g. {@code minecraft:iron_helmet}) suitable for {@code getItemTextureById}.
     * @throws Exception if the query fails (e.g. player not in world)
     */
    JsonArray getNearbyEntities(double range, int limit) throws Exception;
    
    /**
     * Get detailed information about a specific entity by its runtime ID.
     *
     * @param entityId the entity's runtime ID (from Entity.getId())
     * @return JSON object with health, equipment, tags, state flags, etc., or null if not found
     * @throws Exception if the query fails
     */
    com.google.gson.JsonObject getEntityDetails(int entityId) throws Exception;
}
