plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    api(project(":domain"))
    api(project(":video"))
    api(project(":controller-hid"))

    testImplementation(project(":data-sim"))
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
