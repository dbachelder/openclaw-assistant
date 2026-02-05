pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots") }
        maven { url = uri("https://jitpack.io") }
        // Adding the official Sherpa-ONNX repository explicitly
        maven { url = uri("https://k2-fsa.github.io/sherpa/onnx/android/") }
    }
}

rootProject.name = "OpenClawAssistant"
include(":app")
