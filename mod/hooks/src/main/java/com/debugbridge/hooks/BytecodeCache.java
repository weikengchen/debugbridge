package com.debugbridge.hooks;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches bytecode AFTER Mixin and other transformers have run.
 *
 * When retransforming a class, the JVM provides the ORIGINAL bytecode
 * (pre-transformation), not the current bytecode. This means any Mixin
 * injections would be stripped if we retransform naively.
 *
 * This cache captures the post-Mixin bytecode so our advice injection
 * can use it as the baseline, preserving all existing transformations.
 *
 * Must be on the bootstrap classloader alongside DebugBridgeLogger.
 */
public class BytecodeCache {

    // className (internal format: com/example/MyClass) -> bytecode
    private static final ConcurrentHashMap<String, byte[]> cache =
        new ConcurrentHashMap<>();

    // Track whether our observer transformer has been installed
    private static volatile boolean observerInstalled = false;

    // Track whether Mixin is present in this environment
    private static volatile Boolean mixinPresent = null;

    /**
     * Store bytecode for a class. Called by our observer transformer.
     */
    public static void put(String className, byte[] bytecode) {
        if (className != null && bytecode != null) {
            cache.put(className, bytecode.clone());
        }
    }

    /**
     * Get cached bytecode for a class.
     * @return The cached bytecode, or null if not cached
     */
    public static byte[] get(String className) {
        byte[] cached = cache.get(className);
        return cached != null ? cached.clone() : null;
    }

    /**
     * Check if we have cached bytecode for a class.
     */
    public static boolean has(String className) {
        return cache.containsKey(className);
    }

    /**
     * Remove cached bytecode (e.g., after successful retransformation).
     */
    public static void remove(String className) {
        cache.remove(className);
    }

    /**
     * Clear all cached bytecode.
     */
    public static void clear() {
        cache.clear();
    }

    /**
     * Mark that our observer transformer has been installed.
     */
    public static void setObserverInstalled(boolean installed) {
        observerInstalled = installed;
    }

    /**
     * Check if our observer is active.
     */
    public static boolean isObserverInstalled() {
        return observerInstalled;
    }

    /**
     * Detect whether SpongePowered Mixin is present in this environment.
     * Cached after first detection.
     */
    public static boolean isMixinPresent() {
        if (mixinPresent == null) {
            mixinPresent = detectMixin();
        }
        return mixinPresent;
    }

    private static boolean detectMixin() {
        // Check for common Mixin classes
        String[] mixinClasses = {
            "org.spongepowered.asm.mixin.Mixin",
            "org.spongepowered.asm.mixin.transformer.MixinTransformer",
            "org.spongepowered.asm.service.MixinService"
        };

        for (String className : mixinClasses) {
            try {
                Class.forName(className);
                return true;
            } catch (ClassNotFoundException e) {
                // Continue checking
            }
        }
        return false;
    }

    /**
     * Get statistics about the cache.
     */
    public static String getStats() {
        return String.format("BytecodeCache: %d classes cached, observer=%s, mixin=%s",
            cache.size(), observerInstalled, mixinPresent);
    }
}
