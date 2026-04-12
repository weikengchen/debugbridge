package com.debugbridge.agent;

import com.debugbridge.hooks.BytecodeCache;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * A passive ClassFileTransformer that observes bytecode AFTER all other
 * transformers (including Mixin) have run, and caches it for later use.
 *
 * This is critical for Mixin compatibility: when we later retransform a
 * class to inject logging advice, we use the cached post-Mixin bytecode
 * as our baseline, preserving all Mixin injections.
 *
 * Registration order matters:
 * 1. Mixin registers its transformer during premain/early startup
 * 2. We register this observer AFTER Mixin, so we see Mixin's output
 * 3. When a class loads, transformers run in registration order
 * 4. Our observer sees the final transformed bytecode and caches it
 */
public class BytecodeObserver implements ClassFileTransformer {

    private static volatile BytecodeObserver instance;
    private static volatile Instrumentation instrumentation;

    // Only cache Minecraft-related classes to avoid memory bloat
    private static final String[] CACHE_PREFIXES = {
        "net/minecraft/",
        "com/mojang/",
    };

    // Skip caching for known-hot classes that would bloat memory
    private static final String[] SKIP_PATTERNS = {
        "$$Lambda",
        "$SWITCH_TABLE$",
    };

    private BytecodeObserver() {}

    /**
     * Install the observer on the given Instrumentation.
     * Should be called during mod initialization, AFTER Mixin has loaded.
     *
     * @param inst The Instrumentation instance
     * @return true if installation succeeded
     */
    public static synchronized boolean install(Instrumentation inst) {
        if (instance != null) {
            System.out.println("[DebugBridge] BytecodeObserver already installed");
            return true;
        }

        instrumentation = inst;
        instance = new BytecodeObserver();

        // Register with canRetransform=true so we participate in retransformation
        inst.addTransformer(instance, /* canRetransform */ true);

        BytecodeCache.setObserverInstalled(true);

        System.out.println("[DebugBridge] BytecodeObserver installed, Mixin present: "
            + BytecodeCache.isMixinPresent());

        return true;
    }

    /**
     * Get the Instrumentation instance.
     */
    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {

        // Only observe, never modify
        // Cache bytecode for classes we might want to instrument later

        if (className == null || classfileBuffer == null) {
            return null;
        }

        // Skip lambda and synthetic classes
        for (String pattern : SKIP_PATTERNS) {
            if (className.contains(pattern)) {
                return null;
            }
        }

        // Only cache Minecraft classes to avoid memory bloat
        boolean shouldCache = false;
        for (String prefix : CACHE_PREFIXES) {
            if (className.startsWith(prefix)) {
                shouldCache = true;
                break;
            }
        }

        if (shouldCache) {
            // This is the POST-Mixin bytecode (we registered after Mixin)
            BytecodeCache.put(className, classfileBuffer);
        }

        // Return null = don't modify the bytecode
        return null;
    }

    /**
     * Manually trigger caching of an already-loaded class.
     * Used when we want to instrument a class that loaded before our observer.
     */
    public static void cacheLoadedClass(Class<?> clazz) {
        if (instrumentation == null) {
            return;
        }

        String internalName = clazz.getName().replace('.', '/');
        if (BytecodeCache.has(internalName)) {
            return; // Already cached
        }

        // Force retransformation to capture current bytecode
        // Our observer will cache it during the retransform cycle
        try {
            if (instrumentation.isModifiableClass(clazz)) {
                instrumentation.retransformClasses(clazz);
            }
        } catch (Exception e) {
            System.err.println("[DebugBridge] Failed to cache bytecode for "
                + clazz.getName() + ": " + e.getMessage());
        }
    }
}
