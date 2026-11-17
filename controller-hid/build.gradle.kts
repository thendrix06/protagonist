plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    api(project(":domain"))

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
