# Migrating from AutoStopper 2.2.0 to 2.3.0

AutoStopper 2.3.0 talks to the Docker Engine API directly, so the proxy no longer needs the Docker
CLI. The examples now give Velocity Docker access through a small socket-proxy sidecar instead of a
custom entrypoint and a socket mount. The upgrade is a drop-in JAR swap: an existing 2.2.0
deployment keeps working without changes. Moving to the sidecar is optional and can be done later.

## What changes

| 2.2.0 | 2.3.0 | Required action |
|---|---|---|
| AutoStopper ran the `docker` CLI, so the examples installed it at boot with a custom `docker-entrypoint.sh`. | AutoStopper speaks the Engine API over the Unix socket or TCP using only the JDK. No CLI is needed in the proxy container. | None. The entrypoint still works; see [Deprecated](#deprecated). |
| Velocity mounted `/var/run/docker.sock`, and the entrypoint added `bungeecord` to the socket's group. | The examples run the stock `itzg/mc-proxy` image with no entrypoint override and no socket mount. An `autostopper-docker` [socket proxy](security.md#socket-proxy-sidecar) holds the socket and allows only container inspect, start, and stop. | Optional: [move to the sidecar](#moving-to-the-sidecar). |
| No Docker settings. | A new optional `docker` section (`backend`, `endpoint`), both `auto` by default. | None. A configuration without the section behaves as `auto`. See [Docker access](configuration.md#docker-access). |
| — | The selected Docker access is logged at startup and reload and shown at the end of `/autostopper status`. | Check it after upgrading. |

With `endpoint: auto`, AutoStopper uses `DOCKER_HOST` when it is set, then the `autostopper-docker`
sidecar when that name resolves, then `/var/run/docker.sock`. A 2.2.0 deployment has neither of the
first two, so 2.3.0 uses the same socket with the same group permissions.

## Upgrade the JAR

1. **Back up** the proxy data directory, your Compose file, and `plugins/autostopper/config.yml`.
2. **Replace the JAR.** Stop Velocity, replace `velocity_server/plugins/AutoStopper.jar` with the
   unchanged 2.3.0 JAR, and start Velocity again:

   ```sh
   docker compose up -d
   ```

3. **Check the proxy log.** Expect, with your context and mapping count:

   ```text
   Docker access: Engine API, endpoint auto (not resolved yet)
   Docker Engine API endpoint: endpoint auto: unix:///var/run/docker.sock (local socket)
   AutoStopper startup preflight healthy: 2 mapping(s) checked.
   ```

   `/autostopper status` ends with the same `Docker:` line.

You can stop here. The rest of this guide removes the entrypoint and the direct socket mount.

## Moving to the sidecar

The sidecar narrows what the proxy can do through Docker; read
[Docker socket security](security.md) for what it does and does not protect. Do this in a
maintenance window: Velocity restarts once.

1. **Add the sidecar service** to your Compose file. Copy it unchanged from the matching example
   ([legacy](../examples/velocity-legacy/docker-compose.yml),
   [stable](../examples/velocity-stable/docker-compose.yml),
   [preview](../examples/velocity-preview/docker-compose.yml)):

   ```yaml
   autostopper-docker:
     image: wollomatic/socket-proxy:1.13.1@sha256:3935b709275e4ec35d6ed5a5c4a1f0d01ed31eec5e7234efc3357ecd47689002
     restart: unless-stopped
     user: "0:0"
     read_only: true
     cap_drop:
       - ALL
     command:
       - -listenip=0.0.0.0
       - -allowfrom=velocity
       - -allowGET=^(/v[0-9.]+)?/(_ping|containers/[a-zA-Z0-9_.-]+/json)$
       - -allowPOST=^(/v[0-9.]+)?/containers/[a-zA-Z0-9_.-]+/(start|stop)$
     volumes:
       - /var/run/docker.sock:/var/run/docker.sock:ro
     networks:
       - autostopper-internal
   ```

   `-allowfrom` must name your Velocity **service** (the key under `services:`, not its
   `container_name`). Change it if your service is not called `velocity`.

2. **Add the internal network** under the top-level `networks:` key:

   ```yaml
   networks:
     autostopper-internal:
       internal: true
   ```

3. **Change the Velocity service:**
   - add `autostopper-internal` to its `networks`, and `depends_on: [autostopper-docker]`;
   - remove the `/var/run/docker.sock` volume;
   - remove the `docker-entrypoint.sh` volume and the `entrypoint:` line;
   - remove any `user:` or `group_add:` you added for socket access.

4. **Apply the change.** Compose recreates Velocity on the stock image entrypoint:

   ```sh
   docker compose up -d
   ```

5. **Check the proxy log** for the sidecar endpoint and a healthy preflight:

   ```text
   Docker Engine API endpoint: endpoint auto: tcp://autostopper-docker:2375 (socket-proxy sidecar)
   AutoStopper startup preflight healthy: 2 mapping(s) checked.
   ```

   Then let a mapped server go idle and join it again, to confirm a real stop and wake.

6. **Delete `docker-entrypoint.sh`** once everything works.

If the preflight reports `permission denied accessing Docker` or `Docker daemon is unavailable`,
see [Socket proxy problems](troubleshooting.md#socket-proxy-problems).

### Rolling back

Restore the backed-up Compose file and run `docker compose up -d --remove-orphans`, so the
sidecar is removed as well. Compose keeps the unused `<project>_autostopper-internal` network;
remove it with `docker network rm <project>_autostopper-internal`. The 2.3.0 JAR works with either
setup, so the JAR does not need to change.

## Deprecated

These keep working in 2.3.x and are planned for removal in 3.0.0:

- **The custom entrypoint pattern** (`docker-entrypoint.sh`, boot-time `apt-get install docker.io`,
  `exec /usr/bin/run-bungeecord.sh`). It overrides the image's real entrypoint and depends on an
  internal path of the itzg image. Mounting the socket directly stays supported without it; see
  [Alternatives](../README.md#alternatives).
- **The Docker CLI backend** (`docker.backend: cli`) and its `CLI_MISSING` diagnostic. `auto` still
  selects it for `ssh://` or TLS `DOCKER_HOST`, a selected Docker context, or Windows. If you
  depend on one of those, please
  [open an issue](https://github.com/Criseda/AutoStopper/issues) before 3.0.0.
