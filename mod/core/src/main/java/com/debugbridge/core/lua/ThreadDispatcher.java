package com.debugbridge.core.lua;

import java.util.concurrent.Callable;

/**
 * Abstraction over Minecraft's thread dispatcher.
 * Each version-specific mod provides an implementation that posts
 * runnables to the Minecraft client thread.
 */
public interface ThreadDispatcher {
    /**
     * Execute a Callable on the game's main thread and return the result.
     * This MUST block until execution completes (or times out).
     * All Java reflection calls from Lua MUST go through this.
     *
     * @param timeout max milliseconds to wait
     * @throws Exception if execution fails or times out
     */
    <T> T executeOnGameThread(Callable<T> task, long timeout) throws Exception;
}
