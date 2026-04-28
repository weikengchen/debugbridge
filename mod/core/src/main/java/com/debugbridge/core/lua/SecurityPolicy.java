package com.debugbridge.core.lua;

import java.util.Set;

/**
 * Controls which Java classes can be accessed from Lua scripts.
 */
public class SecurityPolicy {
    private static final Set<String> BLOCKED_PREFIXES = Set.of(
            "java.lang.Runtime",
            "java.lang.ProcessBuilder",
            "java.lang.System",
            "java.io.",
            "java.nio.file.",
            "java.net.",
            "java.security.",
            "javax.net.",
            "sun.",
            "com.sun.",
            "jdk."
    );
    
    /**
     * Check if a class is safe to access from Lua.
     */
    public static boolean isAllowed(String className) {
        for (String prefix : BLOCKED_PREFIXES) {
            if (className.startsWith(prefix) || className.equals(prefix)) {
                return false;
            }
        }
        return true;
    }
}
