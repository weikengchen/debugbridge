plugins {
    id("fabric-loom") version "1.6-SNAPSHOT"
}

base {
    archivesName.set("debugbridge-26.1")
}

dependencies {
    implementation(project(":core"))

    minecraft("com.mojang:minecraft:26.1")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.16.0")
    // fabric-api version for 26.1 TBD — update when available
    // modImplementation("net.fabricmc.fabric-api:fabric-api:???+26.1")

    include(project(":core"))
    include("org.luaj:luaj-jse:3.0.1")
    include("org.java-websocket:Java-WebSocket:1.5.7")
    include("com.google.code.gson:gson:2.11.0")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
