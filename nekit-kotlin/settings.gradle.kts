plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.7.0"
}

rootProject.name = "nekit-kotlin"

toolchainManagement {
    jvm {
        // The foojay-resolver-convention plugin automatically configures
        // a repository that uses FoojayToolchainResolver.
        // No need to explicitly define it again if the plugin is applied.
    }
}
