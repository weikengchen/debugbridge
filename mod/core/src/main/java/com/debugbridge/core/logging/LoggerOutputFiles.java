package com.debugbridge.core.logging;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Shared output-file naming for every LoggerService implementation.
 */
public final class LoggerOutputFiles {
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private LoggerOutputFiles() {
    }

    public static String generate(String methodId) {
        String safeName = methodId.replaceAll("[^a-zA-Z0-9.]", "_");
        if (safeName.length() > 50) {
            safeName = safeName.substring(safeName.length() - 50);
        }
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String fileName = "debugbridge-" + safeName + "-" + timestamp + ".log";
        return Path.of(System.getProperty("java.io.tmpdir")).resolve(fileName).toString();
    }
}
