pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "debugbridge"

include(":core")
include(":hooks")
include(":agent")
// Fabric modules use different Loom and Java levels, so keep each module explicit.
include(":fabric-1.19")
include(":fabric-1.21.11")
include(":fabric-26.2-dev")
// include(":fabric-26.1")
