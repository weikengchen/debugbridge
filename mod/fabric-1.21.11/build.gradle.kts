plugins {
    id("fabric-loom") version "1.9.2"
}

base {
    archivesName.set("debugbridge-1.21.11")
}

dependencies {
    implementation(project(":core"))
    implementation("org.java-websocket:Java-WebSocket:1.5.7")

    minecraft("com.mojang:minecraft:1.21.11")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.16.10")
    // fabric-api is not required by the mod itself — uncomment and set a version
    // matching 1.21.11 if you need event hooks from fabric-api.
    // modImplementation("net.fabricmc.fabric-api:fabric-api:???+1.21.11")

    // Include core's dependencies
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
