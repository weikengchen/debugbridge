package com.debugbridge.agent;

import java.util.Optional;

/**
 * Parsed method hook identifier used by the agent instrumentation layer.
 */
record MethodHookTarget(String methodId, String className, String methodName) {
    static Optional<MethodHookTarget> parse(String methodId) {
        if (methodId == null) {
            return Optional.empty();
        }

        int lastDot = methodId.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == methodId.length() - 1) {
            return Optional.empty();
        }

        return Optional.of(new MethodHookTarget(
            methodId,
            methodId.substring(0, lastDot),
            methodId.substring(lastDot + 1)
        ));
    }

    String internalName() {
        return className.replace('.', '/');
    }
}
