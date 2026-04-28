package com.debugbridge.hooks;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Predefined filter factories that can be constructed from
 * external commands (TCP/Lua). All filters are safe - they
 * catch exceptions internally and return false on error.
 */
public class LogFilters {
    
    /**
     * Log only when argument at index matches a value via equals().
     */
    public static Predicate<Object[]> argEquals(int index, Object value) {
        return args -> {
            try {
                return args != null && args.length > index
                        && Objects.equals(args[index], value);
            } catch (Throwable t) {
                return false;
            }
        };
    }
    
    /**
     * Log only when argument.toString() contains a substring.
     */
    public static Predicate<Object[]> argContains(int index, String substring) {
        return args -> {
            try {
                return args != null && args.length > index
                        && args[index] != null
                        && args[index].toString().contains(substring);
            } catch (Throwable t) {
                return false;
            }
        };
    }
    
    /**
     * Log only when argument is an instance of a type (partial name match).
     */
    public static Predicate<Object[]> argInstanceOf(int index, String classNameFragment) {
        return args -> {
            try {
                return args != null && args.length > index
                        && args[index] != null
                        && args[index].getClass().getName().contains(classNameFragment);
            } catch (Throwable t) {
                return false;
            }
        };
    }
    
    /**
     * Rate-limit: log at most once per intervalMs milliseconds.
     */
    public static Predicate<Object[]> throttle(long intervalMs) {
        AtomicLong lastLog = new AtomicLong(0);
        return args -> {
            long now = System.currentTimeMillis();
            long last = lastLog.get();
            return now - last >= intervalMs
                    && lastLog.compareAndSet(last, now);
        };
    }
    
    /**
     * Sample: log only every N calls.
     */
    public static Predicate<Object[]> sample(int n) {
        AtomicLong counter = new AtomicLong(0);
        return args -> counter.incrementAndGet() % n == 0;
    }
    
    /**
     * Combine multiple filters with AND logic.
     */
    @SafeVarargs
    public static Predicate<Object[]> and(Predicate<Object[]>... filters) {
        return args -> {
            for (Predicate<Object[]> f : filters) {
                if (!f.test(args)) return false;
            }
            return true;
        };
    }
    
    /**
     * Combine multiple filters with OR logic.
     */
    @SafeVarargs
    public static Predicate<Object[]> or(Predicate<Object[]>... filters) {
        return args -> {
            for (Predicate<Object[]> f : filters) {
                if (f.test(args)) return true;
            }
            return false;
        };
    }
    
    /**
     * Negate a filter.
     */
    public static Predicate<Object[]> not(Predicate<Object[]> filter) {
        return args -> !filter.test(args);
    }
    
    /**
     * Always log (no filtering).
     */
    public static Predicate<Object[]> always() {
        return args -> true;
    }
    
    /**
     * Never log (for testing).
     */
    public static Predicate<Object[]> never() {
        return args -> false;
    }
}
