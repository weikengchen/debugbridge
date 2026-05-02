plugins {
    java
}

allprojects {
    group = "com.debugbridge"
    version = "1.1.0"

    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
    }
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(17)
    }
}
