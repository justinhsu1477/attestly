rootProject.name = "attestly"

include("core")
include("store-sqlite")
include("embedder-openai")
include("demo")

// Future modules (uncomment as they land):
// include("spring-boot-starter")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    // Version catalog auto-loads from gradle/libs.versions.toml — no explicit registration needed.
}
