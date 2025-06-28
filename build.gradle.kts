import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.0" // Using a recent Kotlin version
    application // For setting mainClassName and creating distributions if needed
}

group = "com.example.nekit" // Placeholder group
version = "0.1.0" // Placeholder version

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin Standard Library
    implementation(kotlin("stdlib")) // Or specific variant like stdlib-jdk8

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3") // Check for latest stable version

    // YAML Parsing (Jackson with YAML Dataformat and Kotlin Module)
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2") // Check for latest
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2") // Ensure version matches other Jackson libs

    // GeoIP Lookup (MaxMind GeoIP2)
    implementation("com.maxmind.geoip2:geoip2:4.0.1") // Check for latest (ensure it's compatible with your license/needs)

    // Logging (SLF4J API and Logback Implementation)
    implementation("org.slf4j:slf4j-api:2.0.7") // Check for latest
    runtimeOnly("ch.qos.logback:logback-classic:1.4.8") // Check for latest

    // Cryptography (BouncyCastle Provider)
    // Use bcprov-jdk18on for JDK 1.8+ or bcprov-jdk15on for older. Assuming modern JDK.
    implementation("org.bouncycastle:bcprov-jdk18on:1.77") // Check for latest

    // Networking (Netty for low-level socket control and async I/O)
    // Netty is a good choice for implementing custom TCP/UDP clients and servers,
    // and for TUN/TAP JNI integration if building that part.
    implementation("io.netty:netty-all:4.1.100.Final") // Check for latest stable version

    // JNA for JNI access to native libraries (TUN/TAP, Libsodium, tun2socks)
    implementation("net.java.dev.jna:jna:5.13.0")

    // TODO: Add other dependencies as they become clear, e.g.:
    // - Specific Libsodium JVM binding if one is chosen (e.g., Kalium, libsodium-jni)
}

// Configure Source Sets to include only src_kt/Utils for this compilation pass
sourceSets {
    main {
        kotlin {
            setSrcDirs(listOf("src_kt/Utils")) // Temporarily focus on Utils
            // To revert, use: srcDir("src_kt")
        }
        resources {
            srcDir("resources") // If you have resource files
        }
    }
    test {
        kotlin {
            setSrcDirs(listOf("test_kt/Utils")) // Assuming tests for Utils might be here
            // To revert, use: srcDir("test_kt")
        }
        resources {
            srcDir("test_resources")
        }
    }
}

// Configure Kotlin compilation options
tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "11" // Or "1.8", "17", etc., depending on project requirements
        freeCompilerArgs = listOf("-Xjsr305=strict") // Example compiler arg
    }
}

// Application plugin configuration (optional, for running the application)
application {
    // TODO: Define the main class name once an entry point is established
    // Example: mainClass.set("com.example.nekit.MainKt")
    // For now, it can be commented out or set to a placeholder if no main function exists yet.
    mainClass.set("com.example.nekit.PlaceholderMainKt") // Requires a file with fun main()
}

// If creating a fat JAR (optional)
// tasks.jar {
//     manifest {
//         attributes(mapOf("Main-Class" to application.mainClass.get()))
//     }
//     from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
// }

// Placeholder for a main function to make the application plugin happy if no other entry point exists yet.
// Create a file e.g. src_kt/com/example/nekit/PlaceholderMain.kt with:
// package com.example.nekit
// fun main() { println("NEKit-Kotlin placeholder main.") }
