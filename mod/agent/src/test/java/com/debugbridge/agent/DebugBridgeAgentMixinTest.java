package com.debugbridge.agent;

import com.debugbridge.core.logging.LoggerService;
import com.debugbridge.hooks.BytecodeCache;
import com.debugbridge.hooks.DebugBridgeLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DebugBridgeAgentMixinTest {
    private static void setStaticFieldUnchecked(Class<?> owner, String name, Object value) {
        try {
            setStaticField(owner, name, value);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
    
    private static void setStaticField(Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
    
    private static Object getStaticField(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
    
    private static byte[] classBytes(Class<?> type) throws IOException {
        String resourceName = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream stream = type.getResourceAsStream(resourceName)) {
            if (stream == null) {
                throw new IOException("Class resource not found: " + resourceName);
            }
            return stream.readAllBytes();
        }
    }
    
    @BeforeEach
    void resetState() throws Exception {
        BytecodeCache.clear();
        DebugBridgeLogger.injectedMethods.clear();
        DebugBridgeLogger.setInjector(null);
        ((Map<?, ?>) getStaticField(DebugBridgeLogger.class, "active")).clear();
        ((Map<?, ?>) getStaticField(DebugBridgeLogger.class, "fileLoggers")).clear();
        ((Collection<?>) getStaticField(DebugBridgeLogger.class, "recentErrors")).clear();
        setStaticField(BytecodeObserver.class, "instance", null);
        setStaticField(BytecodeObserver.class, "instrumentation", null);
        setStaticField(DebugBridgeAgent.class, "instrumentation", null);
        setStaticField(BytecodeCache.class, "mixinPresent", Boolean.TRUE);
    }
    
    @Test
    void mixinLoadedClassWithoutCachedBytecodeDoesNotTryToRebuildCacheByRetransforming() {
        String methodId = Target.class.getName() + ".tick";
        BytecodeObserverTest.RecordingInstrumentation recording =
                new BytecodeObserverTest.RecordingInstrumentation(Target.class, true);
        
        BytecodeObserver.install(recording.instrumentation());
        recording.addTransformerCalls = 0;
        setStaticFieldUnchecked(DebugBridgeAgent.class, "instrumentation", recording.instrumentation());
        DebugBridgeLogger.injectedMethods.add(methodId);
        
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> DebugBridgeAgent.injectAdvice(methodId)
        );
        
        assertTrue(failure.getMessage().contains("Failed to inject advice"));
        assertEquals(0, recording.retransformCalls);
        assertEquals(0, recording.redefineCalls);
        assertEquals(0, recording.addTransformerCalls);
        assertFalse(DebugBridgeLogger.injectedMethods.contains(methodId));
    }
    
    @Test
    void lateLoadedMixinClassOverridesPremainFalseDetection() throws Exception {
        String methodId = Target.class.getName() + ".tick";
        BytecodeObserverTest.RecordingInstrumentation recording =
                new BytecodeObserverTest.RecordingInstrumentation(
                        new Class<?>[]{Target.class, org.spongepowered.asm.mixin.Mixin.class},
                        false
                );
        setStaticField(BytecodeCache.class, "mixinPresent", Boolean.FALSE);
        
        BytecodeObserver.install(recording.instrumentation());
        recording.addTransformerCalls = 0;
        setStaticField(DebugBridgeAgent.class, "instrumentation", recording.instrumentation());
        DebugBridgeLogger.injectedMethods.add(methodId);
        
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> DebugBridgeAgent.injectAdvice(methodId)
        );
        
        assertTrue(failure.getMessage().contains("Failed to inject advice"));
        assertEquals(0, recording.retransformCalls);
        assertEquals(0, recording.redefineCalls);
        assertEquals(0, recording.addTransformerCalls);
        assertFalse(DebugBridgeLogger.injectedMethods.contains(methodId));
    }
    
    @Test
    void loggerInstallReportsErrorWhenMixinSafeInjectionIsRefused() throws Exception {
        String methodId = Target.class.getName() + ".tick";
        BytecodeObserverTest.RecordingInstrumentation recording =
                new BytecodeObserverTest.RecordingInstrumentation(Target.class, true);
        
        BytecodeObserver.install(recording.instrumentation());
        recording.addTransformerCalls = 0;
        setStaticField(DebugBridgeAgent.class, "instrumentation", recording.instrumentation());
        DebugBridgeLogger.setInjector(DebugBridgeAgent::injectAdvice);
        
        LoggerService.InstallResult result = new LoggerServiceImpl().install(
                methodId,
                15,
                Files.createTempFile("debugbridge-refused", ".log").toString(),
                true,
                false,
                true,
                1,
                null
        );
        
        assertFalse(result.success());
        assertTrue(result.error().contains("refusing unsafe retransformation"));
        assertTrue(DebugBridgeLogger.listActive().isEmpty());
        assertFalse(DebugBridgeLogger.injectedMethods.contains(methodId));
        assertEquals(0, recording.retransformCalls);
        assertEquals(0, recording.redefineCalls);
    }
    
    @Test
    void cachedBytecodeRedefinitionCanResolveTargetClassloaderDependencies() throws Exception {
        String methodId = DependencyTarget.class.getName() + ".tick";
        String internalName = DependencyTarget.class.getName().replace('.', '/');
        BytecodeCache.put(internalName, classBytes(DependencyTarget.class));
        
        BytecodeObserverTest.RecordingInstrumentation recording =
                new BytecodeObserverTest.RecordingInstrumentation(
                        new Class<?>[]{DependencyTarget.class, org.spongepowered.asm.mixin.Mixin.class},
                        false
                );
        setStaticField(BytecodeCache.class, "mixinPresent", Boolean.TRUE);
        setStaticField(DebugBridgeAgent.class, "instrumentation", recording.instrumentation());
        DebugBridgeLogger.injectedMethods.add(methodId);
        
        DebugBridgeAgent.injectAdvice(methodId);
        
        assertEquals(1, recording.redefineCalls);
        assertTrue(DebugBridgeLogger.injectedMethods.contains(methodId));
    }
    
    private static final class Target {
        void tick() {
        }
    }
}
