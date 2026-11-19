plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

application {
    mainClass.set("capture.core.reference.ReferenceCoreServerKt")
}

dependencies {
    api(project(":data-sim"))
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
