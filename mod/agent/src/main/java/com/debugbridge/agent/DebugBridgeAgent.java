package com.debugbridge.agent;

import com.debugbridge.hooks.BytecodeCache;
import com.debugbridge.hooks.DebugBridgeLogger;

import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

/**
 * Java Agent entry point for DebugBridge runtime logging.
 * <p>
 * Supports two loading modes:
 * 1. premain: Loaded via -javaagent at JVM startup (preferred)
 * 2. agentmain: Attached to running JVM via ByteBuddyAgent.install()
 * <p>
 * Mixin Compatibility:
 * This agent is designed to work alongside SpongePowered Mixin. We use
 * BytecodeCache to capture post-Mixin bytecode and ensure our transformations
 * don't strip Mixin injections. See BytecodeCache and BytecodeObserver.
 */
public class DebugBridgeAgent {
    private static final String BYTE_BUDDY_EXPERIMENTAL = "net.bytebuddy.experimental";

    private static Instrumentation instrumentation;
    private static volatile boolean initialized = false;

    /**
     * Premain entry - used when loaded via -javaagent.
     * Preferred mode: sees classes as they load, avoids JEP 451 warnings.
     */
    public static void premain(String args, Instrumentation inst) {
        init(args, inst, "premain");
    }

    /**
     * Agentmain entry - used when attached to a running JVM
     * via Attach API or ByteBuddyAgent.install().
     */
    public static void agentmain(String args, Instrumentation inst) {
        init(args, inst, "agentmain");
    }

    private static synchronized void init(String args, Instrumentation inst, String mode) {
        if (initialized) {
            System.out.println("[DebugBridge] Agent already initialized");
            return;
        }

        instrumentation = inst;
        System.out.println("[DebugBridge] Agent initializing via " + mode);
        enableByteBuddyExperimentalMode();

        // Load the hooks JAR onto bootstrap classloader
        String hooksJarPath = resolveHooksJarPath(args);
        if (hooksJarPath != null) {
            try {
                inst.appendToBootstrapClassLoaderSearch(new JarFile(hooksJarPath));
                System.out.println("[DebugBridge] Loaded hooks JAR: " + hooksJarPath);
            } catch (Exception e) {
                System.err.println("[DebugBridge] Failed to load hooks JAR: " + e);
                // Continue anyway - hooks may already be on bootstrap classpath
            }
        }

        // Install bytecode observer for Mixin compatibility
        BytecodeObserver.install(inst);

        // Register injector callback with DebugBridgeLogger
        DebugBridgeLogger.setInjector(DebugBridgeAgent::injectAdvice);

        // Detect Mixin presence and log status
        boolean mixinPresent = BytecodeCache.isMixinPresent();
        System.out.println("[DebugBridge] Mixin detected: " + mixinPresent);
        if (mixinPresent) {
            System.out.println("[DebugBridge] Using Mixin-safe transformation mode");
        }

        initialized = true;
        System.out.println("[DebugBridge] Agent initialized successfully");
    }

    private static void enableByteBuddyExperimentalMode() {
        if (!"true".equalsIgnoreCase(System.getProperty(BYTE_BUDDY_EXPERIMENTAL))) {
            System.setProperty(BYTE_BUDDY_EXPERIMENTAL, "true");
            System.out.println("[DebugBridge] Enabled Byte Buddy experimental mode");
        }
    }

    /**
     * Get the Instrumentation instance.
     */
    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    /**
     * Check if the agent is initialized.
     */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Inject LoggingAdvice into a specific method.
     * Called by DebugBridgeLogger.install() when a method is first hooked.
     *
     * @param methodId "fully.qualified.ClassName.methodName"
     */
    public static void injectAdvice(String methodId) {
        if (instrumentation == null) {
            System.err.println("[DebugBridge] Cannot inject - "
                    + "Instrumentation not available");
            return;
        }

        MethodHookTarget target = MethodHookTarget.parse(methodId).orElse(null);
        if (target == null) {
            System.err.println("[DebugBridge] Invalid methodId: " + methodId);
            return;
        }

        System.out.println("[DebugBridge] Injecting advice on " + target.methodId());

        try {
            new AdviceInjector(instrumentation).inject(target);

            System.out.println("[DebugBridge] Advice injected successfully on "
                    + methodId);
        } catch (Exception e) {
            System.err.println("[DebugBridge] Failed to inject advice on "
                    + methodId + ": " + e.getMessage());
            e.printStackTrace();
            // Remove from injectedMethods so it can be retried
            DebugBridgeLogger.injectedMethods.remove(methodId);
            throw new IllegalStateException(
                    "Failed to inject advice on " + methodId + ": " + e.getMessage(),
                    e
            );
        }
    }

    private static String resolveHooksJarPath(String args) {
        // Parse from agent args, or locate relative to agent JAR
        // Format: -javaagent:debugbridge-agent.jar=/path/to/hooks.jar
        if (args != null && !args.isEmpty()) {
            return args;
        }

        // Try to locate hooks JAR in common locations
        String[] searchPaths = {
                "debugbridge-hooks.jar",
                "libs/debugbridge-hooks.jar",
                "../hooks/debugbridge-hooks.jar"
        };

        for (String path : searchPaths) {
            java.io.File f = new java.io.File(path);
            if (f.exists()) {
                return f.getAbsolutePath();
            }
        }

        // Hooks may already be on classpath
        return null;
    }

}
