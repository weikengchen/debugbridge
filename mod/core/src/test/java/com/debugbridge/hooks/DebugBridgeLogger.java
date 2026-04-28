package com.debugbridge.hooks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

public final class DebugBridgeLogger {
    private static final AtomicLong NEXT_ID = new AtomicLong(1);

    public static final Set<String> injectedMethods = new LinkedHashSet<>();

    public static String lastMethodId;
    public static String lastOutputFile;
    public static Duration lastDuration;
    public static Predicate<Object[]> lastFilter;
    public static boolean lastLogArgs;
    public static boolean lastLogReturn;
    public static boolean lastLogTiming;
    public static int lastArgDepth;

    private static final Map<Long, Map<String, Object>> active = new LinkedHashMap<>();

    private DebugBridgeLogger() {
    }

    public static void reset() {
        NEXT_ID.set(1);
        injectedMethods.clear();
        active.clear();
        lastMethodId = null;
        lastOutputFile = null;
        lastDuration = null;
        lastFilter = null;
        lastLogArgs = false;
        lastLogReturn = false;
        lastLogTiming = false;
        lastArgDepth = 0;
    }

    public static long install(String methodId, String outputFile, Duration duration,
                               Predicate<Object[]> filter, boolean logArgs,
                               boolean logReturn, boolean logTiming, int argDepth) {
        long id = NEXT_ID.getAndIncrement();
        injectedMethods.add(methodId);
        lastMethodId = methodId;
        lastOutputFile = outputFile;
        lastDuration = duration;
        lastFilter = filter;
        lastLogArgs = logArgs;
        lastLogReturn = logReturn;
        lastLogTiming = logTiming;
        lastArgDepth = argDepth;

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("method", methodId);
        entry.put("remaining_ms", duration.toMillis());
        entry.put("has_filter", filter != null);
        active.put(id, entry);
        return id;
    }

    public static boolean cancel(long id) {
        return active.remove(id) != null;
    }

    public static List<Map<String, Object>> listActive() {
        return new ArrayList<>(active.values());
    }

    public static boolean isInjected(String methodId) {
        return injectedMethods.contains(methodId);
    }
}
