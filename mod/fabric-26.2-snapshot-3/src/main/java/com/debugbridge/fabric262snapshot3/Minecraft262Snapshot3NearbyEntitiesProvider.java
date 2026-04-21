package com.debugbridge.fabric262snapshot3;

import com.debugbridge.core.entity.NearbyEntitiesProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class Minecraft262Snapshot3NearbyEntitiesProvider implements NearbyEntitiesProvider {
    @Override
    public JsonArray getNearbyEntities(double range, int limit) {
        throw new UnsupportedOperationException("26.2 nearby entities provider not implemented");
    }

    @Override
    public JsonObject getEntityDetails(int entityId) {
        throw new UnsupportedOperationException("26.2 nearby entities provider not implemented");
    }
}
