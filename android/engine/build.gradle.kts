// Pure-JVM ChatEngine module (advisor GO 2026-10-27, rebuild order step 3).
// NO Android dependencies: compile-only JVM Kotlin, so the engine is testable
// on the JVM and portable. The app wires it to transport + UI later (rebuild
// phase 3); this module defines the domain, SSE parsing, error taxonomy,
// budget guards, history budget, session state, and the send loop with
// cancellation. Contract tests run against a hand-rolled localhost fake server.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin { jvmToolchain(17) }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

tasks.test {
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
