package dockerrun.embedded

import dockerrun.api.ContainerStatus
import dockerrun.api.DockerContainer
import dockerrun.api.DockerRunService
import org.json.JSONObject
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Local embedded implementation of [DockerRunService].
 *
 * Manages Docker containers on the local machine using the docker CLI.
 * Stores container metadata as JSON files on disk. Supports auto-termination
 * via a scheduled executor.
 */
class DockerRunEmbeddedUsingDaemon(private val dataDir: File) : DockerRunService {

    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
    private val autoTerminateFutures = ConcurrentHashMap<UUID, ScheduledFuture<*>>()

    init {
        dataDir.mkdirs()
        containersDir().mkdirs()
    }

    override fun startContainer(
        imageReference: String,
        environmentVariables: Map<String, String>,
        autoTerminateSeconds: Long
    ): DockerContainer {
        val uuid = UUID.randomUUID()
        val container = DockerContainerImpl(this, uuid)

        // Write initial config
        container.writeConfig(
            imageReference = imageReference,
            environmentVariables = environmentVariables,
            status = ContainerStatus.STARTING.name,
            autoTerminateSeconds = autoTerminateSeconds,
            createdAt = System.currentTimeMillis(),
            errorMessage = null,
            dockerContainerId = null
        )

        println("[DockerRunEmbeddedUsingDaemon] Starting container $uuid with image '$imageReference'")

        // Start the container asynchronously
        scheduler.submit {
            try {
                val envArgs = mutableListOf<String>()
                for ((key, value) in environmentVariables) {
                    envArgs.add("-e")
                    envArgs.add("$key=$value")
                }

                val cmdArgs = mutableListOf(
                    "docker", "run", "-d",
                    "--name", "dockerrun-$uuid"
                ) + envArgs + listOf(imageReference)

                val process = ProcessBuilder(cmdArgs)
                    .redirectErrorStream(true)
                    .start()

                val output = process.inputStream.bufferedReader().readText().trim()
                val exitCode = process.waitFor()

                if (exitCode != 0) {
                    println("[DockerRunEmbeddedUsingDaemon] Failed to start container $uuid: $output")
                    container.writeConfig(
                        status = ContainerStatus.FAILED.name,
                        errorMessage = "docker run failed (exit code $exitCode): ${output.take(1000)}"
                    )
                    return@submit
                }

                val dockerContainerId = output.take(64)
                println("[DockerRunEmbeddedUsingDaemon] Container $uuid started with Docker ID: $dockerContainerId")

                container.writeConfig(
                    status = ContainerStatus.RUNNING.name,
                    dockerContainerId = dockerContainerId
                )

                // Schedule auto-termination if requested
                if (autoTerminateSeconds > 0) {
                    val future = scheduler.schedule({
                        println("[DockerRunEmbeddedUsingDaemon] Auto-terminating container $uuid after ${autoTerminateSeconds}s")
                        try {
                            terminateContainerInternal(uuid)
                        } catch (e: Exception) {
                            println("[DockerRunEmbeddedUsingDaemon] Auto-terminate error for $uuid: ${e.message}")
                        }
                    }, autoTerminateSeconds, TimeUnit.SECONDS)
                    autoTerminateFutures[uuid] = future
                }

            } catch (e: Exception) {
                println("[DockerRunEmbeddedUsingDaemon] Error starting container $uuid: ${e.message}")
                e.printStackTrace()
                container.writeConfig(
                    status = ContainerStatus.FAILED.name,
                    errorMessage = "Start exception: ${e.message}"
                )
            }
        }

        return container
    }

    override fun getAllContainers(): Collection<DockerContainer> {
        val dirs = containersDir().listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            try {
                DockerContainerImpl(this, UUID.fromString(dir.name))
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }

    override fun getContainer(uuid: UUID): DockerContainer {
        return DockerContainerImpl(this, uuid)
    }

    override fun pauseContainer(container: DockerContainer) {
        val impl = getContainerImpl(container.uuid)
        val dockerId = impl.dockerContainerId
            ?: throw IllegalStateException(
                "Cannot pause container ${container.uuid}: no Docker container ID assigned (status: ${impl.status})"
            )

        if (impl.status != ContainerStatus.RUNNING) {
            throw IllegalStateException(
                "Cannot pause container ${container.uuid}: current status is ${impl.status}, expected RUNNING"
            )
        }

        val process = ProcessBuilder("docker", "pause", dockerId)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw IllegalStateException(
                "Failed to pause container ${container.uuid} (Docker ID: $dockerId): $output"
            )
        }

        impl.writeConfig(status = ContainerStatus.PAUSED.name)
        println("[DockerRunEmbeddedUsingDaemon] Paused container ${container.uuid}")
    }

