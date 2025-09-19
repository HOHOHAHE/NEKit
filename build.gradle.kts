import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.library")
    kotlin("android") version "1.9.20"
    id("maven-publish")
}

group = "com.github.nekit"
version = "1.0.0"

android {
    namespace = "com.github.nekit"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    // Kotlin Standard Library
    implementation(kotlin("stdlib"))

    // Kotlin Coroutines for Android
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // YAML Parsing (Jackson with YAML Dataformat and Kotlin Module)
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")

    // GeoIP Lookup (MaxMind GeoIP2)
    implementation("com.maxmind.geoip2:geoip2:4.0.1")

    // Logging (SLF4J API and Android compatible implementation)
    implementation("org.slf4j:slf4j-api:2.0.7")
    implementation("org.slf4j:slf4j-android:1.7.36")

    // Cryptography (BouncyCastle Provider)
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.77")

    // Networking (OkHttp for Android networking)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    
    // Ktor for coroutine-based networking
    implementation("io.ktor:ktor-network:2.3.7")
    implementation("io.ktor:ktor-network-tls:2.3.7")

    // JNA for JNI access to native libraries
    implementation("net.java.dev.jna:jna:5.13.0")

    // Android specific dependencies
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Test dependencies
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

// Configure Source Sets to include src_kt directory
android {
    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src_kt")
            java.srcDirs("src_kt")
        }
    }
}

// Publishing configuration
publishing {
    publications {
        create<MavenPublication>("release") {
            from(components["release"])
            
            groupId = "com.github.nekit"
            artifactId = "nekit-kotlin"
            version = "1.0.0"
            
            pom {
                name.set("NEKit Kotlin")
                description.set("A Kotlin port of NEKit networking library for Android")
                url.set("https://github.com/zhuhaow/NEKit")
                
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                
                developers {
                    developer {
                        id.set("nekit")
                        name.set("NEKit Team")
                    }
                }
            }
        }
    }
}
