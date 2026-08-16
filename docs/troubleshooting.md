# Troubleshooting

Start with the proxy log and `/autostopper status`. Status output contains bounded, sanitized
details and a remediation for the latest failure; raw Docker stderr is logged only for operators.
Run Docker checks from inside the Velocity container because host access alone does not prove that
the proxy user can reach the daemon:

```sh
docker compose exec velocity docker version
docker compose exec velocity docker inspect purpur-server
```

`RUNNING_UNVERIFIED` (`Running · readiness unverified`) is expected when Docker reports a mapped container running but AutoStopper has
not completed the configured readiness check for that mapping in the current proxy process. This
commonly appears after a proxy restart while a backend remains running. It is not a failure and it
does not bypass readiness: the next player or manual start request performs the configured bounded
readiness check before connection. If the state persists after a connection attempt, inspect the
readiness failure detail and follow the actions under [Readiness failures](#readiness-failures).

## Load and configuration failures

| Symptom | Likely cause | Action |
|---|---|---|
| `Unsupported class file major version 65` or an AutoStopper class-version error | The proxy JVM is older than Java 21. | Use the pinned Java 21 legacy line or a Java 25 Velocity 4.x line. |
| `Unsupported class file major version 69` while loading Velocity 4.x | A Velocity 4.x build is running on a JVM older than Java 25. | Use `itzg/mc-proxy:2026.8.0-java25` with the pinned stable (4.0.0) or preview (4.1 snapshot) build, or use the complete Java 21 / Velocity 3.5.1 legacy line. |
| AutoStopper JAR is not discovered | The JAR is missing from the active proxy volume or the wrong host directory is mounted. | Confirm the container sees `/server/plugins/AutoStopper.jar` and restart Velocity. |
| Configuration is not found where expected | The old mixed-case path is being edited. | Use `plugins/autostopper/config.yml`, based on plugin ID `autostopper`. |
| `unknown Velocity server` | `server_name` is absent from Velocity's `[servers]` table or does not match case/spelling. | Add or correct the Velocity entry, then reload. |
| Reload reports YAML, type, range, duplicate, or mapping errors | The candidate file failed whole-file validation. | Correct every reported error and reload. The previous configuration remains active meanwhile. |

## Docker diagnostics

| Status/detail | Typed classification | Operator action |
|---|---|---|
| `Docker CLI could not be found` | `CLI_MISSING` | Ensure the example entrypoint completed, `/usr/bin/docker` exists in the proxy container, and the proxy process can find `docker` on `PATH`. |
| `Docker daemon is unavailable` | `DAEMON_UNAVAILABLE` | Start Docker, verify the socket is mounted at `/var/run/docker.sock`, and check daemon/host health. |
| `permission denied accessing Docker` | `PERMISSION_DENIED` | Check the socket GID, the group created by the entrypoint, and the groups of the `bungeecord` process. Restart the container after permission changes. Remember that granting access is host-root-equivalent. |
| `configured container does not exist` / `MISSING` | `CONTAINER_MISSING` | Run `docker ps -a`, create the backend with `docker compose create` or `up`, or correct `container_name`. AutoStopper never creates containers. |
| `Docker status check timed out` / `Timed out` | `TIMED_OUT` | Check daemon responsiveness, disk pressure, host load, and stuck Docker operations. The built-in Docker command deadline is bounded; retry after the daemon recovers. |
| `Docker status check failed` / `FAILED` | `INDETERMINATE` | Run the equivalent `docker inspect` inside the proxy container and review raw proxy logs. Correct the Docker/container error before retrying. |

If status races a successful reload or a newer lifecycle operation, AutoStopper discards the older
Docker result rather than applying it to the replacement mapping or newer state. Retry
`/autostopper status` to collect a current observation.

The example entrypoint adds `bungeecord` to the socket group. Seeing a non-root UID after that is
expected but is not evidence of reduced host authority; see [Docker socket security](security.md).

## Readiness failures

| Player/status detail | Meaning | Operator action |
|---|---|---|
| `The configured Minecraft status target remained unreachable` | TCP connection attempts did not reach the configured backend before the readiness deadline. | Verify service DNS, network membership, host, port, firewall, and that Minecraft is listening. In Compose, prefer the service name and port `25565`. |
| `did not respond in time` | A connection or status response exceeded the per-probe or overall deadline. | Check backend startup time and load. Increase `readiness.timeout_seconds` only after confirming the target is correct; tune connect/read timeouts deliberately. |
| `did not return a valid Minecraft status response` | The target accepted a connection but did not speak the Minecraft status protocol. | Correct the port/address and remove TCP proxies that return non-Minecraft data. A bare open port is insufficient. |
| `container has no Docker health check configured` | `docker_health` was selected but the image defines no `HEALTHCHECK`. | Add a meaningful image health check, switch to `minecraft_status`, or explicitly use `docker_health_or_status`. |
| `Minecraft readiness target is not configured correctly` | The explicit target is incomplete/invalid or the Velocity address cannot be resolved into a target. | Configure both `target_host` and `target_port`, or correct the Velocity server address. |
| Container stopped or disappeared while starting | The backend exited, was manually removed, or its name changed during readiness. | Inspect backend logs and exit state, restore the container, and correct the mapping before retrying. |
| Docker became inaccessible during readiness | Socket, daemon, or permission access was lost after startup began. | Restore Docker access and retry the connection. |

`docker_health_or_status` succeeds when either signal passes. It is not a way to ignore both a
broken health check and an unreachable status endpoint.

## Failed stops and retries

Automatic stops use `docker stop` only after the server has no connected players and exceeds the
inactivity timeout. If a stop fails, times out, or loses Docker access:

- AutoStopper retains the activity record instead of pretending the container stopped;
- the next attempt uses the configured capped exponential backoff;
- live player activity or conflicting lifecycle work cancels the pending stop safely; and
- after `max_attempts`, a new attempt cycle requires another full inactivity period.

Check the proxy log for `Stop attempt ... failed` and the last `ContainerStatus`. Verify the daemon,
container state, stop behavior, and host load. Do not add an automatic restart policy to a monitored
container: Docker would restart it after AutoStopper successfully stops it.

## Connection behavior

- AutoStopper intercepts only destinations present in `monitored_servers`. If a hub should remain
  always on, omit it from that list.
- Multiple players connecting to one stopped server share one bounded startup/readiness operation.
- Player messages follow the authoritative flow: checking the server, starting it when stopped,
  waiting for readiness, and connecting through Velocity. Already-running unverified backends skip
  the start stage but still report inspection and readiness. Late arrivals immediately see the
  current shared stage, and terminal feedback reports their own elapsed wait time.
- `Players waiting: X` counts unique players in the shared operation, including the viewer. It is
  omitted for the first waiter and shown to later arrivals, or to an earlier waiter who retries after
  the count changes; repeating the same attempt does not create another lifecycle operation.
- Stage messages are transition-driven rather than periodic. A long gap after the waiting-for-
  readiness message means the configured readiness deadline is still running; use the proxy
  log and `/autostopper status` for operator diagnostics rather than expecting a percentage update.
- `SERVER_STOPPING`, overload, cancellation, or a mapping change asks the player to retry; it does
  not launch a second conflicting operation.
- AutoStopper does not implement a custom `/server` command. Players use Velocity's command and
  permissions. On failure the plugin may suggest retrying with `/server <name>`, but it does not own
  that command.

## Manual lifecycle commands and holds

| Symptom / Error | Cause | Operator action |
|---|---|---|
| `Cannot stop server <name>: X players are currently connected` | An operator ran `/autostopper stop` or `restart` while players were active. | Move or disconnect players before stopping, or wait for them to leave so inactivity stopping takes over. |
| `Cannot stop server <name>: players are waiting to connect` | An operator ran `/autostopper stop` or `restart` while player connections were waking the server. | Allow the connection sequence to complete or fail; do not interrupt in-flight player admission. |
| `Server <name> is already ready` | `/autostopper start` was run on an already running and ready server. | No action needed; start is idempotent. |
| `Server <name> is already sleeping` | `/autostopper stop` was run on an already stopped server. | No action needed; stop is idempotent. |
| Hold disappeared after proxy restart | Runtime holds are memory-only and do not survive proxy restarts. | Re-apply the hold with `/autostopper hold <name>` or omit the server from `monitored_servers` if it should remain persistent. |
| Hold disappeared after reload | The server mapping was modified or removed in `config.yml`. | Re-apply the hold if the server is still monitored under a new mapping. |

## Shutdown

`shutdown_timeout_seconds` bounds cancellation of AutoStopper's own scheduled checks, readiness
work, Docker subprocesses, lifecycle requests, and worker threads. Velocity shutdown deliberately
leaves backend containers unchanged. If the deadline expires, remaining AutoStopper worker threads
are daemon threads and the proxy log records the expiration.
