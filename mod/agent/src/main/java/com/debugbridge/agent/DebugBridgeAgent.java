package com.debugbridge.agent;

import com.debugbridge.hooks.BytecodeCache;
import com.debugbridge.hooks.DebugBridgeLogger;
import com.debugbridge.hooks.LoggingAdvice;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.jar.JarFile;

import static net.bytebuddy.matcher.ElementMatchers.named;

/**
 * Java Agent entry point for DebugBridge runtime logging.
 *
 * Supports two loading modes:
 * 1. premain: Loaded via -javaagent at JVM startup (preferred)
 * 2. agentmain: Attached to running JVM via ByteBuddyAgent.install()
 *
 * Mixin Compatibility:
 * This agent is designed to work alongside SpongePowered Mixin. We use
 * BytecodeCache to capture post-Mixin bytecode and ensure our transformations
 * don't strip Mixin injections. See BytecodeCache and BytecodeObserver.
 */
public class DebugBridgeAgent {

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

        // Parse methodId into class name and method name
        int lastDot = methodId.lastIndexOf('.');
        if (lastDot < 0) {
            System.err.println("[DebugBridge] Invalid methodId: " + methodId);
            return;
        }
        String className = methodId.substring(0, lastDot);
        String methodName = methodId.substring(lastDot + 1);

        System.out.println("[DebugBridge] Injecting advice on " + className
            + "." + methodName);

        try {
            // Check if we have cached post-Mixin bytecode for this class
            String internalName = className.replace('.', '/');
            byte[] cachedBytecode = BytecodeCache.get(internalName);

            if (cachedBytecode != null && BytecodeCache.isMixinPresent()) {
                // Use Mixin-safe transformation with cached bytecode
                injectWithCachedBytecode(className, methodName, cachedBytecode);
            } else {
                // Standard transformation (no Mixin or class not cached)
                injectStandard(className, methodName);
            }

            System.out.println("[DebugBridge] Advice injected successfully on "
                + methodId);
        } catch (Exception e) {
            System.err.println("[DebugBridge] Failed to inject advice on "
                + methodId + ": " + e.getMessage());
            e.printStackTrace();
            // Remove from injectedMethods so it can be retried
            DebugBridgeLogger.injectedMethods.remove(methodId);
        }
    }

    /**
     * Standard advice injection using Byte Buddy's AgentBuilder.
     * Used when Mixin is not present or bytecode is not cached.
     */
    private static void injectStandard(String className, String methodName) {
        new AgentBuilder.Default()
            // Critical: do not change class schema (no new methods/fields)
            .disableClassFormatChanges()
            // Use retransformation to modify already-loaded classes
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
            .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
            // Error handling
            .with(new AgentBuilder.Listener.Adapter() {
                @Override
                public void onError(String typeName,
                                    ClassLoader classLoader,
                                    JavaModule module,
                                    boolean loaded,
                                    Throwable throwable) {
                    System.err.println("[DebugBridge] Transform error on "
                        + typeName + ": " + throwable.getMessage());
                }

                @Override
                public void onComplete(String typeName,
                                       ClassLoader classLoader,
                                       JavaModule module,
                                       boolean loaded) {
                    System.out.println("[DebugBridge] Transform complete: " + typeName);
                }
            })
            // Match the target class
            .type(named(className))
            // Install advice on the target method
            .transform((builder, type, cl, module, pd) ->
                builder.visit(
                    Advice.to(LoggingAdvice.class)
                          .on(named(methodName))))
            .installOn(instrumentation);
    }

    /**
     * Mixin-safe advice injection using cached post-Mixin bytecode.
     *
     * The problem: When we call Instrumentation.retransformClasses(), the JVM
     * gives transformers the ORIGINAL bytecode (pre-Mixin), not the current
     * bytecode. This strips all Mixin injections.
     *
     * The solution: Use our cached post-Mixin bytecode as the baseline for
     * transformation, then install the result via redefineClasses() instead
     * of retransformClasses().
     */
    private static void injectWithCachedBytecode(String className,
                                                  String methodName,
                                                  byte[] cachedBytecode) throws Exception {
        System.out.println("[DebugBridge] Using cached bytecode for " + className
            + " (" + cachedBytecode.length + " bytes)");

        // Find the loaded class
        Class<?> targetClass = null;
        for (Class<?> c : instrumentation.getAllLoadedClasses()) {
            if (c.getName().equals(className)) {
                targetClass = c;
                break;
            }
        }

        if (targetClass == null) {
            throw new ClassNotFoundException("Class not loaded: " + className);
        }

        // Use Byte Buddy to transform the cached bytecode
        ClassFileLocator locator = ClassFileLocator.Simple.of(
            className, cachedBytecode);

        TypePool typePool = TypePool.Default.of(locator);
        TypeDescription typeDesc = typePool.describe(className).resolve();

        DynamicType.Builder<?> builder = new net.bytebuddy.ByteBuddy()
            .redefine(typeDesc, locator);

        // Apply advice
        DynamicType.Unloaded<?> transformed = builder
            .visit(Advice.to(LoggingAdvice.class).on(named(methodName)))
            .make();

        byte[] newBytecode = transformed.getBytes();

        // Redefine the class with our transformed bytecode
        instrumentation.redefineClasses(
            new java.lang.instrument.ClassDefinition(targetClass, newBytecode));

        // Update cache with the new bytecode (includes our advice + Mixin)
        BytecodeCache.put(className.replace('.', '/'), newBytecode);
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
