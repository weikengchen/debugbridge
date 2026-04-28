package com.debugbridge.agent;

import com.debugbridge.hooks.BytecodeCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.ProtectionDomain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BytecodeObserverTest {
    private static void setStaticField(Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
    
    @BeforeEach
    void resetState() throws Exception {
        BytecodeCache.clear();
        setStaticField(BytecodeObserver.class, "instance", null);
        setStaticField(BytecodeObserver.class, "instrumentation", null);
    }
    
    @Test
    void explicitCacheRequestCapturesNonMinecraftClass() {
        RecordingInstrumentation recording = new RecordingInstrumentation(Target.class, true);
        
        BytecodeObserver.install(recording.instrumentation());
        BytecodeObserver.cacheLoadedClass(Target.class);
        
        assertEquals(1, recording.retransformCalls);
        assertTrue(BytecodeCache.has(Target.class.getName().replace('.', '/')));
    }
    
    private static final class Target {
        void tick() {
        }
    }
    
    static final class RecordingInstrumentation implements InvocationHandler {
        private final Class<?>[] loadedClasses;
        private final boolean feedTransformerOnRetransform;
        int addTransformerCalls;
        int retransformCalls;
        int redefineCalls;
        private ClassFileTransformer transformer;
        
        RecordingInstrumentation(Class<?> loadedClass, boolean feedTransformerOnRetransform) {
            this(new Class<?>[]{loadedClass}, feedTransformerOnRetransform);
        }
        
        RecordingInstrumentation(Class<?>[] loadedClasses, boolean feedTransformerOnRetransform) {
            this.loadedClasses = loadedClasses;
            this.feedTransformerOnRetransform = feedTransformerOnRetransform;
        }
        
        private static Object defaultValue(Class<?> returnType) {
            if (returnType == Void.TYPE) return null;
            if (returnType == Boolean.TYPE) return false;
            if (returnType == Byte.TYPE) return (byte) 0;
            if (returnType == Short.TYPE) return (short) 0;
            if (returnType == Integer.TYPE) return 0;
            if (returnType == Long.TYPE) return 0L;
            if (returnType == Float.TYPE) return 0f;
            if (returnType == Double.TYPE) return 0d;
            if (returnType == Character.TYPE) return '\0';
            return null;
        }
        
        Instrumentation instrumentation() {
            return (Instrumentation) Proxy.newProxyInstance(
                    Instrumentation.class.getClassLoader(),
                    new Class<?>[]{Instrumentation.class},
                    this
            );
        }
        
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "addTransformer" -> {
                    transformer = (ClassFileTransformer) args[0];
                    addTransformerCalls++;
                    yield null;
                }
                case "getAllLoadedClasses" -> loadedClasses;
                case "isModifiableClass", "isRetransformClassesSupported", "isRedefineClassesSupported" -> true;
                case "retransformClasses" -> {
                    retransformCalls++;
                    if (feedTransformerOnRetransform && transformer != null) {
                        for (Class<?> clazz : (Class<?>[]) args[0]) {
                            transformer.transform(
                                    clazz.getClassLoader(),
                                    clazz.getName().replace('.', '/'),
                                    clazz,
                                    (ProtectionDomain) null,
                                    new byte[]{0x01, 0x02, 0x03}
                            );
                        }
                    }
                    yield null;
                }
                case "redefineClasses" -> {
                    redefineCalls++;
                    yield null;
                }
                case "removeTransformer" -> true;
                default -> defaultValue(method.getReturnType());
            };
        }
    }
}
