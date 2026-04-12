plugins {
    java
}

description = "DebugBridge hooks - bootstrap classloader components"

dependencies {
    // Byte Buddy annotations only (for LoggingAdvice)
    // These are read at transformation time, not runtime
    compileOnly("net.bytebuddy:byte-buddy:1.14.18")
}

tasks.jar {
    archiveBaseName.set("debugbridge-hooks")

    manifest {
        // This JAR is loaded onto the bootstrap classloader
        // It must not contain application classes
    }
}
