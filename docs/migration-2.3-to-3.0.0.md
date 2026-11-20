# Migrating from AutoStopper 2.3.x to 3.0.0

AutoStopper 3.0.0 removes what 2.3.0 deprecated. It talks to Docker only through the Engine API, and
the Docker CLI backend is gone. For a deployment that follows the 2.3.x examples (the socket-proxy
sidecar, or a socket mounted into Velocity), the upgrade is a drop-in JAR swap.

## What changes

| 2.3.x | 3.0.0 | Required action |
|---|---|---|
| `docker.backend: cli` ran the `docker` CLI. | The Docker CLI backend is removed. `docker.backend: cli` is a configuration error. | Remove `docker.backend`, or set it to `auto`. See [Leaving the CLI backend](#leaving-the-cli-backend). |
| `docker.backend: auto` and `api` chose the Engine API. | `docker.backend` has no effect, and `auto` and `api` are still accepted. The generated `config.yml` no longer contains the key. | None. You may delete the key. |
| `backend: auto` handed an `ssh://` or TLS `DOCKER_HOST`, a selected Docker context, or a Windows host without `DOCKER_HOST` to the Docker CLI. | AutoStopper contacts no daemon in these cases. Every Docker operation reports `DAEMON_UNAVAILABLE`, and the proxy log names the cause. | Set `docker.endpoint` to a `unix://` or plain `tcp://` address, or use the socket proxy. |
| `backend: api` ignored an unsupported `DOCKER_HOST` with a warning and used the sidecar or the local socket. | An unsupported `DOCKER_HOST` fails the same way, whatever `docker.backend` says, so AutoStopper never manages containers on a different daemon by mistake. | Unset `DOCKER_HOST`, or set `docker.endpoint`. |
| The `CLI_MISSING` Docker diagnostic. | Removed. | Update any log alerting that matches it. |
| Each wake-up inspected the container twice: once to decide, and once more just before the start. | The start is sent directly after the first inspect. A container that is already running counts as started. | None. |
| The `NO_MAPPING` telemetry outcome and the "has no container mapping" player message, documented for a reload that removed a mapping during a startup. | Removed. Nothing produced them: a reload that removes a mapping retires the startup, which ends as `MAPPING_CHANGED`. | Update any log alerting that matches `NO_MAPPING`. |
| The `STATUS_POLL` telemetry `origin`, documented for operations started by status checks or preflight. | Removed. Status checks and preflight never start a lifecycle operation, so no record ever carried it. | Update any log parsing that lists the origins. |
| The custom `docker-entrypoint.sh` pattern from the 2.2.0 examples. | No longer documented or tested. The CLI it installs is never used. | Move to the socket proxy (see the [2.2.0 to 2.3.0 guide](migration-2.2.0-to-2.3.0.md#moving-to-the-sidecar)), or keep the socket mount without the entrypoint (see [Direct socket mount](security.md#direct-socket-mount)). |

## Upgrade

1. **Back up** the proxy data directory, your Compose file, and `plugins/autostopper/config.yml`.
2. **Check the `docker` section** of `config.yml`. Delete `backend: cli` if you have it, together
   with the whole `backend` line if you like.
3. **Replace the JAR**, or change the version in `MODRINTH_PROJECTS`, and run `docker compose up -d`.
4. **Check the proxy log.** On the first Docker operation, expect a line such as:

   ```text
   Docker access: Engine API, endpoint auto (not resolved yet)
   AutoStopper startup preflight healthy: 2 mapping(s) checked.
   ```

   If you see `Configuration validation error: docker.backend: the Docker CLI backend was removed in
   3.0.0`, finish step 2 and run `/autostopper reload`. If you see `Cannot reach Docker: …, which
   AutoStopper does not support`, follow [Leaving the CLI backend](#leaving-the-cli-backend).

## Leaving the CLI backend

Find the line that applies to you:

- **Socket proxy or socket mount, `docker.backend: cli` set by hand:** delete the key. AutoStopper
  finds the sidecar or `/var/run/docker.sock` on its own, and the proxy container no longer needs
  the `docker` CLI.
- **`DOCKER_HOST=ssh://…`:** AutoStopper cannot follow SSH. Run the socket proxy next to the Docker
  daemon and set `docker.endpoint: tcp://<host>:2375` on a private network, or run Velocity on the
  Docker host with the socket mounted.
- **TLS `DOCKER_HOST` (`DOCKER_TLS_VERIFY` or `DOCKER_CERT_PATH`):** TLS is not supported. Use the
  socket proxy on a private network, as above.
- **A selected Docker context (`DOCKER_CONTEXT`, or `currentContext` in `~/.docker/config.json`):**
  find its endpoint with `docker context inspect --format '{{.Endpoints.docker.Host}}'` and set
  `docker.endpoint` to it, when it is `unix://` or plain `tcp://`. An explicit endpoint is used as
  configured, without looking at contexts.
- **Velocity on Windows with Docker Desktop:** AutoStopper cannot open the `npipe://` named pipe.
  Run Velocity in Docker as in the examples, or enable Docker Desktop's "Expose daemon on
  tcp://localhost:2375 without TLS" and set `docker.endpoint: tcp://localhost:2375`. Anyone on
  the machine can then control Docker, so only do this on a single-user development machine.

## Rollback

The 3.0.0 configuration also loads in 2.3.x, so going back is a JAR swap: restore the 2.3.2 JAR (or
pin `autostopper:2.3.2` in `MODRINTH_PROJECTS`) and restart Velocity. Restore your backed-up
`config.yml` only if you need `docker.backend: cli` again.
