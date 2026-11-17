// Root build for Protagonist.
//
// Plugins are put on the root buildscript classpath (instead of a root `plugins {}` block) so that the
// Android Gradle Plugin is only resolved when Android modules are included. This keeps the platform-neutral
// modules buildable on hosts without Google Maven access: ./gradlew -Pprotagonist.android=false test
buildscript {
    val includeAndroid =
        gradle.startParameter.projectProperties["protagonist.android"]?.toBooleanStrictOrNull()
            ?: (project.findProperty("protagonist.android") as String?)?.toBooleanStrictOrNull()
            ?: true
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
        gradlePluginPortal()
    }
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
        classpath(libs.kotlin.serialization.plugin)
        classpath(libs.kotlin.compose.plugin)
        classpath(libs.spotless.plugin)
        if (includeAndroid) {
            classpath(libs.android.gradle.plugin)
        }
    }
}

val ktlintVersion = libs.versions.ktlint.get()

subprojects {
    apply(plugin = "com.diffplug.spotless")
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint(ktlintVersion).editorConfigOverride(
                mapOf(
                    "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
                    "ktlint_standard_function-naming" to "disabled",
                ),
            )
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(ktlintVersion)
        }
    }

    // Platform-neutral modules target Java 17 bytecode so Android modules can consume them.
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            explicitApi()
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                allWarningsAsErrors.set(true)
            }
        }
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            if (name.contains("Test")) compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
        }
    }

    tasks.withType<Test>().configureEach {
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
