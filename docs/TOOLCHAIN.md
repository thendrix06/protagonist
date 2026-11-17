# Protagonist toolchain pins

All versions are pinned in `gradle/libs.versions.toml` (single source of truth) and `gradle/wrapper/gradle-wrapper.properties`.

| Component | Pin | Provenance (2026-11-17) |
|---|---|---|
| Gradle wrapper | 8.14.3 | Verified: used for every build in the authoring environment. Kotlin 2.4.21 warns that its successor (Kotlin 2.5) will need ≥ 8.14.4; bump the wrapper together with the Kotlin bump. |
| JDK | 17+ (bytecode target 17) | Verified with OpenJDK 21. |
| Kotlin / KGP / Compose compiler plugin / serialization plugin | 2.4.21 | Verified on Maven Central and used in passing builds. |
| kotlinx-coroutines | 1.11.0 | Verified (Maven Central, used in passing builds). |
| kotlinx-serialization-json | 1.13.0 | Verified (Maven Central, used in passing builds). |
| OkHttp | 5.5.1 | Verified (Maven Central, compiled). |
| Spotless / ktlint | 8.11.0 / 1.8.0 | Verified (used for `spotlessCheck`). |
| Robolectric | 4.16 | Exists on Maven Central; **not yet executed** (Android tests not run). |
| Android Gradle Plugin | 8.13.0 | **Unverified in this environment.** Google Maven (`dl.google.com`) was blocked by the authoring sandbox's network policy. This is the last stable line the author can vouch for. Upgrade to the current stable AGP (and Gradle) when first building with Google Maven access. |
| Compose BOM | 2025.08.00 | **Unverified** (Google Maven). Same note. |
| AndroidX activity-compose / lifecycle / core-ktx | 1.10.1 / 2.9.2 / 1.16.0 | **Unverified** (Google Maven). |
| AndroidX test core / ext-junit / runner | 1.7.0 / 1.3.0 / 1.7.0 | **Unverified** (Google Maven). |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 | SDK platform 36 and build-tools 35–37 were present in the authoring environment. |

## Why the root build uses `buildscript {}` for plugins

AGP is put on the classpath only when Android modules are included. `-Pprotagonist.android=false` therefore builds
and tests all platform-neutral modules from Maven Central alone. This is useful for back-end engineers and for CI
runners without the Android SDK. The Google repository is content-filtered to `androidx.*`, `com.android.*` and
`com.google.*`.

## First build with Google Maven access: checklist

1. Run `./gradlew --refresh-dependencies spotlessCheck test assembleDebug`.
2. If AGP 8.13.0 or BOM 2025.08.00 is no longer the newest stable version, update `libs.versions.toml`. If you
   move to AGP 9.x, also: (a) drop `id("org.jetbrains.kotlin.android")` from `:app` and `:design-system` (AGP 9
   has built-in Kotlin), and (b) bump the Gradle wrapper to the version AGP requires.
3. Fix any compile errors in `:app` / `:design-system`. These modules were written without a compiler, against
   stable Compose Material 3 APIs.
4. Record the results in `docs/IMPLEMENTATION_NOTES.md`.
