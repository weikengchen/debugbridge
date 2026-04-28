package com.debugbridge.hooks;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.logging.*;

/**
 * Bootstrap-loaded bridge class containing the dispatch map and logging logic.
 * Must be on the bootstrap classloader so inlined advice bytecode can reference
 * it from any classloader context.
 *
 * This class does NOT perform bytecode injection - it only manages the logging
 * lifecycle. Injection is handled by DebugBridgeAgent.
 */
public class DebugBridgeLogger {

    private static final AtomicLong nextId = new AtomicLong(1);

    // methodId -> list of active loggers on that method
    private static final ConcurrentHashMap<String, CopyOnWriteArrayList<LogEntry>>
        active = new ConcurrentHashMap<>();

    private static final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DebugBridge-LogExpiry");
            t.setDaemon(true);
            return t;
        });

    // Methods that already have advice bytecode injected - never retransform twice
    // Public so DebugBridgeAgent (in agent module) can access it for error recovery
    public static final Set<String> injectedMethods = ConcurrentHashMap.newKeySet();

    // Reusable file loggers
    private static final ConcurrentHashMap<String, Logger> fileLoggers =
        new ConcurrentHashMap<>();

    // Error tracking for external queries
    private static final CopyOnWriteArrayList<LogError> recentErrors =
        new CopyOnWriteArrayList<>();
    private static final int MAX_ERRORS = 100;

    // Callback for triggering advice injection (set by DebugBridgeAgent)
    private static volatile Runnable onNeedInjection;
    private static volatile java.util.function.Consumer<String> injector;

    // ----------------------------------------------------------------
    // LogEntry - represents one active logger
    // ----------------------------------------------------------------

    public static class LogEntry {
        public final long id;
        public final String methodId;
        public final Logger logger;
        public final long expiresAt;           // System.nanoTime() deadline
        public final Predicate<Object[]> filter; // null = no filter
        public final boolean logArgs;
        public final boolean logReturn;
        public final boolean logTiming;
        public final int argDepth;
        private volatile boolean cancelled = false;

        public LogEntry(long id, String methodId, Logger logger,
                        long expiresAt, Predicate<Object[]> filter,
                        boolean logArgs, boolean logReturn,
                        boolean logTiming, int argDepth) {
            this.id = id;
            this.methodId = methodId;
            this.logger = logger;
            this.expiresAt = expiresAt;
            this.filter = filter;
            this.logArgs = logArgs;
            this.logReturn = logReturn;
            this.logTiming = logTiming;
            this.argDepth = argDepth;
        }

        public boolean isLive() {
            return !cancelled && System.nanoTime() - expiresAt < 0;
        }

        public void cancel() { cancelled = true; }
    }

    // ----------------------------------------------------------------
    // Hot path - called from inlined advice bytecode
    // ----------------------------------------------------------------

    /**
     * Called at method entry. Returns startTime (nonzero) if any logger
     * actually logged; 0 means "nothing logged, skip onExit."
     */
    public static long onEntry(String methodId, Object self, Object[] args) {
        CopyOnWriteArrayList<LogEntry> entries = active.get(methodId);
        if (entries == null || entries.isEmpty()) return 0L;

        long startTime = 0L;
        for (LogEntry e : entries) {
            if (!e.isLive()) continue;
            try {
                if (e.filter != null && !e.filter.test(args)) continue;

                if (startTime == 0L) startTime = System.nanoTime();

                StringBuilder sb = new StringBuilder(128)
                    .append("[ENTER] ").append(methodId);
                if (e.logArgs && args != null) {
                    sb.append(" args=");
                    appendArgs(sb, args, e.argDepth);
                }
                sb.append(" t=").append(Thread.currentThread().getName());
                e.logger.info(sb.toString());
            } catch (Throwable t) {
                reportError(e.id, methodId, "onEntry", t);
            }
        }
        return startTime;
    }

    /**
     * Called at method exit (normal return or exception).
     * Skipped entirely if startTime == 0 (nothing was logged at entry).
     */
    public static void onExit(String methodId, Object ret,
                              Throwable thrown, long startTime) {
        if (startTime == 0L) return;
        CopyOnWriteArrayList<LogEntry> entries = active.get(methodId);
        if (entries == null) return;

        long elapsed = System.nanoTime() - startTime;
        for (LogEntry e : entries) {
            if (!e.isLive()) continue;
            try {
                StringBuilder sb = new StringBuilder(128);
                if (thrown != null) {
                    sb.append("[THROW] ").append(methodId)
                      .append(' ').append(thrown.getClass().getSimpleName())
                      .append(": ").append(thrown.getMessage());
                } else {
                    sb.append("[EXIT]  ").append(methodId);
                    if (e.logReturn && ret != null) {
                        sb.append(" ret=")
                          .append(formatArg(ret, e.argDepth));
                    }
                }
                if (e.logTiming) {
                    sb.append(" elapsed=")
                      .append(elapsed / 1_000).append("us");
                }
                e.logger.info(sb.toString());
            } catch (Throwable t) {
                reportError(e.id, methodId, "onExit", t);
            }
        }
    }

    // ----------------------------------------------------------------
    // Control API - called from TCP/Lua handler
    // ----------------------------------------------------------------

    /**
     * Set the injector callback. Called by DebugBridgeAgent during init.
     */
    public static void setInjector(java.util.function.Consumer<String> inj) {
        injector = inj;
    }

    /**
     * Install a logger on a method. Returns the logger ID.
     * If the method has not been instrumented yet, triggers advice injection.
     *
     * @param methodId    Fully qualified method identifier,
     *                    e.g. "net.minecraft.server.MinecraftServer.tick"
     * @param outputFile  Path to log file, e.g. "/tmp/tick-trace.log"
     * @param duration    How long the logger stays active
     * @param filter      Optional predicate on method arguments (null = log all)
     * @param logArgs     Whether to log method arguments
     * @param logReturn   Whether to log return values
     * @param logTiming   Whether to log elapsed time
     * @param argDepth    Depth of argument inspection (0 = class@hash, 1+ = fields)
     * @return            Logger ID for cancel/status queries
     */
    public static long install(String methodId, String outputFile,
                               Duration duration,
                               Predicate<Object[]> filter,
                               boolean logArgs, boolean logReturn,
                               boolean logTiming, int argDepth) {
        long id = nextId.getAndIncrement();

        LogEntry entry = new LogEntry(
            id, methodId,
            getOrCreateLogger(outputFile),
            System.nanoTime() + duration.toNanos(),
            filter, logArgs, logReturn, logTiming, argDepth
        );

        active.computeIfAbsent(methodId, k -> new CopyOnWriteArrayList<>())
              .add(entry);

        // Schedule automatic expiry
        scheduler.schedule(() -> remove(id, methodId),
                           duration.toMillis(), TimeUnit.MILLISECONDS);

        // Inject advice if this method hasn't been instrumented yet
        if (injectedMethods.add(methodId)) {
            if (injector != null) {
                try {
                    injector.accept(methodId);
                } catch (RuntimeException | Error e) {
                    remove(id, methodId);
                    injectedMethods.remove(methodId);
                    throw e;
                }
            } else {
                System.err.println("[DebugBridge] No injector registered, "
                    + "cannot instrument " + methodId);
            }
        }

        return id;
    }

    /**
     * Cancel a logger immediately by ID.
     * @return true if the logger was found and cancelled
     */
    public static boolean cancel(long id) {
        for (var list : active.values()) {
            for (LogEntry e : list) {
                if (e.id == id) {
                    e.cancel();
                    list.remove(e);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * List all currently active (non-expired, non-cancelled) loggers.
     */
    public static List<Map<String, Object>> listActive() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var entries : active.values()) {
            for (LogEntry e : entries) {
                if (e.isLive()) {
                    long remainingMs = Math.max(0,
                        (e.expiresAt - System.nanoTime()) / 1_000_000);
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("id", e.id);
                    info.put("method", e.methodId);
                    info.put("remaining_ms", remainingMs);
                    info.put("has_filter", e.filter != null);
                    result.add(info);
                }
            }
        }
        return result;
    }

    /**
     * Get recent errors from logger execution.
     */
    public static List<Map<String, Object>> getErrors() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (LogError err : recentErrors) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("logger_id", err.loggerId);
            info.put("method", err.methodId);
            info.put("phase", err.phase);
            info.put("error", err.message);
            info.put("timestamp_ms", err.timestampMs);
            result.add(info);
        }
        return result;
    }

    /**
     * Check whether a method already has advice injected.
     */
    public static boolean isInjected(String methodId) {
        return injectedMethods.contains(methodId);
    }

    // ----------------------------------------------------------------
    // Internal helpers
    // ----------------------------------------------------------------

    private static void remove(long id, String methodId) {
        CopyOnWriteArrayList<LogEntry> list = active.get(methodId);
        if (list == null) return;
        list.removeIf(e -> e.id == id);
        // Do NOT remove the key or retransform - advice stays for reuse
    }

    private static void reportError(long loggerId, String methodId,
                                    String phase, Throwable t) {
        LogError err = new LogError(loggerId, methodId, phase,
            t.getClass().getSimpleName() + ": " + t.getMessage(),
            System.currentTimeMillis());

        recentErrors.add(err);
        // Cap error list size
        while (recentErrors.size() > MAX_ERRORS) {
            recentErrors.remove(0);
        }

        System.err.println("[DebugBridge] Logger #" + loggerId
            + " error in " + phase + " of " + methodId
            + ": " + t.getMessage());
    }

    private static Logger getOrCreateLogger(String path) {
        return fileLoggers.computeIfAbsent(path, p -> {
            Logger logger = Logger.getLogger("DebugBridge." + p);
            logger.setUseParentHandlers(false);
            try {
                FileHandler fh = new FileHandler(p, /* append */ true);
                fh.setFormatter(new SimpleFormatter() {
                    @Override
                    public String format(LogRecord r) {
                        return String.format("%1$tT.%1$tL %2$s%n",
                            r.getMillis(), r.getMessage());
                    }
                });
                logger.addHandler(fh);
            } catch (IOException e) {
                // Fall back to stderr
                logger.addHandler(new ConsoleHandler());
            }
            return logger;
        });
    }

    private static void appendArgs(StringBuilder sb, Object[] args, int depth) {
        sb.append('[');
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(formatArg(args[i], depth));
        }
        sb.append(']');
    }

    /**
     * Format an object for logging output.
     * depth=0: ClassName@hashHex
     * depth=1+: attempt to extract useful fields via reflection
     */
    static String formatArg(Object obj, int depth) {
        if (obj == null) return "null";
        if (obj instanceof String) return "\"" + obj + "\"";
        if (obj instanceof Number || obj instanceof Boolean) {
            return obj.toString();
        }
        if (depth == 0) {
            return obj.getClass().getSimpleName() + "@"
                + Integer.toHexString(System.identityHashCode(obj));
        }
        // depth >= 1: attempt toString(), fall back to class@hash
        try {
            String s = obj.toString();
            if (s.contains("@")) {
                // Default Object.toString() - not useful, use class@hash
                return obj.getClass().getSimpleName() + "@"
                    + Integer.toHexString(System.identityHashCode(obj));
            }
            // Truncate long strings
            return s.length() > 200 ? s.substring(0, 200) + "..." : s;
        } catch (Throwable t) {
            return obj.getClass().getSimpleName() + "@"
                + Integer.toHexString(System.identityHashCode(obj))
                + "[toString threw " + t.getClass().getSimpleName() + "]";
        }
    }

    private static class LogError {
        final long loggerId;
        final String methodId;
        final String phase;
        final String message;
        final long timestampMs;

        LogError(long loggerId, String methodId, String phase,
                 String message, long timestampMs) {
            this.loggerId = loggerId;
            this.methodId = methodId;
            this.phase = phase;
            this.message = message;
            this.timestampMs = timestampMs;
        }
    }
}
