package com.debugbridge.agent;

import com.debugbridge.hooks.DebugBridgeLogger;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Agent-owned Byte Buddy advice definition.
 * <p>
 * Byte Buddy reads this class through the agent classloader while building the
 * transformed target bytecode. The inlined advice may only call bootstrap-safe
 * hook APIs such as DebugBridgeLogger.
 */
public final class AgentLoggingAdvice {
    private AgentLoggingAdvice() {
    }
    
    @Advice.OnMethodEnter
    static long onEnter(
            @Advice.Origin("#t.#m") String method,
            @Advice.This(optional = true) Object self,
            @Advice.AllArguments Object[] args
    ) {
        return DebugBridgeLogger.onEntry(method, self, args);
    }
    
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void onExit(
            @Advice.Origin("#t.#m") String method,
            @Advice.Return(readOnly = true, typing = Assigner.Typing.DYNAMIC) Object ret,
            @Advice.Thrown Throwable thrown,
            @Advice.Enter long startTime
    ) {
        if (startTime != 0L) {
            DebugBridgeLogger.onExit(method, ret, thrown, startTime);
        }
    }
}
