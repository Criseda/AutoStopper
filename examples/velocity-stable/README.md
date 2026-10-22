# Stable example: Java 25 and Velocity 4.2.0

This pinned example is the recommended production line. It uses
`itzg/mc-proxy:2026.8.0-java25`, Velocity 4.2.0 build 30, and Java 25. Velocity 4.x requires at
least Java 25; do not run this build on Java 21.

> **Docker access:** `docker-compose.yml` runs an `autostopper-docker` socket proxy that holds
> `/var/run/docker.sock` as root and lets Velocity only inspect, start, and stop containers.
> Velocity uses the stock image with no custom entrypoint and no socket mount. Read the complete
> [security guidance](../../docs/security.md) before starting the stack.

## Run this example

You need Docker Engine and Docker Compose v2 on a Linux host.

1. Copy this directory's `docker-compose.yml` to an empty directory on the host and start it:

   ```sh
   docker compose up -d
   ```

   The proxy installs AutoStopper from Modrinth (`MODRINTH_PROJECTS`, pinned to one release), and
   the first start generates the configuration under `velocity_server/`.

2. In `velocity_server/velocity.toml`, replace the sample servers and the sample forced hosts,
   which refer to them:

   ```toml
   [servers]
   purpur = "purpur:25565"
   fabric = "fabric:25565"
   try = ["purpur"]

   [forced-hosts]
   ```

   In the `[advanced]` section of the same file, set `read-timeout = 180000` so a sleeping server
   has time to wake before Velocity gives up on the player (the default is 30 seconds).

3. In `velocity_server/plugins/autostopper/config.yml`, replace `monitored_servers: []` with:

   ```yaml
   monitored_servers:
     - server_name: purpur
       container_name: purpur-server
     - server_name: fabric
       container_name: fabric-server
   ```

4. Restart Velocity and check the log:

   ```sh
   docker compose restart velocity
   docker compose logs velocity | grep -i autostopper
   ```

   Look for `endpoint auto: tcp://autostopper-docker:2375 (socket-proxy sidecar)` and
   `AutoStopper startup preflight healthy: 2 mapping(s) checked.`

After 5 minutes without players a server sleeps, and the next player who joins wakes it.
`/autostopper status` shows each server's state. The managed servers use `restart: "no"` so Docker
does not undo a stop; always-on hubs stay out of `monitored_servers`.

For tuning, upgrades, a manual JAR install, and troubleshooting, see the
[main README](../../README.md#next-steps).
