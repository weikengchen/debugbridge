package com.debugbridge.core.logging;

import com.debugbridge.agent.DebugBridgeAgent;
import com.debugbridge.hooks.DebugBridgeLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReflectiveLoggerServiceTest {
    private static Object allocateWithoutConstructor(Class<?> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeType.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Method allocateInstance = unsafeType.getMethod("allocateInstance", Class.class);
        return allocateInstance.invoke(unsafe, type);
    }

    private static void setField(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    @BeforeEach
    void setUp() {
        DebugBridgeAgent.setInitialized(false);
        DebugBridgeLogger.reset();
    }

    @Test
    void reportsUnavailableUntilAgentIsInitialized() {
        ReflectiveLoggerService service = new ReflectiveLoggerService();

        assertFalse(service.isAvailable());

        LoggerService.InstallResult result = service.install(
                "example.Target.method",
                15,
                null,
                true,
                false,
                true,
                1,
                null
        );
        assertFalse(result.success());
        assertTrue(result.error().contains("not available"));
    }

    @Test
    void delegatesInstallCancelAndListOperationsToHooks() {
        DebugBridgeAgent.setInitialized(true);
        ReflectiveLoggerService service = new ReflectiveLoggerService();

        LoggerService.InstallResult first = service.install(
                "example.Target.method",
                15,
                "C:/temp/logger.log",
                true,
                false,
                true,
                2,
                Map.of("type", "sample", "n", 4)
        );

        assertTrue(first.success());
        assertEquals(1L, first.loggerId());
        assertEquals("C:/temp/logger.log", first.outputFile());
        assertNull(first.message());
        assertEquals("example.Target.method", DebugBridgeLogger.lastMethodId);
        assertEquals("C:/temp/logger.log", DebugBridgeLogger.lastOutputFile);
        assertEquals(2, DebugBridgeLogger.lastArgDepth);
        assertNotNull(DebugBridgeLogger.lastFilter);
        assertFalse(DebugBridgeLogger.lastFilter.test(new Object[0]));
        assertFalse(DebugBridgeLogger.lastFilter.test(new Object[0]));
        assertFalse(DebugBridgeLogger.lastFilter.test(new Object[0]));
        assertTrue(DebugBridgeLogger.lastFilter.test(new Object[0]));

        assertEquals(1, service.listActive().size());
        assertEquals("example.Target.method", service.listInjectedMethods().get(0));
        assertTrue(service.cancel(first.loggerId()));
        assertTrue(service.listActive().isEmpty());

        LoggerService.InstallResult second = service.install(
                "example.Target.method",
                15,
                "C:/temp/logger.log",
                true,
                false,
                true,
                2,
                null
        );

        assertTrue(second.success());
        assertEquals("Reusing existing advice injection", second.message());
    }

    @Test
    void generatesPortableTempPathWhenOutputFileIsMissing() {
        DebugBridgeAgent.setInitialized(true);
        ReflectiveLoggerService service = new ReflectiveLoggerService();

        LoggerService.InstallResult result = service.install(
                "example.Target.method",
                30,
                null,
                true,
                false,
                true,
                1,
                null
        );

        assertTrue(result.success());
        assertNotNull(result.outputFile());
        assertTrue(result.outputFile().endsWith(".log"));
        assertEquals(Path.of(System.getProperty("java.io.tmpdir")).getRoot(),
                Path.of(result.outputFile()).getRoot());
    }

    @Test
    void retriesRuntimeLookupAfterUnavailableBindingBecomesStale() throws Exception {
        DebugBridgeAgent.setInitialized(true);
        ReflectiveLoggerService service = new ReflectiveLoggerService();
        Object unavailableRuntime = allocateWithoutConstructor(
                Class.forName("com.debugbridge.core.logging.ReflectiveLoggerService$RuntimeAccess")
        );
        setField(service, "runtime", unavailableRuntime);

        assertTrue(service.isAvailable());
    }
}
