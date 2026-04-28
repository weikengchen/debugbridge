package com.debugbridge.core.chat;

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
     * @param limit max entries returned
     * @return JSON array of {plain, addedTime} where addedTime is the tick at
     *         which the message was added (Minecraft's GuiMessage.addedTime).
     * @throws Exception on query failure
     */
    JsonArray getRecentMessages(int limit) throws Exception;
}
