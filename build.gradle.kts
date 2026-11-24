import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.1.20"
    id("java-library")
    id("maven-publish")
}

val jitpackBuild = providers.environmentVariable("JITPACK").orNull == "true"
group = if (jitpackBuild) providers.environmentVariable("GROUP").get() else "com.grozniy"
version = providers.gradleProperty("releaseVersion").orNull
    ?: if (jitpackBuild) providers.environmentVariable("VERSION").get()
    else providers.gradleProperty("projectVersion").get()

providers.gradleProperty("releaseVersion").orNull?.let {
    require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(it)) {
        "releaseVersion must be a stable version such as 0.1.0"
    }
}
description = "Test-scoped MockK isolation for parallel JUnit 5 tests"

repositories { mavenCentral() }

kotlin { jvmToolchain(17) }
java { withSourcesJar() }

dependencies {
    api("io.mockk:mockk-jvm:1.14.6") { version { strictly("1.14.6") } }
    implementation("io.mockk:mockk-agent-api-jvm:1.14.6")
    implementation("net.bytebuddy:byte-buddy:1.15.11")
    implementation("net.bytebuddy:byte-buddy-agent:1.15.11")
    api("org.junit.jupiter:junit-jupiter-api:5.12.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    // Fixtures are executed explicitly through JUnit Launcher by integration tests.
    include("**/*Test.class")
    testLogging { events("failed", "skipped") }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("concurrent-mockk")
                description.set(project.description)
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit/")
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "localPreview"
            url = layout.buildDirectory.dir("repository").get().asFile.toURI()
        }
    }
}

// Keep archives reproducible and prepare the same files locally and in CI.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val releaseArtifactBaseName = project.name + "-" + project.version

val stageReleaseArtifacts by tasks.registering(Sync::class) {
    dependsOn(tasks.jar, tasks.named("sourcesJar"), tasks.named("generatePomFileForMavenJavaPublication"))
    into(layout.buildDirectory.dir("release"))
    from(tasks.jar)
    from(tasks.named("sourcesJar"))
    from(layout.buildDirectory.file("publications/mavenJava/pom-default.xml")) {
        rename { releaseArtifactBaseName + ".pom" }
    }
}

tasks.register("releaseArtifacts") {
    group = "publishing"
    description = "Stages JAR, sources, POM and SHA-256 checksums in build/release"
    dependsOn(stageReleaseArtifacts)
    doLast {
        val directory = layout.buildDirectory.dir("release").get().asFile
        val manifest = directory.listFiles().orEmpty()
            .filter { it.isFile && it.name != "SHA256SUMS" }
            .sortedBy { it.name }
            .joinToString(separator = "\n", postfix = "\n") { file ->
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                digest + "  " + file.name
            }
        directory.resolve("SHA256SUMS").writeText(manifest)
    }
}
