package com.debugbridge.core.screen;

import com.google.gson.JsonObject;

/**
 * Inspects the screen the player currently has open. For container screens
 * (chests, anvils, brewing stands, etc.) emits per-slot item info in a single
 * native pass — avoids the per-call Java<->Lua bridge cost that times out
 * when iterating slots from Lua.
 */
public interface ScreenInspectProvider {
    
    /**
     * Snapshot the currently-open screen.
     *
     * @return JSON object with {open: bool, type, title}. For container screens
     * also {menuClass, slots: [{idx, container, item:{itemId, count,
     * damage, maxDamage, name}}]}. {open: false} when no screen is
     * displayed.
     * @throws Exception on query failure
     */
    JsonObject inspectCurrentScreen() throws Exception;
}
