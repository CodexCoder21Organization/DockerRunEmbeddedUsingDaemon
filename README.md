# DockerRunEmbedded

Local embedded implementation of the [DockerRunApi](https://github.com/CodexCoder21Organization/DockerRunApi) interfaces. Manages Docker containers on the local machine using the docker CLI.

## Overview

DockerRunEmbedded implements `DockerRunService` by shelling out to the `docker` CLI to start, pause, unpause, and terminate containers. Container metadata is persisted as JSON files on disk, and auto-termination is handled via a `ScheduledExecutorService`.

## Features

- Start containers from any Docker image reference
- Set environment variables on containers
- Pause and unpause running containers
- Terminate containers (stops and removes)
- Auto-terminate containers after a configurable duration
- Persistent metadata storage on disk

## Building

```bash
scripts/build.bash dockerrun.embedded.buildMaven
```

## Usage

```kotlin
import dockerrun.embedded.DockerRunEmbedded
import dockerrun.api.ContainerStatus
import java.io.File

val service = DockerRunEmbedded(File("/tmp/dockerrun-data"))

// Start a container with auto-termination after 1 hour
val container = service.startContainer(
    imageReference = "docker.io/library/nginx:latest",
    environmentVariables = mapOf("PORT" to "8080"),
    autoTerminateSeconds = 3600
)

// Check status
println(container.status) // RUNNING

// Pause / Unpause
service.pauseContainer(container)
service.unpauseContainer(container)

// Terminate manually
service.terminateContainer(container)
```

## Storage Layout

```
<dataDir>/
  containers/
    <uuid>/
      config.json    # Container metadata (image, status, env vars, timestamps)
```

## Prerequisites

- Docker daemon must be running and accessible via `docker` CLI
- The user running the service must have permission to execute Docker commands

## Maven Coordinates

```
dockerrun.embedded:docker-run-embedded:0.0.1
```
