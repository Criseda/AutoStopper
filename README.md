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

- Velocity proxy server (3.3.0 or newer)
- Docker environment with itzg/minecraft-server containers
- Docker socket mounted to the Velocity container
- Java 21+

## Installation

1. Download the latest AutoStopper JAR from the [releases](https://github.com/YourUsername/AutoStopper/releases) page
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
  - server_name: fabric
    container_name: fabric-server
```

### Configuration Options

- `inactivity_timeout_seconds`: Time in seconds a server must be inactive before being shut down (default: 900 seconds/15 minutes)
- `monitored_servers`: List of server mappings
  - `server_name`: Name of the server in Velocity configuration
  - `container_name`: Corresponding Docker container name

## Docker Setup

AutoStopper requires the Docker socket to be mounted in your Velocity container. Here's an example `docker-compose.yml` setup:

```yaml
services:
  velocity:
    image: itzg/mc-proxy
    container_name: velocity-server
    environment:
      TYPE: "VELOCITY"
      ONLINE_MODE: "true"
      VELOCITY_VERSION: "latest"
      VELOCITY_BUILD_ID: "latest"
      REPLACE_ENV_VARIABLES: "true"
    ports:
      - "25565:25565"
    volumes:
      - ./velocity_server:/server
      - /var/run/docker.sock:/var/run/docker.sock  # Required for AutoStopper
    networks:
      - mc-network
    restart: unless-stopped
    entrypoint: bash -c
    command: >
      "if [ ! -f /usr/bin/docker ]; then
        apt-get update && apt-get install -y docker.io && apt-get clean;
      fi &&
      SOCKET_GID=$$(stat -c '%g' /var/run/docker.sock) &&
      if ! getent group $$SOCKET_GID > /dev/null; then groupadd -g $$SOCKET_GID docker_sock; fi &&
      GROUP_NAME=$$(getent group $$SOCKET_GID | cut -d: -f1) &&
      usermod -aG $$GROUP_NAME bungeecord &&
      exec /usr/bin/run-bungeecord.sh"

  # Example Minecraft servers that can be managed by AutoStopper
  purpur:
    image: itzg/minecraft-server:java21
    container_name: purpur-server
    environment:
      TYPE: "PURPUR"
      VERSION: "1.21.4"
      EULA: "TRUE"
      ONLINE_MODE: "FALSE"
    volumes:
      - ./purpur_data:/data
    networks:
      - mc-network
    restart: "no"  # Important: Let AutoStopper manage the container lifecycle

  fabric:
    image: itzg/minecraft-server:java21
    container_name: fabric-server
    environment:
      TYPE: "FABRIC"
      VERSION: "1.21.4"
      EULA: "TRUE"
      ONLINE_MODE: "FALSE"
    volumes:
      - ./fabric_data:/data
    networks:
      - mc-network
    restart: "no"  # Important: Let AutoStopper manage the container lifecycle

networks:
  mc-network:
    driver: bridge
```

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
- `/autostopper reload` - Reloads the configuration (requires `autostopper.admin` permission)

## Permissions

- `autostopper.admin` - Allows use of the reload command

## How It Works

1. When a player attempts to connect to a monitored server:
   - If the server is running, the connection proceeds normally
   - If the server is stopped, AutoStopper:
     - Starts the Docker container
     - Waits for the server to fully initialize
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
