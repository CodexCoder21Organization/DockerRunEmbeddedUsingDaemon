@KotlinBuildScript("https://tools.kotlin.build/")
@file:WithArtifact("kompile:build-kotlin-jvm:0.0.1")
package dockerrun.embedded

import build.kotlin.withartifact.WithArtifact
import java.io.File
import build.kotlin.jvm.*
import build.kotlin.annotations.MavenArtifactCoordinates

val dependencies = resolveDependencies(
    // DockerRunApi interfaces
    MavenPrebuilt("dockerrun.api:docker-run-api:0.0.1"),
    // JSON
    MavenPrebuilt("org.json:json:20250517"),
    // Kotlin stdlib
    MavenPrebuilt("org.jetbrains.kotlin:kotlin-stdlib:1.9.22"),
    MavenPrebuilt("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.22"),
    MavenPrebuilt("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.22"),
)

@MavenArtifactCoordinates("dockerrun.embedded:docker-run-embedded:")
fun buildMaven(): File {
    return buildSimpleKotlinMavenArtifact(
        // 0.0.1: Initial release
        //        - DockerRunEmbeddedUsingDaemon: Local implementation of DockerRunService using docker CLI
        //        - Container lifecycle: start, pause, unpause, terminate
        //        - Auto-termination via ScheduledExecutorService
        //        - JSON config storage on disk
        coordinates = "dockerrun.embedded:docker-run-embedded:0.0.1",
        src = File("src"),
        compileDependencies = dependencies
    )
}

fun buildSkinnyJar(): File {
    return buildMaven().jar
}

fun buildFatJar(): File {
    val manifest = Manifest()
    return BuildJar(manifest, dependencies.map { it.jar } + buildSkinnyJar())
}
