plugins { kotlin("jvm") version "2.1.20" }

repositories {
    if (providers.gradleProperty("usePublishedArtifact").isPresent) {
        maven {
            url = uri("../../build/repository")
            content { includeGroup("com.grozniy") }
        }
    }
    mavenCentral()
}
kotlin { jvmToolchain(17) }

dependencies {
    val libraryVersion = providers.gradleProperty("releaseVersion").orElse("0.2.0-SNAPSHOT").get()
    testImplementation("com.grozniy:concurrent-mockk:" + libraryVersion)
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}
