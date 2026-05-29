// SQLite-backed MemoryRepository adapter.
// Pure-JVM via xerial sqlite-jdbc; vectors are stored as BLOBs and compared with
// application-level cosine similarity (no native extension). Depends on :core only.

plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.sqlite.jdbc)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}
