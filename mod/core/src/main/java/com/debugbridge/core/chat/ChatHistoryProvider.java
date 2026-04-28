package com.debugbridge.core.chat;

import com.debugbridge.core.mapping.MappingResolver;
import com.google.gson.JsonArray;

/**
 * Provides recent client-side chat messages — what the user has seen in the
 * chat overlay, including system messages and command output. Cheaper than
 * iterating gui.chat.allMessages from Lua (which costs one bridge round-trip
 * per field on each message).
 */
public interface ChatHistoryProvider {
    
    /**
     * Get the most recent chat messages, newest first.
     *
     * @param limit       max entries returned
     * @param resolver    used to map the Mojang field name {@code allMessages}
     *                    to the runtime intermediary name. Loom rewrites
     *                    method/field references in our bytecode but cannot
     *                    rewrite reflective string lookups, so the provider
     *                    must do this mapping itself.
     * @param includeJson when true, each message also gets a {@code json}
     *                    field with the full Component serialized (preserves
     *                    colors, styles, click events, hover events). Off by
     *                    default — adds bytes for callers that only need
     *                    {@code plain}.
     * @return JSON array of {plain, addedTime, json?} where addedTime is the
     *         tick at which the message was added (GuiMessage.addedTime).
     * @throws Exception on query failure
     */
    JsonArray getRecentMessages(int limit, MappingResolver resolver, boolean includeJson) throws Exception;
}
