<p align="center">
  <img src="https://cdn.modrinth.com/data/PG4gqnzX/images/b2003a220072c1bfbb6255af452a39dab08a5377.png" width="200" alt="AutoStopper Logo">
</p>

# AutoStopper

AutoStopper is a Velocity proxy plugin that automatically stops and starts Minecraft server containers based on player activity. It helps server administrators save resources by shutting down inactive Docker-based Minecraft servers.

## Download links

- [Modrinth](https://modrinth.com/plugin/autostopper)

## Features

- Automatically monitors server activity
- Stops inactive Docker containers after a configurable timeout
- Starts servers on-demand when players try to connect
- Seamlessly connects players to servers after starting them
- Maintains server state tracking

## Requirements

- Velocity proxy server (3.5.1 or newer)
- Docker environment with itzg/minecraft-server containers
- Docker socket mounted to the Velocity container
- Java 21+

## Support matrix

AutoStopper is compiled as **one Java 21 bytecode artifact** against the latest
stable 3.x Velocity API (3.5.1). The same JAR is smoke-tested on both supported
runtime lines:

| Runtime line | Java | Velocity runtime (pinned)          | Status |
|--------------|------|-------------------------------------|--------|
| Legacy       | 21   | `velocity-3.5.1-615.jar`            | Tested |
| Current      | 25   | `velocity-4.1.0-SNAPSHOT-16.jar`    | Tested |

The `4.1.0` line is currently distributed as PaperMC snapshot builds; a stable
`4.1.0` download is pinned here as soon as one is published. The proxy's Java
version must always match the velocity bytecode line (see the Docker setup
notes below).

The smoke-test harness lives in [`smoke/`](smoke/README.md) and is run with:

```powershell
mvn clean package
.\smoke\run-smoke.ps1
```

## Installation

1. Download the latest AutoStopper JAR from the [releases](https://github.com/criseda/AutoStopper/releases) page
2. Place the JAR in your Velocity server's `plugins` directory
3. Start (or restart) your Velocity server
4. Edit the generated configuration file to match your setup

## Configuration

After the first run, AutoStopper will generate a `config.yml` in the `plugins/AutoStopper` directory:

```yaml
# Time in seconds before an inactive server is stopped
inactivity_timeout_seconds: 900

# List of servers AutoStopper should manage
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
  - server_name: fabric
    container_name: fabric-server
    readiness:
      strategy: docker_health
```

### Configuration Options

- `inactivity_timeout_seconds`: Time in seconds a server must be inactive before being shut down (default: 300 seconds/5 minutes)
- `monitored_servers`: List of server mappings
  - `server_name`: Name of the server in Velocity configuration
  - `container_name`: Corresponding Docker container name
  - `readiness`: Optional per-server readiness policy. Existing configurations default to a
    bounded `minecraft_status` probe against the server address registered in Velocity.
    - `strategy`: `minecraft_status`, `docker_health`, or `docker_health_or_status`.
      The combined strategy accepts either a healthy Docker health check or a valid
      Minecraft status response, and is the explicit fallback option for images that
      may not define a health check.
    - `target_host` and `target_port`: Minecraft status target. Configure both or neither;
      when omitted, AutoStopper uses the address registered for `server_name` in Velocity.
      These values are ignored by the Docker-health-only strategy.
    - `probe_interval_millis`: Delay between attempts (default: `1000`).
    - `timeout_seconds`: Overall readiness deadline (default: `120`).
    - `connect_timeout_millis`: Maximum socket connection time per Minecraft probe
      (default: `1000`).
    - `read_timeout_millis`: Maximum response-read time per Minecraft probe
      (default: `1000`).

Minecraft readiness uses the status protocol, not a bare open port or log text. Docker
health readiness requires the container image to define a Docker `HEALTHCHECK`; otherwise
`docker_health` fails immediately with a configuration-oriented diagnostic.

## Docker Setup

AutoStopper requires the Docker socket to be mounted in your Velocity container.
Tested, fully pinned Compose examples are provided for both support lines
(issue #3 matrix):

| Support line | Example                                    | mc-proxy image             | Velocity build |
|--------------|--------------------------------------------|----------------------------|----------------|
| Legacy       | [`examples/velocity-legacy/`](examples/velocity-legacy/) | `itzg/mc-proxy:2026.8.0-java21` | 3.5.1 (build 615) |
| Current      | [`examples/velocity-current/`](examples/velocity-current/) | `itzg/mc-proxy:2026.8.0-java25` | 4.1.0-SNAPSHOT (build 16) |

Each example contains a `docker-compose.yml` and a `docker-entrypoint.sh`:

1. ```bash
   cd examples/velocity-legacy   # or examples/velocity-current
   ```
2. Drop the AutoStopper JAR into `./velocity_server/plugins/`
3. ```bash
   docker compose up -d
   ```

Both examples are boot-tested against the exact pinned builds in the support
matrix. Copy one of them to your server and adapt the Minecraft services.

### Upgrade Note

The mc-proxy image Java version and the Velocity bytecode version must move
together: the legacy example uses a Java 21 image with Velocity 3.5.1 (Java 21
bytecode), the current example uses a Java 25 image with Velocity 4.1 (Java 25
bytecode). Never mix, e.g., a Java 21 image with a Velocity 4.x build.

### Verify It Works / Troubleshooting

Check the proxy log for the plugin's startup line:

```bash
docker logs velocity-server | grep "AutoStopper"
```

If the plugin fails to load, look for a Java bytecode mismatch:

- `Unsupported class file major version` / `class file version 69` means the
  proxy JVM is too old for the pinned Velocity build - keep the Velocity
  version and the `itzg/mc-proxy` java tag in the same support line.
- A generic `Plugin ... failed to load` without a bytecode error usually means
  the JAR is not in the profile's `plugins` directory or the wrong JAR name was
  used.

The minimal load-path smoke harness used during development lives in
[`smoke/`](smoke/README.md).

### Important Docker Configuration Notes

1. The Velocity container must have Docker CLI installed, which is why the entrypoint script installs it
2. The Docker socket must be mounted (`/var/run/docker.sock:/var/run/docker.sock`)
3. The script automatically handles permissions by detecting the socket group ID and adding the server user to it.
4. Set `restart: "no"` for managed servers so Docker doesn't automatically restart them
5. Keep any hub/lobby servers with `restart: unless-stopped` if you want them to always be available

## Commands

- `/autostopper` or `/as` - Main command
- `/autostopper help` - Displays help information
- `/autostopper status` - Shows the status of all monitored servers
- `/autostopper reload` - Reloads the configuration

## Permissions

| Command or action | Permission rule | Behavior when undefined |
|-------------------|-----------------|-------------------------|
| `/autostopper`, `/as` | Public | Allowed |
| `/autostopper help` | Public | Allowed |
| `/autostopper status` | `autostopper.command.status` or `autostopper.admin` | Denied |
| `/autostopper reload` | `autostopper.command.reload` or `autostopper.admin` | Denied |
| Automatic start on connection | No AutoStopper permission; Velocity's normal server connection rules apply | Unchanged |
| Automatic inactivity stop | Internal configured lifecycle action; no player permission | Not applicable |

`autostopper.admin` is an explicit umbrella: granting it authorizes every
restricted AutoStopper command, even if a command-specific node is denied.
AutoStopper does not check or change `velocity.command.server`, and it does not
expose manual start or stop commands.

## How It Works

1. When a player attempts to connect to a monitored server:
   - AutoStopper separates the Docker container's running state from Minecraft readiness
   - If the server is stopped, AutoStopper starts the Docker container
   - Whether the container was already running or was just started, AutoStopper:
     - Waits for the configured Docker-health and/or Minecraft status check to pass
     - Automatically connects the player once the server is ready

2. The plugin tracks the last activity time for each server
3. A scheduled task checks for inactive servers and stops them after the configured timeout period
4. Servers that are always needed (like hubs/lobbies) can be excluded from monitoring

## Building from Source

1. Clone the repository
2. Build using Maven:

   ```bash
   mvn clean package
   ```

3. Find the JAR file in `target/AutoStopper-1.1.2.jar`

## License

This project is licensed under the [MIT License](LICENSE). See the LICENSE file for details.

## Credits

- Built for Velocity by [Criseda](https://criseda.com)
- Uses [itzg/minecraft-server](https://github.com/itzg/docker-minecraft-server)
- Uses [itzg/mc-proxy](https://github.com/itzg/docker-mc-proxy)
