rootProject.name = "attestly"

include("core")

// Future modules (uncomment as they land):
// include("spring-boot-starter")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    // Version catalog auto-loads from gradle/libs.versions.toml — no explicit registration needed.
}
