plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test.junit)
}
