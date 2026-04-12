plugins {
    java
}

description = "DebugBridge agent - Java Agent for runtime instrumentation"

dependencies {
    // Byte Buddy for bytecode manipulation
    implementation("net.bytebuddy:byte-buddy:1.14.18")
    implementation("net.bytebuddy:byte-buddy-agent:1.14.18")

    // Depend on hooks module (for DebugBridgeLogger, LogFilters)
    implementation(project(":hooks"))

    // Depend on core module (for LoggerService interface)
    compileOnly(project(":core"))
}

tasks.jar {
    archiveBaseName.set("debugbridge-agent")

    // Include all dependencies in the JAR (fat JAR for agent)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Premain-Class" to "com.debugbridge.agent.DebugBridgeAgent",
            "Agent-Class" to "com.debugbridge.agent.DebugBridgeAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
            "Boot-Class-Path" to "debugbridge-hooks.jar"
        )
    }
}
