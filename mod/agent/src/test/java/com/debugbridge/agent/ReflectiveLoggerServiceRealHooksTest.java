package com.debugbridge.agent;

import com.debugbridge.core.logging.LoggerService;
import com.debugbridge.core.logging.ReflectiveLoggerService;
import com.debugbridge.hooks.DebugBridgeLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectiveLoggerServiceRealHooksTest {
    private static Object getStaticField(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void setStaticField(Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    @BeforeEach
    void resetRuntime() throws Exception {
        setStaticField(DebugBridgeAgent.class, "initialized", true);
        ((AtomicLong) getStaticField(DebugBridgeLogger.class, "nextId")).set(1);
        DebugBridgeLogger.injectedMethods.clear();
        ((Map<?, ?>) getStaticField(DebugBridgeLogger.class, "active")).clear();
        ((Map<?, ?>) getStaticField(DebugBridgeLogger.class, "fileLoggers")).clear();
        ((Collection<?>) getStaticField(DebugBridgeLogger.class, "recentErrors")).clear();
    }

    @Test
    void reflectiveServiceBindsToRealAgentAndHooksApis() throws Exception {
        ReflectiveLoggerService service = new ReflectiveLoggerService();
        String outputFile = Files.createTempFile("debugbridge-real-hooks", ".log").toString();

        LoggerService.InstallResult result = service.install(
                "example.Target.method",
                15,
                outputFile,
                true,
                false,
                true,
                1,
                Map.of("type", "sample", "n", 2)
        );

        assertTrue(result.success());
        assertEquals("example.Target.method", service.listActive().get(0).method());
        assertTrue(service.listInjectedMethods().contains("example.Target.method"));
        assertTrue(service.cancel(result.loggerId()));
    }
}
