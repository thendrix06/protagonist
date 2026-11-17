plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":domain"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.withType<Test>().configureEach {
    // Contract tests read the shared protocol contract from the repository root.
    systemProperty("capture.contract", rootProject.file("contracts/capture_protocol.json").absolutePath)
    inputs.file(rootProject.file("contracts/capture_protocol.json"))
}
