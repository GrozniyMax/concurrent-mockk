rootProject.name = "concurrent-mockk-example"

if (!providers.gradleProperty("usePublishedArtifact").isPresent) {
    includeBuild("../..")
}
