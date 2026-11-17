// Android modules are included by default. Disable with -Pprotagonist.android=false on hosts that cannot reach
// Google Maven: the platform-neutral modules then build and test from Maven Central alone.
val includeAndroid = providers.gradleProperty("protagonist.android").orNull?.toBooleanStrictOrNull() ?: true

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (includeAndroid) {
            google {
                content {
                    includeGroupByRegex("androidx\\..*")
                    includeGroupByRegex("com\\.android(\\..*)?")
                    includeGroupByRegex("com\\.google\\..*")
                }
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Protagonist"

// Platform-neutral modules: build and test on any JVM, no Android SDK or Google Maven needed.
include(":domain")
include(":data-sim")
include(":data-remote")
include(":video")
include(":controller-hid")
include(":presentation")

if (includeAndroid) {
    include(":design-system")
    include(":app")
}
