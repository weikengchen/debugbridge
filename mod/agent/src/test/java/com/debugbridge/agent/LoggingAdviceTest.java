package com.debugbridge.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;
import org.junit.jupiter.api.Test;

class LoggingAdviceTest {
    @Test
    void agentAdviceUsesLoggerMethodIdOriginFormat() throws Exception {
        Method onEnter = AgentLoggingAdvice.class.getDeclaredMethod(
            "onEnter",
            String.class,
            Object.class,
            Object[].class
        );

        Advice.Origin origin = onEnter.getParameters()[0].getAnnotation(Advice.Origin.class);

        assertEquals("#t.#m", origin.value());
    }
}
