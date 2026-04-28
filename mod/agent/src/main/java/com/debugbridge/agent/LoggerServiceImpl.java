package com.debugbridge.agent;

import com.debugbridge.core.logging.LoggerOutputFiles;
import com.debugbridge.core.logging.LoggerService;
import com.debugbridge.hooks.DebugBridgeLogger;
import com.debugbridge.hooks.LogFilters;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Implementation of LoggerService that delegates to DebugBridgeLogger.
 * Instantiated by the agent module and registered with the BridgeServer.
 */
public class LoggerServiceImpl implements LoggerService {
    
    @Override
    public boolean isAvailable() {
        return DebugBridgeAgent.isInitialized();
    }
    
    @Override
    public InstallResult install(String methodId, int durationSeconds, String outputFile,
                                 boolean logArgs, boolean logReturn, boolean logTiming,
                                 int argDepth, Map<String, Object> filter) {
        try {
            boolean alreadyInjected = DebugBridgeLogger.isInjected(methodId);
            if (outputFile == null || outputFile.isBlank()) {
                outputFile = LoggerOutputFiles.generate(methodId);
            }
            
            // Build filter predicate
            Predicate<Object[]> predicate = buildFilter(filter);
            
            // Install the logger
            long loggerId = DebugBridgeLogger.install(
                    methodId,
                    outputFile,
                    Duration.ofSeconds(durationSeconds),
                    predicate,
                    logArgs,
                    logReturn,
                    logTiming,
                    argDepth
            );
            
            String message = alreadyInjected ? "Reusing existing advice injection" : null;
            
            return InstallResult.success(loggerId, outputFile, message);
            
        } catch (Exception e) {
            return InstallResult.error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
    
    @Override
    public boolean cancel(long id) {
        return DebugBridgeLogger.cancel(id);
    }
    
    @Override
    public List<LoggerInfo> listActive() {
        List<LoggerInfo> result = new ArrayList<>();
        for (Map<String, Object> info : DebugBridgeLogger.listActive()) {
            result.add(new LoggerInfo(
                    ((Number) info.get("id")).longValue(),
                    (String) info.get("method"),
                    ((Number) info.get("remaining_ms")).longValue(),
                    (Boolean) info.get("has_filter")
            ));
        }
        return result;
    }
    
    @Override
    public List<String> listInjectedMethods() {
        return new ArrayList<>(DebugBridgeLogger.injectedMethods);
    }
    
    private Predicate<Object[]> buildFilter(Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return null;
        }
        
        String type = (String) filter.get("type");
        return switch (type) {
            case "throttle" -> {
                long intervalMs = ((Number) filter.get("interval_ms")).longValue();
                yield LogFilters.throttle(intervalMs);
            }
            case "arg_contains" -> {
                int index = ((Number) filter.get("index")).intValue();
                String substring = (String) filter.get("substring");
                yield LogFilters.argContains(index, substring);
            }
            case "arg_instanceof" -> {
                int index = ((Number) filter.get("index")).intValue();
                String className = (String) filter.get("class_name");
                yield LogFilters.argInstanceOf(index, className);
            }
            case "sample" -> {
                int n = ((Number) filter.get("n")).intValue();
                yield LogFilters.sample(n);
            }
            default -> null;
        };
    }
}
