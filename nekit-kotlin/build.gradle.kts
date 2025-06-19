plugins {
    kotlin("jvm") version "1.9.22" // Or latest stable
    java // Apply the Java plugin to configure Java toolchains
}

java { // Add this block for JVM toolchain configuration
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11)) // Set default toolchain to 11
    }
}

group = "io.github.hohohahe"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("io.netty:netty-handler:4.1.100.Final")
    implementation("io.netty:netty-codec:4.1.100.Final")
        implementation("io.netty:netty-codec-http:4.1.100.Final")
    implementation("io.netty:netty-transport:4.1.100.Final")

    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.11")

        // Jackson for YAML Configuration
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.3") 
        implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.3") // For YAML

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testImplementation("io.mockk:mockk:1.13.8")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        // Set main compilation JVM target to 1.8 if that's the library's target
        // If the library itself can be 11, then this can be 11 too.
        // For broad compatibility, libraries often target 1.8.
        jvmTarget = "1.8" 
        freeCompilerArgs = freeCompilerArgs + "-Xjvm-default=all-compatibility"
    }
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    kotlinOptions {
        jvmTarget = "11" 
    }
}

// Ensure Java compilation tasks also align if there are Java sources or for consistency
tasks.withType<JavaCompile>().configureEach {
    // For main Java sources, align with Kotlin's main target if any Java is used
    if (name == "compileJava") {
        sourceCompatibility = JavaVersion.VERSION_1_8.toString()
        targetCompatibility = JavaVersion.VERSION_1_8.toString()
    }
    // For test Java sources
    if (name == "compileTestJava") {
        sourceCompatibility = JavaVersion.VERSION_11.toString()
        targetCompatibility = JavaVersion.VERSION_11.toString()
    }
}


tasks.test {
    useJUnitPlatform()
    // Set the toolchain for the test task if it needs to be different from the project default
    // If the project default (from java.toolchain) is 11, this might not be strictly needed
    // but explicit doesn't hurt.
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(11))
    })
}
