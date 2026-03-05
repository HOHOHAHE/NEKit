plugins {
    kotlin("jvm") version "1.9.20"
    application
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.slf4j:slf4j-simple:1.7.36")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.fasterxml.jackson.core:jackson-core:2.13.5")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.13.5")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.5")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.13.5")
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
    implementation("net.java.dev.jna:jna:5.13.0")
    implementation("com.maxmind.geoip2:geoip2:4.0.1")
    implementation("io.netty:netty-all:4.1.100.Final")
    implementation("io.ktor:ktor-network:2.3.7")
    implementation("io.ktor:ktor-network-tls:2.3.7")
}



application {
    mainClass.set("nekit.main.MainKt")
}
