package com.debugbridge.hooks;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * A Byte Buddy @Advice class whose bytecode is inlined into target methods.
 *
 * IMPORTANT: This class is NEVER called at runtime. Byte Buddy reads its
 * bytecode at transformation time and inlines it into target methods.
 * Therefore:
 * 1. It must only reference classes visible from the bootstrap classloader
 * 2. The Byte Buddy annotations must be present (as class annotations)
 * 3. DebugBridgeLogger must be on the bootstrap classloader
 */
public class LoggingAdvice {

    /**
     * Inlined at method entry. Captures method signature, 'this', and arguments.
     *
     * @return startTime if logging occurred, 0 otherwise (used to skip onExit)
     */
    @Advice.OnMethodEnter
    static long onEnter(
            @Advice.Origin String method,
            @Advice.This(optional = true) Object self,
            @Advice.AllArguments Object[] args) {
        return DebugBridgeLogger.onEntry(method, self, args);
    }

    /**
     * Inlined at method exit (both normal return and exception).
     * Only logs if startTime != 0 (i.e., onEntry logged something).
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void onExit(
            @Advice.Origin String method,
            @Advice.Return(readOnly = true,
                           typing = Assigner.Typing.DYNAMIC) Object ret,
            @Advice.Thrown Throwable thrown,
            @Advice.Enter long startTime) {
        if (startTime != 0L) {
            DebugBridgeLogger.onExit(method, ret, thrown, startTime);
        }
    }
}
