# Protagonist toolchain pins

All versions are pinned in `gradle/libs.versions.toml` (single source of truth) and `gradle/wrapper/gradle-wrapper.properties`.
Every pin below was resolved and used in a successful build on 2026-11-17, unless marked otherwise.

| Component | Pin |
|---|---|
| Gradle wrapper | 9.9.1 |
| JDK | 17+ (bytecode target 17); verified with OpenJDK 21 |
| Android Gradle Plugin | 9.5.1 (built-in Kotlin: no `org.jetbrains.kotlin.android` plugin) |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 (the current AndroidX releases require compileSdk 37) |
| Kotlin / Compose compiler plugin / serialization plugin | 2.4.21 |
| Compose BOM | 2026.09.00 |
| activity-compose / lifecycle / core-ktx | 1.13.0 / 2.11.0 / 1.19.1 |
| AndroidX test core / ext-junit / runner | 1.7.0 / 1.3.0 / 1.7.0 |
| kotlinx-coroutines / kotlinx-serialization-json | 1.11.0 / 1.13.0 |
| OkHttp | 5.5.1 |
| Robolectric | 4.18 (resolves, but its large runtime jars could not be downloaded in the authoring sandbox; see IMPLEMENTATION_NOTES) |
| Spotless / ktlint | 8.11.0 / 1.8.0 |

## Why the root build uses `buildscript {}` for plugins

AGP is put on the classpath only when Android modules are included. `-Pprotagonist.android=false` therefore builds
and tests all platform-neutral modules from Maven Central alone, which is useful for back-end engineers and for
CI runners without the Android SDK. The Google repository is content-filtered to `androidx.*`, `com.android.*`
and `com.google.*`.
