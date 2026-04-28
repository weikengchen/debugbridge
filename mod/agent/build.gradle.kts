plugins {
    java
}

description = "DebugBridge agent - Java Agent for runtime instrumentation"

val hooksJarTask = project(":hooks").tasks.named<Jar>("jar")
val stageHooksJarForJavaAgent by tasks.registering(Copy::class) {
    from(hooksJarTask.flatMap { it.archiveFile })
    into(layout.buildDirectory.dir("libs"))
}

dependencies {
    // Byte Buddy for bytecode manipulation
    implementation("net.bytebuddy:byte-buddy:1.18.8")
    implementation("net.bytebuddy:byte-buddy-agent:1.18.8")

    // The hooks module is loaded from debugbridge-hooks.jar on the bootstrap
    // classloader. Do not embed it in the agent jar or the game classloader can
    // resolve duplicate hook classes from the wrong code source.
    compileOnly(project(":hooks"))

    // Depend on core module (for LoggerService interface)
    compileOnly(project(":core"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation(project(":core"))
    testImplementation(project(":hooks"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar {
    archiveBaseName.set("debugbridge-agent")
    dependsOn(stageHooksJarForJavaAgent)

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
            "Boot-Class-Path" to hooksJarTask.flatMap { it.archiveFileName }.get()
        )
    }
}

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.jar)
    systemProperty("debugbridge.agent.jar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
}
