pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots") }
        maven { url = uri("https://jitpack.io") }
        // Explicitly including the Sherpa-ONNX repository
        maven { url = uri("https://k2-fsa.github.io/sherpa/onnx/android/") }
    }
}

rootProject.name = "OpenClawAssistant"
include(":app")
