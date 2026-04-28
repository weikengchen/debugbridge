package com.debugbridge.agent;

import com.debugbridge.hooks.BytecodeCache;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.util.Objects;

import static net.bytebuddy.matcher.ElementMatchers.named;

/**
 * Applies logging advice while preserving the classloader and Mixin invariants
 * that make DebugBridge safe inside Fabric clients.
 */
final class AdviceInjector {
    private final Instrumentation instrumentation;

    AdviceInjector(Instrumentation instrumentation) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
    }

    void inject(MethodHookTarget target) throws Exception {
        String internalName = target.internalName();
        byte[] cachedBytecode = BytecodeCache.get(internalName);
        boolean mixinPresent = isMixinPresentNow();
        Class<?> loadedClass = findLoadedClass(target.className());

        if (mixinPresent && cachedBytecode != null) {
            injectWithCachedBytecode(target, cachedBytecode);
        } else if (mixinPresent && loadedClass != null) {
            throw new IllegalStateException("Mixin is present and no "
                + "post-Mixin bytecode is cached for loaded class "
                + target.className() + "; refusing unsafe retransformation");
        } else {
            injectStandard(target);
        }
    }

    /**
     * Standard advice injection using Byte Buddy's AgentBuilder.
     * Used when Mixin is not present or the class has not loaded yet.
     */
    private void injectStandard(MethodHookTarget target) {
        new AgentBuilder.Default()
            .with(new net.bytebuddy.ByteBuddy().with(TypeValidation.DISABLED))
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
            .type(named(target.className()))
            // Install advice on the target method
            .transform((builder, type, cl, module, pd) ->
                builder.visit(
                    Advice.to(AgentLoggingAdvice.class)
                          .on(named(target.methodName()))))
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
    private void injectWithCachedBytecode(MethodHookTarget target,
                                          byte[] cachedBytecode) throws Exception {
        System.out.println("[DebugBridge] Using cached bytecode for " + target.className()
            + " (" + cachedBytecode.length + " bytes)");

        Class<?> targetClass = findLoadedClass(target.className());

        if (targetClass == null) {
            throw new ClassNotFoundException("Class not loaded: " + target.className());
        }

        ClassFileLocator locator = new ClassFileLocator.Compound(
            ClassFileLocator.Simple.of(target.className(), cachedBytecode),
            ClassFileLocator.ForClassLoader.of(targetClass.getClassLoader()),
            ClassFileLocator.ForClassLoader.ofSystemLoader(),
            ClassFileLocator.ForClassLoader.ofBootLoader()
        );

        TypePool typePool = TypePool.Default.of(locator);
        TypeDescription typeDesc = typePool.describe(target.className()).resolve();

        DynamicType.Builder<?> builder = new net.bytebuddy.ByteBuddy()
            .with(TypeValidation.DISABLED)
            .redefine(typeDesc, locator);

        // Apply advice
        DynamicType.Unloaded<?> transformed = builder
            .visit(Advice.to(AgentLoggingAdvice.class).on(named(target.methodName())))
            .make();

        byte[] newBytecode = transformed.getBytes();

        // Redefine the class with our transformed bytecode
        instrumentation.redefineClasses(
            new java.lang.instrument.ClassDefinition(targetClass, newBytecode));

        // Update cache with the new bytecode (includes our advice + Mixin)
        BytecodeCache.put(target.internalName(), newBytecode);
    }

    private Class<?> findLoadedClass(String className) {
        for (Class<?> c : instrumentation.getAllLoadedClasses()) {
            if (c.getName().equals(className)) {
                return c;
            }
        }
        return null;
    }

    private boolean isMixinPresentNow() {
        if (BytecodeCache.isMixinPresent()) {
            return true;
        }

        return findLoadedClass("org.spongepowered.asm.mixin.Mixin") != null
            || findLoadedClass("org.spongepowered.asm.mixin.transformer.MixinTransformer") != null
            || findLoadedClass("org.spongepowered.asm.service.MixinService") != null;
    }
}
