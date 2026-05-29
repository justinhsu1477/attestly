// End-to-end demo wiring core + SQLite store + OpenAI embedder together.
// `./gradlew :demo:run` (with OPENAI_API_KEY set) drives a real add→search→explain→forget.

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":store-sqlite"))
    implementation(project(":embedder-openai"))
    implementation(libs.kotlin.stdlib)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.wiremock)
}

application {
    mainClass.set("io.attestly.demo.MainKt")
}
