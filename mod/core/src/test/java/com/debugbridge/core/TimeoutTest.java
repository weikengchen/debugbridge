package com.debugbridge.core;

import com.debugbridge.core.lua.*;
import com.debugbridge.core.mapping.PassthroughResolver;
import com.debugbridge.core.refs.ObjectRefStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

class TimeoutTest {

    @Test
    @Timeout(10) // JUnit will kill the test if it takes > 10s
    void testInfiniteLoopTimesOut() {
        LuaRuntime runtime = new LuaRuntime(
            new PassthroughResolver("test"),
            new DirectDispatcher(),
            new ObjectRefStore()
        );
        runtime.setMaxExecutionTimeMs(2000); // 2 second timeout

        long start = System.currentTimeMillis();
        var result = runtime.execute("while true do end");
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("Elapsed: " + elapsed + "ms");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Error: " + result.error);
        System.out.println("Output: " + result.output);

        assertFalse(result.isSuccess());
        assertTrue(result.error.contains("timed out"),
            "Expected timeout error, got: " + result.error);
        assertTrue(elapsed < 5000,
            "Should have timed out within ~2s, took " + elapsed + "ms");
    }
}
