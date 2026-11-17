plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    api(project(":domain"))
    api(project(":data-remote"))

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
