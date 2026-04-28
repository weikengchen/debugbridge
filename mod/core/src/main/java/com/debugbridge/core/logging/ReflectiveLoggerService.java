package com.debugbridge.core.logging;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * LoggerService implementation that binds to the agent and hooks modules at runtime
 * without taking a direct compile dependency on either module.
 */
public final class ReflectiveLoggerService implements LoggerService {
    
    private final Supplier<RuntimeAccess> runtimeFactory;
    private volatile RuntimeAccess runtime;
    
    public ReflectiveLoggerService() {
        this(ReflectiveLoggerService.class.getClassLoader());
    }
    
    ReflectiveLoggerService(ClassLoader classLoader) {
        this(() -> new RuntimeAccess(classLoader));
    }
    
    private ReflectiveLoggerService(Supplier<RuntimeAccess> runtimeFactory) {
        this.runtimeFactory = Objects.requireNonNull(runtimeFactory, "runtimeFactory");
    }
    
    private static Throwable unwrap(Throwable error) {
        if (error instanceof InvocationTargetException invocationTargetException
                && invocationTargetException.getCause() != null) {
            return unwrap(invocationTargetException.getCause());
        }
        return error;
    }
    
    @Override
    public boolean isAvailable() {
        return runtime().isAvailable();
    }
    
