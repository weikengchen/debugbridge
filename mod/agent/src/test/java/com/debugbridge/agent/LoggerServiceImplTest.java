package com.debugbridge.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.debugbridge.core.logging.LoggerService;
import com.debugbridge.hooks.DebugBridgeLogger;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoggerServiceImplTest {
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
    void firstInstallDoesNotClaimAdviceReuseButSecondInstallDoes() throws Exception {
        LoggerServiceImpl service = new LoggerServiceImpl();
        String outputFile = Files.createTempFile("debugbridge-agent-service", ".log").toString();

        LoggerService.InstallResult first = service.install(
            "example.Target.method",
            15,
            outputFile,
            true,
            false,
            true,
            1,
            null
        );

        assertTrue(first.success());
        assertNull(first.message());

        LoggerService.InstallResult second = service.install(
            "example.Target.method",
            15,
            outputFile,
            true,
            false,
            true,
            1,
            null
        );

        assertTrue(second.success());
        assertEquals("Reusing existing advice injection", second.message());
    }

    @Test
    void generatedOutputFileUsesJvmTempDirectory() {
        LoggerServiceImpl service = new LoggerServiceImpl();

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

        assertTrue(result.success());
        assertTrue(Path.of(result.outputFile()).startsWith(Path.of(System.getProperty("java.io.tmpdir"))));
        assertTrue(result.outputFile().endsWith(".log"));
    }

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
}