    override fun unpauseContainer(container: DockerContainer) {
        val impl = getContainerImpl(container.uuid)
        val dockerId = impl.dockerContainerId
            ?: throw IllegalStateException(
                "Cannot unpause container ${container.uuid}: no Docker container ID assigned (status: ${impl.status})"
            )

        if (impl.status != ContainerStatus.PAUSED) {
            throw IllegalStateException(
                "Cannot unpause container ${container.uuid}: current status is ${impl.status}, expected PAUSED"
            )
        }

        val process = ProcessBuilder("docker", "unpause", dockerId)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw IllegalStateException(
                "Failed to unpause container ${container.uuid} (Docker ID: $dockerId): $output"
            )
        }

        impl.writeConfig(status = ContainerStatus.RUNNING.name)
        println("[DockerRunEmbeddedUsingDaemon] Unpaused container ${container.uuid}")
    }

    override fun terminateContainer(container: DockerContainer) {
        terminateContainerInternal(container.uuid)
    }

    private fun terminateContainerInternal(uuid: UUID) {
        val impl = getContainerImpl(uuid)

        if (impl.status == ContainerStatus.TERMINATED) {
            println("[DockerRunEmbeddedUsingDaemon] Container $uuid is already terminated")
            return
        }

        val dockerId = impl.dockerContainerId
        if (dockerId != null) {
            // Stop the container
            val stopProcess = ProcessBuilder("docker", "stop", dockerId)
                .redirectErrorStream(true)
                .start()
            stopProcess.inputStream.bufferedReader().readText()
            stopProcess.waitFor()

            // Remove the container
            val rmProcess = ProcessBuilder("docker", "rm", "-f", dockerId)
                .redirectErrorStream(true)
                .start()
            rmProcess.inputStream.bufferedReader().readText()
            rmProcess.waitFor()
        }

        // Cancel any pending auto-terminate
        autoTerminateFutures.remove(uuid)?.cancel(false)

        impl.writeConfig(status = ContainerStatus.TERMINATED.name)
        println("[DockerRunEmbeddedUsingDaemon] Terminated container $uuid")
    }

    internal fun containersDir(): File = File(dataDir, "containers").apply { mkdirs() }

    private fun getContainerImpl(uuid: UUID): DockerContainerImpl {
        return DockerContainerImpl(this, uuid)
    }
}


class DockerContainerImpl(
    private val service: DockerRunEmbeddedUsingDaemon,
    override val uuid: UUID
) : DockerContainer {

    val containerDir = File(service.containersDir(), uuid.toString()).also { it.mkdirs() }
    private val configFile = File(containerDir, "config.json")

    override val imageReference: String
        get() = config.optString("imageReference", "")

    override val environmentVariables: Map<String, String>
        get() {
            val envObj = config.optJSONObject("environmentVariables") ?: return emptyMap()
            val result = mutableMapOf<String, String>()
            for (key in envObj.keys()) {
                result[key] = envObj.getString(key)
            }
            return result
        }

    override val status: ContainerStatus
        get() {
            val statusStr = config.optString("status", ContainerStatus.STARTING.name)
            return ContainerStatus.valueOf(statusStr)
        }

    override val autoTerminateSeconds: Long
        get() = config.optLong("autoTerminateSeconds", 0)

    override val createdAt: Long
        get() = config.optLong("createdAt", 0)

    override val errorMessage: String?
        get() = config.optString("errorMessage", null)?.takeIf { it.isNotEmpty() }

    override val dockerContainerId: String?
        get() = config.optString("dockerContainerId", null)?.takeIf { it.isNotEmpty() }

    private val config: JSONObject
        get() {
            if (!configFile.exists()) {
                configFile.writeText("{}")
            }
            return JSONObject(configFile.readText())
        }

    internal fun writeConfig(
        imageReference: String = this.imageReference,
        environmentVariables: Map<String, String> = this.environmentVariables,
        status: String = this.status.name,
        autoTerminateSeconds: Long = this.autoTerminateSeconds,
        createdAt: Long = this.createdAt,
        errorMessage: String? = this.errorMessage,
        dockerContainerId: String? = this.dockerContainerId
    ) {
        val root = JSONObject()
        root.put("imageReference", imageReference)
        val envObj = JSONObject()
        for ((key, value) in environmentVariables) {
            envObj.put(key, value)
        }
        root.put("environmentVariables", envObj)
        root.put("status", status)
        root.put("autoTerminateSeconds", autoTerminateSeconds)
        root.put("createdAt", createdAt)
        if (errorMessage != null) {
            root.put("errorMessage", errorMessage)
        }
        if (dockerContainerId != null) {
            root.put("dockerContainerId", dockerContainerId)
        }
        configFile.writeText(root.toString())
    }

    override fun equals(other: Any?): Boolean {
        if (other !is DockerContainer) return false
        return uuid == other.uuid
    }

    override fun hashCode(): Int = uuid.hashCode()
}