    @Override
    public InstallResult install(String methodId, int durationSeconds, String outputFile,
                                 boolean logArgs, boolean logReturn, boolean logTiming,
                                 int argDepth, Map<String, Object> filter) {
        RuntimeAccess currentRuntime = runtime();
        if (!currentRuntime.isAvailable()) {
            return LoggerService.UNAVAILABLE.install(
                    methodId,
                    durationSeconds,
                    outputFile,
                    logArgs,
                    logReturn,
                    logTiming,
                    argDepth,
                    filter
            );
        }
        
        try {
            boolean alreadyInjected = currentRuntime.isInjected(methodId);
            String resolvedOutputFile = outputFile;
            if (resolvedOutputFile == null || resolvedOutputFile.isBlank()) {
                resolvedOutputFile = LoggerOutputFiles.generate(methodId);
            }
            
            Object predicate = buildFilter(filter);
            long loggerId = currentRuntime.install(
                    methodId,
                    resolvedOutputFile,
                    Duration.ofSeconds(durationSeconds),
                    predicate,
                    logArgs,
                    logReturn,
                    logTiming,
                    argDepth
            );
            
            return InstallResult.success(
                    loggerId,
                    resolvedOutputFile,
                    alreadyInjected ? "Reusing existing advice injection" : null
            );
        } catch (ReflectiveOperationException | RuntimeException e) {
            Throwable cause = unwrap(e);
            return InstallResult.error(cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }
    
    @Override
    public boolean cancel(long id) {
        try {
            return runtime().cancel(id);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
    
    @Override
    public List<LoggerInfo> listActive() {
        try {
            return runtime().listActive();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return List.of();
        }
    }
    
    @Override
    public List<String> listInjectedMethods() {
        try {
            return runtime().listInjectedMethods();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return List.of();
        }
    }
    
    private RuntimeAccess runtime() {
        RuntimeAccess current = runtime;
        if (current != null && current.isAvailable()) {
            return current;
        }
        
        RuntimeAccess refreshed = runtimeFactory.get();
        runtime = refreshed;
        return refreshed;
    }
    
    private Object buildFilter(Map<String, Object> filter) throws ReflectiveOperationException {
        if (filter == null || filter.isEmpty()) {
            return null;
        }
        
        String type = (String) filter.get("type");
        if (type == null) {
            return null;
        }
        
        return switch (type) {
            case "throttle" -> runtime.createThrottleFilter(((Number) filter.get("interval_ms")).longValue());
            case "arg_contains" -> runtime.createArgContainsFilter(
                    ((Number) filter.get("index")).intValue(),
                    (String) filter.get("substring")
            );
            case "arg_instanceof" -> runtime.createArgInstanceOfFilter(
                    ((Number) filter.get("index")).intValue(),
                    (String) filter.get("class_name")
            );
            case "sample" -> runtime.createSampleFilter(((Number) filter.get("n")).intValue());
            default -> null;
        };
    }
    
    private static final class RuntimeAccess {
        
        private static final String AGENT_CLASS = "com.debugbridge.agent.DebugBridgeAgent";
        private static final String LOGGER_CLASS = "com.debugbridge.hooks.DebugBridgeLogger";
        private static final String FILTERS_CLASS = "com.debugbridge.hooks.LogFilters";
        
        private final Method agentIsInitialized;
        private final Method loggerInstall;
        private final Method loggerCancel;
        private final Method loggerListActive;
        private final Method loggerIsInjected;
        private final Field loggerInjectedMethods;
        private final Method filtersThrottle;
        private final Method filtersArgContains;
        private final Method filtersArgInstanceOf;
        private final Method filtersSample;
        
        private RuntimeAccess(ClassLoader classLoader) {
            Class<?> agentClass = loadClass(classLoader, AGENT_CLASS);
            Class<?> loggerClass = loadClass(classLoader, LOGGER_CLASS);
            Class<?> filtersClass = loadClass(classLoader, FILTERS_CLASS);
            
            this.agentIsInitialized = findMethod(agentClass, "isInitialized");
            this.loggerInstall = findMethod(
                    loggerClass,
                    "install",
                    String.class,
                    String.class,
                    Duration.class,
                    Predicate.class,
                    boolean.class,
                    boolean.class,
                    boolean.class,
                    int.class
            );
            this.loggerCancel = findMethod(loggerClass, "cancel", long.class);
            this.loggerListActive = findMethod(loggerClass, "listActive");
            this.loggerIsInjected = findMethod(loggerClass, "isInjected", String.class);
            this.loggerInjectedMethods = findField(loggerClass, "injectedMethods");
            this.filtersThrottle = findMethod(filtersClass, "throttle", long.class);
            this.filtersArgContains = findMethod(filtersClass, "argContains", int.class, String.class);
            this.filtersArgInstanceOf = findMethod(filtersClass, "argInstanceOf", int.class, String.class);
            this.filtersSample = findMethod(filtersClass, "sample", int.class);
        }
        
        private static Object invokeFilterFactory(Method method, Object... args) throws ReflectiveOperationException {
            if (method == null) {
                throw new IllegalStateException("DebugBridge filter runtime is not available");
            }
            return method.invoke(null, args);
        }
        
        private static Class<?> loadClass(ClassLoader classLoader, String className) {
            ClassLoader[] candidates = {
                    classLoader,
                    Thread.currentThread().getContextClassLoader(),
                    ClassLoader.getSystemClassLoader(),
                    null
            };
            
            for (ClassLoader candidate : candidates) {
                try {
                    return Class.forName(className, true, candidate);
                } catch (ClassNotFoundException | LinkageError ignored) {
                    // Try the next candidate loader.
                }
            }
            
            return null;
        }
        
        private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
            if (type == null) {
                return null;
            }
            
            try {
                return type.getMethod(name, parameterTypes);
            } catch (NoSuchMethodException e) {
                return null;
            }
        }
        
        private static Field findField(Class<?> type, String name) {
            if (type == null) {
                return null;
            }
            
            try {
                return type.getField(name);
            } catch (NoSuchFieldException e) {
                return null;
            }
        }
        
        private boolean isAvailable() {
            if (agentIsInitialized == null
                    || loggerInstall == null
                    || loggerCancel == null
                    || loggerListActive == null
                    || loggerInjectedMethods == null) {
                return false;
            }
            
            try {
                return Boolean.TRUE.equals(agentIsInitialized.invoke(null));
            } catch (ReflectiveOperationException e) {
                return false;
            }
        }
        
        private long install(String methodId, String outputFile, Duration duration, Object filter,
                             boolean logArgs, boolean logReturn, boolean logTiming, int argDepth)
                throws ReflectiveOperationException {
            if (loggerInstall == null) {
                throw new IllegalStateException("DebugBridge logger runtime is not available");
            }
            
            Object result = loggerInstall.invoke(
                    null,
                    methodId,
                    outputFile,
                    duration,
                    filter,
                    logArgs,
                    logReturn,
                    logTiming,
                    argDepth
            );
            return ((Number) result).longValue();
        }
        
        private boolean cancel(long id) throws ReflectiveOperationException {
            if (loggerCancel == null) {
                return false;
            }
            return Boolean.TRUE.equals(loggerCancel.invoke(null, id));
        }
        
        private List<LoggerInfo> listActive() throws ReflectiveOperationException {
            if (loggerListActive == null) {
                return List.of();
            }
            
            Object result = loggerListActive.invoke(null);
            if (!(result instanceof List<?> rawList)) {
                return List.of();
            }
            
            List<LoggerInfo> active = new ArrayList<>(rawList.size());
            for (Object entry : rawList) {
                if (!(entry instanceof Map<?, ?> info)) {
                    continue;
                }
                active.add(new LoggerInfo(
                        ((Number) info.get("id")).longValue(),
                        (String) info.get("method"),
                        ((Number) info.get("remaining_ms")).longValue(),
                        Boolean.TRUE.equals(info.get("has_filter"))
                ));
            }
            return active;
        }
        
        private List<String> listInjectedMethods() throws ReflectiveOperationException {
            if (loggerInjectedMethods == null) {
                return List.of();
            }
            
            Object value = loggerInjectedMethods.get(null);
            if (!(value instanceof Set<?> methods)) {
                return List.of();
            }
            
            List<String> result = new ArrayList<>(methods.size());
            for (Object method : methods) {
                if (method instanceof String methodName) {
                    result.add(methodName);
                }
            }
            return result;
        }
        
        private boolean isInjected(String methodId) throws ReflectiveOperationException {
            if (loggerIsInjected == null) {
                return false;
            }
            return Boolean.TRUE.equals(loggerIsInjected.invoke(null, methodId));
        }
        
        private Object createThrottleFilter(long intervalMs) throws ReflectiveOperationException {
            return invokeFilterFactory(filtersThrottle, intervalMs);
        }
        
        private Object createArgContainsFilter(int index, String substring) throws ReflectiveOperationException {
            return invokeFilterFactory(filtersArgContains, index, substring);
        }
        
        private Object createArgInstanceOfFilter(int index, String className) throws ReflectiveOperationException {
            return invokeFilterFactory(filtersArgInstanceOf, index, className);
        }
        
        private Object createSampleFilter(int n) throws ReflectiveOperationException {
            return invokeFilterFactory(filtersSample, n);
        }
    }
}
