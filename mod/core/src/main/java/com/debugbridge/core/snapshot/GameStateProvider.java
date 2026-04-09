package com.debugbridge.core.snapshot;

import com.google.gson.JsonObject;

/**
 * Interface for capturing a snapshot of current game state.
 * Each version-specific mod provides its own implementation
 * since it has direct access to Minecraft classes.
 */
public interface GameStateProvider {
    /**
     * Capture a snapshot of the current game state.
     * Called on the game thread.
     */
    JsonObject captureSnapshot();
}
