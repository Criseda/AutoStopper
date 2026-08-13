<p align="center">
  <img src="https://cdn.modrinth.com/data/PG4gqnzX/images/b2003a220072c1bfbb6255af452a39dab08a5377.png" width="200" alt="AutoStopper logo">
</p>

# AutoStopper

AutoStopper is a Velocity proxy plugin that starts mapped Docker-based Minecraft servers when a
player connects and stops them after a configurable period with no players. Startup waits for a
real readiness signal, concurrent connection requests share one startup, and Docker work runs off
Velocity's event and command workers.

> **2.0.0 release-candidate documentation:** the instructions below describe the upcoming 2.0.0
> release. Publication of the validated artifact is tracked by
> [issue #21](https://github.com/Criseda/AutoStopper/issues/21). Until then, 1.1.2 remains the
> migration source, not a substitute 2.0.0 artifact.

- [GitHub releases](https://github.com/Criseda/AutoStopper/releases)
- [Modrinth](https://modrinth.com/plugin/autostopper)
- [Changelog](CHANGELOG.md)
- [Migration from 1.1.2](docs/migration-1.1.2-to-2.0.0.md)

## Security boundary

> **DANGER — HOST-ROOT-EQUIVALENT ACCESS:** The tested installation mounts
> `/var/run/docker.sock` into the Velocity container. Any process that can use that socket can
> control the Docker host with privileges effectively equivalent to root. Adding the `bungeecord`
> user to the socket's group changes which user can reach the socket; it does **not** create
> ordinary non-root isolation. Treat a compromise of Velocity, AutoStopper, another proxy plugin,
> or the proxy container as a compromise of the Docker host.

Use a dedicated host or VM where practical, install only trusted proxy plugins, restrict host and
Compose-file access, do not expose the Docker API over TCP, and grant the proxy access only on a
host whose containers it is allowed to control. A read-only socket mount is not sufficient because
AutoStopper must start and stop containers. See [Docker socket security](docs/security.md) before
installing.

## Supported runtimes

AutoStopper is one Java 21 bytecode JAR compiled against Velocity API 3.5.1. The same packaged JAR
is tested on both runtime lines below; keep each proxy image and Velocity build together.

| Support line | Proxy image | Proxy JVM | Velocity runtime | Example |
|---|---|---:|---|---|
| Legacy | `itzg/mc-proxy:2026.8.0-java21` | 21 | `3.5.1`, build `615` | [`examples/velocity-legacy`](examples/velocity-legacy/) |
| Current | `itzg/mc-proxy:2026.8.0-java25` | 25 | `4.1.0-SNAPSHOT`, build `16` | [`examples/velocity-current`](examples/velocity-current/) |

Velocity 4.1 is currently supplied by PaperMC as a snapshot. Do not run that build on Java 21.
The release-candidate stack is tested with Purpur 1.21.4; backend Java is independent of the proxy
JVM. Other Velocity, Java, proxy-image, and backend combinations are not part of the tested matrix.

## Installation

Prerequisites are Docker Engine with Linux containers and Docker Compose v2, enough access to create
the backend containers in advance, and acceptance of the security boundary above.

1. Copy one complete support-line directory to the Docker host. Do not combine the Java 21 image
   from one example with the Velocity 4.1 build from the other.
2. Download the 2.0.0 JAR from [GitHub Releases](https://github.com/Criseda/AutoStopper/releases) or
   [Modrinth](https://modrinth.com/plugin/autostopper) after it is published. Put the unchanged JAR
   at `velocity_server/plugins/AutoStopper.jar` inside the copied example directory.
3. Review the socket mount and entrypoint in `docker-compose.yml`, then start the stack:

   ```sh
   docker compose up -d
   ```

4. Add every managed backend to the `[servers]` section of
   `velocity_server/velocity.toml`. The key must exactly match AutoStopper's `server_name`:

   ```toml
   [servers]
   purpur = "purpur:25565"
   fabric = "fabric:25565"
   try = ["purpur"]
   ```

5. Edit the generated lowercase data path
   `velocity_server/plugins/autostopper/config.yml`. Start with one mapping if desired:

   ```yaml
   inactivity_timeout_seconds: 300
   shutdown_timeout_seconds: 10
   stop_retry:
     max_attempts: 3
     initial_backoff_seconds: 60
     max_backoff_seconds: 300
   monitored_servers:
     - server_name: purpur
       container_name: purpur-server
       readiness:
         strategy: minecraft_status
         target_host: purpur
         target_port: 25565
         probe_interval_millis: 1000
         timeout_seconds: 120
         connect_timeout_millis: 1000
         read_timeout_millis: 1000
   ```

6. Restart the proxy, or run `/autostopper reload` with the required permission. Verify startup and
   the mapping preflight:

   ```sh
   docker compose logs velocity
   docker compose ps
   ```

   In Velocity, `/autostopper status` should report the monitored server as `READY` or `STOPPED`.
   `DOCKER_UNAVAILABLE` or `FAILED` includes an operator-safe detail such as a missing container;
   raw Docker stderr remains only in the proxy log.

Managed containers must already exist and use `restart: "no"` so Docker does not immediately undo
an inactivity stop. Unmonitored hubs or lobbies are not intercepted or stopped by AutoStopper and
may retain `restart: unless-stopped`.

## Behavior

- A connection to a monitored server is held while AutoStopper inspects or starts its mapped
  container and completes the configured readiness check. Simultaneous players share that work.
- A connection to an unmonitored Velocity server proceeds normally; AutoStopper never infers a
  container name and never manages it.
- The inactivity scan runs once per minute. A mapped, running server with no players is stopped
  after its configured inactivity period.
- Failed and timed-out automatic stops retain activity and use bounded exponential retry. After the
  attempt limit, a new retry cycle requires another full inactivity period.
- A successful reload atomically replaces the configuration and runs a Docker mapping preflight. A
  malformed or invalid reload is rejected in full and the previous snapshot remains active.
- Proxy shutdown cancels plugin work within `shutdown_timeout_seconds`; it does not stop managed
  Minecraft containers.

See the [configuration reference](docs/configuration.md) and
[troubleshooting guide](docs/troubleshooting.md) for the complete contract.

## Commands and permissions

| Command | Required permission | Notes |
|---|---|---|
| `/autostopper`, `/as` | None | Shows plugin information. |
| `/autostopper help` | None | Shows only commands the source may use. |
| `/autostopper status` | `autostopper.command.status` or `autostopper.admin` | Collects current operational state for every mapping. |
| `/autostopper reload` | `autostopper.command.reload` or `autostopper.admin` | Atomically validates, applies, and preflights the new configuration. |

`autostopper.admin` is an explicit umbrella. If it is granted, it authorizes both restricted
commands even when a command-specific node is not granted. Automatic starts and stops have no
player permission node; normal Velocity connection rules still apply. AutoStopper does not register
or replace `/server`, does not check or change `velocity.command.server`, and exposes no manual
start or stop command.

## Building and verification

Builds require JDK 21 through 25. The committed Maven Wrapper pins Maven 3.9.16 and is the canonical
entry point:

```sh
./mvnw --batch-mode --no-transfer-progress clean verify
```

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

The verified shaded JAR is written under `target/`. The optional packaged-runtime and live
release-candidate gates are documented in [`smoke/README.md`](smoke/README.md) and
[`e2e/README.md`](e2e/README.md).

## License and credits

AutoStopper is licensed under the [MIT License](LICENSE). It uses the
[`itzg/mc-proxy`](https://github.com/itzg/docker-mc-proxy) and
[`itzg/minecraft-server`](https://github.com/itzg/docker-minecraft-server) container projects.
