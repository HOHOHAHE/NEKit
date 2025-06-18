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
    implementation("io.netty:netty-codec-http:4.1.100.Final") // <--- ADD THIS LINE
    implementation("io.netty:netty-transport:4.1.100.Final")
    // implementation("io.netty:netty-transport-native-epoll:4.1.100.Final:linux-x86_64")

    // Logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.11")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.3") // Use the latest 2.15.x or newer if available
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.3") // Use the latest 2.15.x or newer if available
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0") // This was in the plan, ensure it's there or remove if jackson is sole choice


    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(kotlin("test-junit5")) // org.jetbrains.kotlin:kotlin-test-junit5, version from Kotlin plugin
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // junit-jupiter-api is pulled by junit-jupiter
    // junit-jupiter-engine is pulled by junit-jupiter
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0") // Explicitly keep engine for runtime if not covered by junit-jupiter for test runtime
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
