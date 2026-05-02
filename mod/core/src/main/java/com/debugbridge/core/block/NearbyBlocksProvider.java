package com.debugbridge.core.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Provides a fast, native query of nearby block entities (signs, chests,
 * banners, beacons, etc.) — the blocks worth browsing for debugging.
 * <p>
 * Plain-terrain blocks (dirt, stone, etc.) are intentionally excluded; this
 * provider only surfaces blocks that carry per-instance state via a
 * BlockEntity.
 */
public interface NearbyBlocksProvider {

    /**
     * Get block entities within the given range of the local player.
     *
     * @param range maximum distance in blocks
     * @param limit maximum number of entries to return
     * @return JSON array of block-entity summaries, each with at least
     * {@code x, y, z, blockId, type, distance}. {@code blockId} is
     * the block registry key (e.g. {@code minecraft:oak_sign}) and
     * {@code type} is the runtime BlockEntity class name.
     * @throws Exception on query failure
     */
    JsonArray getNearbyBlocks(double range, int limit) throws Exception;

    /**
     * Get detailed information about a specific block at (x, y, z).
     *
     * @return JSON object with type, blockId, position, plus type-specific
     * fields where available (sign lines, container contents, banner
     * patterns, skull owner, etc.). Returns null if there is no
     * block entity at that position.
     * @throws Exception on query failure
     */
    JsonObject getBlockDetails(int x, int y, int z) throws Exception;
}
