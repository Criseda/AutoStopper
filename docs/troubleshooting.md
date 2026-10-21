# Troubleshooting

Start with the proxy log and `/autostopper status`. Status output contains bounded, sanitized
details for the latest failure, and `/autostopper status <server>` adds its remediation; raw Docker
errors are logged only for operators. The status overview ends with a `Docker:` line showing the
backend and endpoint AutoStopper is using.

Run Docker checks from inside the Velocity container because host access alone does not prove that
the proxy can reach the daemon. The proxy image has `curl`. Print only the HTTP status: an inspect
body contains the container's environment variables.

```sh
# Socket proxy (the examples): expect 200 and 200
docker compose exec velocity curl -s -o /dev/null -w '%{http_code}\n' http://autostopper-docker:2375/_ping
docker compose exec velocity curl -s -o /dev/null -w '%{http_code}\n' http://autostopper-docker:2375/containers/purpur-server/json

# Direct socket mount: run as the proxy user
docker compose exec -u 1000 velocity curl -s -o /dev/null -w '%{http_code}\n' --unix-socket /var/run/docker.sock http://docker/_ping
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
| `Unsupported class file major version 69` while loading Velocity 4.x | A Velocity 4.x build is running on a JVM older than Java 25. | Use `itzg/mc-proxy:2026.8.0-java25` with the pinned stable (4.0.0, 4.1.0, or 4.2.0) or preview (4.2.1 snapshot) build, or use the complete Java 21 / Velocity 3.5.1 legacy line. |
| AutoStopper JAR is not discovered | The JAR is missing from the active proxy volume or the wrong host directory is mounted. | With `MODRINTH_PROJECTS`, check the proxy log for the Modrinth download. With a manual install, confirm the container sees `/server/plugins/AutoStopper.jar`. Then restart Velocity. |
| The proxy exits with `plugins via MODRINTH_PROJECTS require MINECRAFT_VERSION` | `MODRINTH_PROJECTS` is set without `MINECRAFT_VERSION`. | Set `MINECRAFT_VERSION`, for example `"1.21.4"`. It only selects the download and does not have to match your backends. |
| The proxy exits with `No files are available for the project 'AutoStopper' for loader velocity and Minecraft version …` | `MINECRAFT_VERSION` is outside AutoStopper's range (1.7.2 to 26.2), or the pinned version does not exist. | Use a Minecraft version in that range and an AutoStopper version listed on Modrinth. |
| The proxy exits with `'modrinth' command failed` and a connection error | Modrinth could not be reached at startup. The image contacts it on every start, even when the pinned JAR is already downloaded. | Restore network access, or install the JAR manually instead of `MODRINTH_PROJECTS`. |
| `Refusing to load plugin at path plugins/… since ID autostopper was already claimed` | Two AutoStopper JARs are in `plugins`, usually a manual `AutoStopper.jar` next to a Modrinth download. Velocity runs only the first, which may be the older one. | Keep one install method: delete the manual `AutoStopper.jar` when using `MODRINTH_PROJECTS`. |
| Configuration is not found where expected | The old mixed-case path is being edited. | Use `plugins/autostopper/config.yml`, based on plugin ID `autostopper`. |
| `unknown Velocity server` | `server_name` is absent from Velocity's `[servers]` table or does not match case/spelling. | Add or correct the Velocity entry, then reload. |
| Reload reports YAML, type, range, duplicate, or mapping errors | The candidate file failed whole-file validation. | Correct every reported error and reload. The previous configuration remains active meanwhile. |

## Docker diagnostics

| Status/detail | Typed classification | Operator action |
|---|---|---|
| `Docker daemon is unavailable` | `DAEMON_UNAVAILABLE` | Check the `Docker:` line for the endpoint in use. With the socket proxy, see [Socket proxy problems](#socket-proxy-problems). With a direct mount, verify the socket is mounted at `/var/run/docker.sock`. Then check that Docker is running and the host is healthy. |
| `Docker endpoint refused the request` | `PERMISSION_DENIED` | The socket proxy (or a Docker authorization plugin) answered 401/403. See [Socket proxy problems](#socket-proxy-problems). |
| `permission denied accessing Docker` | `PERMISSION_DENIED` | The proxy process may not open the socket. With a direct mount, Velocity must run as `user: "1000:1000"` with `group_add` set to the socket's group ID (`stat -c '%g' /var/run/docker.sock`); see [Direct socket mount](security.md#direct-socket-mount). Recreate the container after permission changes. Granting socket access is host-root-equivalent. |
| `configured container does not exist` / `MISSING` | `CONTAINER_MISSING` | Run `docker ps -a` on the Docker host, create the backend with `docker compose create` or `up`, or correct `container_name`. AutoStopper never creates containers. |
| `Docker status check timed out` / `Timed out` | `TIMED_OUT` | Check daemon responsiveness, disk pressure, host load, and stuck Docker operations. Every Docker request has a bounded deadline; retry after the daemon recovers. A request in flight while the Docker daemon restarts can time out once. |
| `Docker status check failed` / `FAILED` | `INDETERMINATE` | Run `docker inspect <container>` on the Docker host and review the raw error in the proxy log. Correct the Docker/container error before retrying. |
| `Docker CLI could not be found` | `CLI_MISSING` | Only with the deprecated CLI backend (`docker.backend: cli`, or `auto` falling back to it). Install the `docker` CLI in the proxy container, or remove the cause of the fallback; see [Docker access](configuration.md#docker-access). |

If status races a successful reload or a newer lifecycle operation, AutoStopper discards the older
Docker result rather than applying it to the replacement mapping or newer state. Retry
`/autostopper status` to collect a current observation.

## Socket proxy problems

The examples reach Docker through the `autostopper-docker` socket proxy. Check its state and log
first:

```sh
docker compose ps autostopper-docker
docker compose logs autostopper-docker
```

| Symptom | Cause | Action |
|---|---|---|
| The log shows `endpoint auto: unix:///var/run/docker.sock (local socket)` although you run the proxy, followed by `Docker daemon is unavailable` | The name `autostopper-docker` does not resolve from Velocity, so AutoStopper fell back to the local socket. | Attach Velocity to the `autostopper-internal` network and keep the service name `autostopper-docker`. To use another name, set `docker.endpoint: tcp://<name>:2375`. |
| `Docker endpoint refused the request`; the proxy log shows `blocked request` with `reason="forbidden IP"` | `-allowfrom` does not match Velocity. | Set `-allowfrom` to the Velocity **service** name (the key under `services:`), then `docker compose up -d`. |
| `Docker endpoint refused the request` for some servers only | The allowlist is pinned to container names and this container is not in it. | Add the container name to `-allowGET` and `-allowPOST`; see [Docker socket security](security.md#socket-proxy-sidecar). |
| `Docker daemon is unavailable`; `autostopper-docker` is not running and its log shows `socket not available` … `permission denied` | The proxy does not run as root and cannot open the socket. | Keep `user: "0:0"` on the proxy service. |
| `Docker daemon is unavailable` after the Docker daemon restarted | The proxy has no restart policy, so it stayed stopped. | Keep `restart: unless-stopped` on the proxy service, then `docker compose up -d`. |

A non-root Velocity process is not evidence of reduced host authority; see
[Docker socket security](security.md).

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
| The player is disconnected with `An internal error occurred in your connection` while the server wakes; the proxy log shows `read timed out` and the wait ends `PLAYER_DISCONNECTED` | Velocity's `read-timeout` (30 seconds by default) is shorter than the backend's startup. The startup itself continues. | Raise `read-timeout` under `[advanced]` in `velocity.toml` above your slowest backend's startup time, for example `180000`, and restart Velocity. |

`docker_health_or_status` succeeds when either signal passes. It is not a way to ignore both a
broken health check and an unreachable status endpoint.

## Failed stops and retries

Automatic stops ask Docker to stop the container only after the server has no connected players and
exceeds the inactivity timeout. If a stop fails, times out, or loses Docker access:

- AutoStopper retains the activity record instead of pretending the container stopped;
- the next attempt uses the configured capped exponential backoff;
- live player activity or conflicting lifecycle work cancels the pending stop safely; and
- after `max_attempts`, a new attempt cycle requires another full inactivity period.

Check the proxy log for `Stop attempt ... failed` and the last `ContainerStatus`. Verify the daemon,
container state, stop behavior, and host load. Do not add an automatic restart policy to a monitored
container: Docker would restart it after AutoStopper successfully stops it.

### Stop grace period

`docker stop` sends `SIGTERM`, waits for the container's own stop grace period, then sends `SIGKILL`.
AutoStopper does not override that policy: before each stop it reads the container's configured
`StopTimeout` and waits for that period plus its normal 10-second Docker command timeout. Unset
values use Docker's 10-second default; unbounded or longer values are capped at 10 minutes.

Large or modded worlds can need more than 10 seconds to save. Give such backends a longer grace
period in their deployment, for example `stop_grace_period: 60s` in Compose or
`docker run --stop-timeout 60`. If Docker still exceeds the deadline, AutoStopper checks the
container again and reports the stop as successful when it has already stopped.

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
- If a backend stops outside AutoStopper (a crash, an in-game `/stop`, or a manual `docker stop`),
  the next inactivity scan (at most one minute) or the next refused connection attempt checks Docker
  and marks the server stopped, so the following player connection starts it again. A refusal while
  the container is still running, such as a whitelist or ban kick, does not change its state.

## Manual lifecycle commands and holds

| Symptom / Error | Cause | Operator action |
|---|---|---|
| `Cannot stop server <name>: X players are currently connected` | An operator ran `/autostopper stop` or `restart` while players were active. | Move or disconnect players before stopping, or wait for them to leave so inactivity stopping takes over. |
| `Cannot stop server <name>: players are waiting to connect` | An operator ran `/autostopper stop` or `restart` while player connections were waking the server. | Allow the connection sequence to complete or fail; do not interrupt in-flight player admission. |
| `Server <name> is already ready` | `/autostopper start` was run on an already running and ready server. | No action needed; start is idempotent. |
| `Server <name> is already sleeping` | `/autostopper stop` was run on an already stopped server. | No action needed; stop is idempotent. |
| Hold disappeared after proxy restart | Runtime holds are memory-only and do not survive proxy restarts. | Re-apply the hold with `/autostopper hold <name>` or omit the server from `monitored_servers` if it should remain persistent. |
| Hold disappeared after reload | The server mapping was modified or removed in `config.yml`. | Re-apply the hold if the server is still monitored under a new mapping. |

## Outcome reference

Every `AutoStopper lifecycle completed:` and `AutoStopper lifecycle stage:` log line carries a typed
`outcome=` code. Each code is documented below:

- [Manual command outcomes](#manual-command-outcomes): failures of `/autostopper start`, `stop`, and
  `restart`. The same codes mean the same thing for player connections and automatic stops.
- [Connection and startup outcomes](#connection-and-startup-outcomes): codes that only player
  connections and the shared startup produce.
- [PROXY_SHUTDOWN](#proxy_shutdown): work that was still running when Velocity shut down.
- [Success and no-op outcomes](#success-and-no-op-outcomes): codes that need no action.

## Manual command outcomes

When `/autostopper start`, `stop`, or `restart` fails, the chat line states the cause. In game,
hovering the line shows the remediation and the typed outcome code, which matches `outcome=` in the
`AutoStopper lifecycle completed:` log line; clicking it opens the matching section below. Console
senders get the same detail and link as plain text. A suggested next command (the retry or
`/autostopper status`) follows the line where one helps.

### SERVER_STARTING

A start is already in progress for the server. Wait for it to finish, then retry the stop or restart.

### SERVER_STOPPING

A stop is already in progress for the server. Wait for it to finish, then retry.

### PLAYERS_CONNECTED

Stop and restart refuse to disconnect players. Move players to another server or wait for them to
leave; inactivity stopping takes over once the server is empty.

### WAITERS_PRESENT

Players are waiting for the server to become ready. Let the connection sequence finish or fail,
then retry; AutoStopper does not interrupt in-flight player admission.

### MAPPING_CHANGED

A configuration reload changed or removed the server's mapping while the command ran, so the command
stopped rather than act on a stale mapping. Check the mapping in `config.yml`, then retry.

### CONTAINER_MISSING

The configured container does not exist. Run `docker ps -a` on the Docker host, create the
backend with `docker compose create` or `up`, or correct `container_name` and reload. AutoStopper
never creates containers. See [Docker diagnostics](#docker-diagnostics).

### DOCKER_INACCESSIBLE

The proxy cannot reach the Docker daemon: the daemon or socket proxy is down, the socket proxy
refused the request, socket permission is denied, or (CLI backend only) the CLI is missing. Follow the matching row in [Docker diagnostics](#docker-diagnostics), then retry.

### STATUS_TIMED_OUT

The Docker status check exceeded its bounded deadline. Check daemon responsiveness, disk pressure,
and host load, then retry.

### STATUS_FAILED

The Docker status check failed for a reason other than a missing container or lost access. Review
the raw error in the proxy log, run `docker inspect <container>` on the Docker host, and
correct the Docker or container error before retrying.

### START_TIMED_OUT

Docker did not start the container within the deadline. Check daemon responsiveness and host load,
then retry.

### START_FAILED

Docker could not start the container. Review the raw error in the proxy log and the backend's
`docker logs`, correct the container error, then retry.

### STOP_TIMED_OUT

Docker did not stop the container within its stop grace period plus the command deadline. Check
host load and whether the backend needs a longer grace period; see
[Stop grace period](#stop-grace-period).

### STOP_FAILED

Docker could not stop the container. Review the raw error in the proxy log and the container state,
then retry. See [Failed stops and retries](#failed-stops-and-retries).

### SERVER_NOT_READY

The container started but did not pass the configured readiness check before the deadline. Follow
[Readiness failures](#readiness-failures), then retry.

### OVERLOADED

AutoStopper's bounded worker queue is full. Wait for current lifecycle operations to finish, then
retry.

### CANCELLED

A reload, proxy shutdown, or conflicting lifecycle operation cancelled the command. Retry once the
reload or conflicting operation completes.

## Connection and startup outcomes

These codes appear on `op=CONNECTION_WAIT` lines, which record one player's wait from the connection
attempt to the final result, and on startup stage lines. When the shared startup fails, the wait
reports that startup's code from [Manual command outcomes](#manual-command-outcomes) instead.

### CONNECTION_FAILED

The connection request to the ready backend failed with an error or returned no result. Review the
proxy log for the error, check that the backend is listening on the address in Velocity's
`[servers]` table, then retry with `/server <name>`. If no player has connected since the backend
became ready, AutoStopper marks the server failed so `/autostopper status` shows it.

### SERVER_DISCONNECTED

The backend accepted the connection and then disconnected the player, for example a whitelist, ban,
version mismatch, or a backend that stopped outside AutoStopper. Check the backend log for the kick
reason. If no player has connected since the backend became ready, AutoStopper marks the server
failed. When a backend that served players before refuses a connection, AutoStopper checks Docker
and marks the server stopped if the container is no longer running; see
[Connection behavior](#connection-behavior).

### CONNECTION_CANCELLED

Another plugin or Velocity cancelled the connection request. Check plugins that handle
`ServerPreConnectEvent` or move players between servers.

### CONNECTION_IN_PROGRESS

Velocity was already connecting the player to a server, so it did not start a second connection.
Usually no action is needed; the player can retry once the other connection finishes.

### PLAYER_DISCONNECTED

The player left the proxy before the server became ready or before the connection finished. No
action is needed; the shared startup continues for any other waiting players.

### NO_MAPPING

The startup status check found no active container mapping for the server, usually because a reload
removed it while the startup was queued. Players see a no-container-mapping message, and a manual
start or restart reports [MAPPING_CHANGED](#mapping_changed). Confirm the server
is still in `monitored_servers`, reload a valid configuration, then retry.

## Success and no-op outcomes

These codes report success, a stage result, or a request that was already satisfied. No action is
needed.

| Outcome | Meaning |
|---|---|
| `READY` | The server passed its readiness check, or a manual start finished with the server ready. |
| `RUNNING` | The Docker status check found the container running. |
| `STOPPED` | The container stopped, or a status or readiness check found it stopped. |
| `RESTARTED_AND_READY` | A manual restart stopped the server, started it again, and it passed readiness. |
| `CONNECTED` | Velocity connected the waiting player to the backend. |
| `ALREADY_READY` | A manual start found the server already running and ready. |
| `ALREADY_STOPPED` | A manual stop found the server already stopped. |
| `ALREADY_CONNECTED` | The player was already connected to the backend. |

## Lifecycle telemetry diagnostics

AutoStopper emits structured `INFO` log lines upon completing every authoritative operation and `DEBUG`
log lines for intermediate stages:

- Search `velocity.log` for `AutoStopper lifecycle completed:` to track completed lifecycle operations
  with their duration (`elapsed_ms`), outcome, origin, and waiter counts.
- Search `velocity.log` for `AutoStopper lifecycle stage:` with debug logging enabled to diagnose
  individual stage durations (e.g. whether container launch or readiness probing took the majority
  of startup time).
- Telemetry records use typed outcomes (e.g., `START_TIMED_OUT`, `SERVER_NOT_READY`, `CONTAINER_MISSING`,
  `DOCKER_INACCESSIBLE`) to quickly isolate root causes without needing to guess from unformatted messages.
  Look up any code in the [Outcome reference](#outcome-reference).

## Shutdown

`shutdown_timeout_seconds` bounds cancellation of AutoStopper's own scheduled checks, readiness
work, Docker requests, lifecycle requests, and worker threads. Velocity shutdown deliberately
leaves backend containers unchanged. If the deadline expires, remaining AutoStopper worker threads
are daemon threads and the proxy log records the expiration.

### PROXY_SHUTDOWN

Velocity shut down while the operation was still running: a startup, a player's connection wait, a
manual command, or an automatic stop. AutoStopper completes it with this code rather than
`CANCELLED` and leaves the backend container in whatever state Docker reached. No action is needed;
check `/autostopper status` after the proxy starts again.
