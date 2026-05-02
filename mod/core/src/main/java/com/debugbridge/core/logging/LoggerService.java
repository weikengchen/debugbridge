package com.debugbridge.core.logging;

import java.util.List;
import java.util.Map;

/**
 * Interface for the runtime logger injection service.
 * Implemented by the agent module when loaded; a no-op stub is used otherwise.
 */
public interface LoggerService {

    /**
     * No-op implementation when the agent is not loaded.
     */
    LoggerService UNAVAILABLE = new LoggerService() {
        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public InstallResult install(String methodId, int durationSeconds, String outputFile,
                                     boolean logArgs, boolean logReturn, boolean logTiming,
                                     int argDepth, Map<String, Object> filter) {
            return InstallResult.error(
                    "Logger injection not available. The DebugBridge agent is not loaded. " +
                            "Start Minecraft with -javaagent:debugbridge-agent.jar or call " +
                            "ByteBuddyAgent.install() from the mod initializer.");
        }

        @Override
        public boolean cancel(long id) {
            return false;
        }

        @Override
        public List<LoggerInfo> listActive() {
            return List.of();
        }

        @Override
        public List<String> listInjectedMethods() {
            return List.of();
        }
    };

    /**
     * Check if the logger service is available (agent loaded).
     */
    boolean isAvailable();

    /**
     * Install a logger on a method.
     *
     * @param methodId        Fully qualified method name (Mojang names)
     * @param durationSeconds How long the logger stays active
     * @param outputFile      Path to log file (null for auto-generated)
     * @param logArgs         Whether to log arguments
     * @param logReturn       Whether to log return value
     * @param logTiming       Whether to log timing
     * @param argDepth        Depth of argument inspection
     * @param filter          Filter configuration (null for no filter)
     * @return Result containing logger ID and output file path
     */
    InstallResult install(String methodId, int durationSeconds, String outputFile,
                          boolean logArgs, boolean logReturn, boolean logTiming,
                          int argDepth, Map<String, Object> filter);

    /**
     * Cancel a logger by ID.
     *
     * @return true if the logger was found and cancelled
     */
    boolean cancel(long id);

    /**
     * List all active loggers.
     */
    List<LoggerInfo> listActive();

    /**
     * List all methods that have advice injected (active or not).
     */
    List<String> listInjectedMethods();

    /**
     * Result of installing a logger.
     */
    record InstallResult(long loggerId, String outputFile, String message, boolean success, String error) {
        public static InstallResult success(long loggerId, String outputFile, String message) {
            return new InstallResult(loggerId, outputFile, message, true, null);
        }

        public static InstallResult error(String error) {
            return new InstallResult(-1, null, null, false, error);
        }
    }

    /**
     * Info about an active logger.
     */
    record LoggerInfo(long id, String method, long remainingMs, boolean hasFilter) {
    }
}
