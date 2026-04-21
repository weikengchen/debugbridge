import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    id("net.fabricmc.fabric-loom") version "1.17.0-alpha.6"
}

base {
    archivesName.set("debugbridge-26.2-snapshot-3")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(26))
    }
}

dependencies {
    implementation(project(":core"))
    implementation("org.luaj:luaj-jse:3.0.1")
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    implementation("com.google.code.gson:gson:2.11.0")
    minecraft("com.mojang:minecraft:26.2-snapshot-3")
    implementation("net.fabricmc:fabric-loader:0.19.2")

    include(project(":core"))
    include("org.luaj:luaj-jse:3.0.1")
    include("org.java-websocket:Java-WebSocket:1.5.7")
    include("com.google.code.gson:gson:2.11.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(26)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
