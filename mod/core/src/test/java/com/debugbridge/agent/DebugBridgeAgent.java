package com.debugbridge.agent;

public final class DebugBridgeAgent {
    private static boolean initialized;

    private DebugBridgeAgent() {
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static void setInitialized(boolean value) {
        initialized = value;
    }
}
