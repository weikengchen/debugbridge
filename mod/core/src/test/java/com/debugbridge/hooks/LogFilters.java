package com.debugbridge.hooks;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

public final class LogFilters {
    private LogFilters() {
    }

    public static Predicate<Object[]> throttle(long intervalMs) {
        AtomicLong lastLog = new AtomicLong(Long.MIN_VALUE / 4);
        return args -> {
            long now = System.currentTimeMillis();
            long last = lastLog.get();
            return now - last >= intervalMs && lastLog.compareAndSet(last, now);
        };
    }

    public static Predicate<Object[]> argContains(int index, String substring) {
        return args -> args != null
                && args.length > index
                && args[index] != null
                && args[index].toString().contains(substring);
    }

    public static Predicate<Object[]> argInstanceOf(int index, String classNameFragment) {
        return args -> args != null
                && args.length > index
                && args[index] != null
                && args[index].getClass().getName().contains(classNameFragment);
    }

    public static Predicate<Object[]> sample(int n) {
        AtomicLong counter = new AtomicLong();
        return args -> counter.incrementAndGet() % n == 0;
    }
}
