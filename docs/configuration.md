# Configuration reference

Velocity supplies AutoStopper's data directory from the lowercase plugin ID `autostopper`. In the
tested Compose examples the configuration file is therefore:

```text
velocity_server/plugins/autostopper/config.yml
```

On a non-containerized proxy, use `<velocity-root>/plugins/autostopper/config.yml`.

## Generated configuration

A first start creates these active defaults, followed by a commented mapping example.
`monitored_servers` is deliberately empty so installing the plugin cannot accidentally control
containers:

```yaml
# AutoStopper Configuration
# Number of seconds of inactivity before a server is shut down.
inactivity_timeout_seconds: 300

# Hard deadline for cancelling plugin work during proxy shutdown.
shutdown_timeout_seconds: 10

# Failed stops are retried with capped exponential backoff.
stop_retry:
  max_attempts: 3
  initial_backoff_seconds: 60
  max_backoff_seconds: 300

# Add only server names already registered in Velocity.
monitored_servers: []
```

All duration fields must be positive whole numbers in their documented unit. Duplicate YAML keys
are rejected. A reload validates the entire candidate before it becomes active: any error rejects
the whole file and retains the previous immutable configuration.

## Top-level fields

| Field | Required | Default | Contract |
|---|---:|---:|---|
| `inactivity_timeout_seconds` | No | `300` | Seconds with no connected players before a running monitored server becomes eligible for an automatic stop. Maximum `2147483647`. The scan runs once per minute, so a stop is not guaranteed at the exact second. |
| `shutdown_timeout_seconds` | No | `10` | Hard deadline in seconds for cancelling AutoStopper schedules, lifecycle requests, readiness checks, Docker processes, and worker threads when Velocity shuts down. It does not stop backend containers. Maximum `2147483647`. |
| `stop_retry` | No | See below | Bounded policy for failed or timed-out inactivity stops. |
| `monitored_servers` | No | `[]` | Explicit one-to-one Velocity server and Docker container mappings. Only these servers are managed. |

## Stop retry policy

| Field | Default | Contract |
|---|---:|---|
| `stop_retry.max_attempts` | `3` | Total stop attempts in one cycle; positive integer, maximum `100`. |
| `stop_retry.initial_backoff_seconds` | `60` | Delay after the first failed attempt; positive integer. |
| `stop_retry.max_backoff_seconds` | `300` | Cap for exponential backoff; positive integer and at least the initial backoff. |

Backoff doubles after each failed attempt and is capped by `max_backoff_seconds`. Failed, timed-out,
or inaccessible stops preserve the activity record. When all attempts are exhausted, AutoStopper
starts a new activity period; another stop cycle is possible only after the full inactivity timeout.

## Server mappings

Each entry has this shape:

```yaml
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

| Field | Required | Default | Contract |
|---|---:|---:|---|
| `server_name` | Yes | — | Exact key already present in Velocity's `[servers]` table. Unknown or duplicate names reject the configuration. Leading/trailing whitespace and blank values are rejected. |
| `container_name` | Yes | — | Exact name of an existing Docker container. Duplicate container mappings, leading/trailing whitespace, and blank values are rejected. AutoStopper does not create containers. |
| `readiness` | No | `minecraft_status` defaults | Per-server readiness policy described below. |

Mappings are intentionally explicit and isolated. A Velocity server omitted from
`monitored_servers` is an **unmonitored server**: its connection event passes through unchanged, it
is not included in inactivity checks, and AutoStopper never starts or stops a container for it.
This is the correct setup for an always-on hub or lobby. A mapped server is a **monitored server**:
AutoStopper owns only the mapped container's connection-time start and inactivity stop workflow.

Changing or removing a mapping during a reload safely retires the old lifecycle entry. Work already
associated with a changed mapping is cancelled or rejected rather than applied to the replacement.

## Readiness policies

Container `Running=true` does not mean Minecraft can accept a player. AutoStopper waits for one of
three explicit strategies:

| Strategy | Success condition | When to use it |
|---|---|---|
| `minecraft_status` | A valid Minecraft status-protocol response from the configured or Velocity-registered address. | Default and recommended when the proxy can reach the backend address. It is stronger than checking an open TCP port or matching logs. |
| `docker_health` | Docker reports the mapped container as `healthy`. | Only when the image defines a meaningful Docker `HEALTHCHECK`. Missing health configuration fails immediately. |
| `docker_health_or_status` | Either Docker health is `healthy` or the Minecraft status probe succeeds. | Explicit fallback for images that may omit a health check while still exposing a reachable Minecraft endpoint. |

| Readiness field | Default | Contract |
|---|---:|---|
| `strategy` | `minecraft_status` | One of the three exact values above; matching is case-insensitive. |
| `target_host` | Velocity server address | Must be configured together with `target_port`. Ignored by the Docker-health-only strategy. |
| `target_port` | Velocity server port | Integer from `1` through `65535`; must be configured together with `target_host`. |
| `probe_interval_millis` | `1000` | Positive delay between readiness attempts. |
| `timeout_seconds` | `120` | Positive overall readiness deadline. |
| `connect_timeout_millis` | `1000` | Positive connection deadline for each Minecraft status probe. |
| `read_timeout_millis` | `1000` | Positive response-read deadline for each Minecraft status probe. |

If `target_host` and `target_port` are omitted, the address registered for `server_name` in
Velocity is used. In Compose, an explicit service DNS name such as `purpur:25565` avoids accidental
use of a host-facing address that is not reachable from the proxy container.

## Reload and preflight

Run `/autostopper reload` after editing. A successful reload:

1. parses and validates the complete YAML file;
2. atomically replaces the prior configuration;
3. reconciles activity and lifecycle state; and
4. inspects every mapped container off-thread.

The command reports how many mappings are healthy or degraded. Use `/autostopper status` for each
mapping's state and safe remediation. Syntax, type, range, unknown-server, and duplicate-mapping
errors are also written to the proxy log. A failed reload never partially applies valid-looking
entries from the rejected file.

## Operational states

`/autostopper status` reports one of these states for each monitored server:

| State | Meaning |
|---|---|
| `STOPPED` | Docker reports the mapped container is stopped. This is a healthy idle state. |
| `STARTING` | One shared start/readiness operation is active; the status may include waiting-player count. |
| `READY` | Docker reports the container running and AutoStopper has completed readiness for the current mapping lifecycle. |
| `RUNNING_UNVERIFIED` | Docker reports the container running, but AutoStopper has not verified configured readiness in the current lifecycle generation. Player or manual demand performs readiness before connection. |
| `STOPPING` | An automatic inactivity stop is in progress. |
| `FAILED` | The latest Docker or lifecycle observation failed. The line includes its safe detail and remediation. |
| `DOCKER_UNAVAILABLE` | The Docker CLI, daemon, or socket permission boundary is unavailable. |

Active `STARTING` and `STOPPING` operations take precedence while their coordinator-owned work is
current. Outside those transitions, a current Docker observation overrides stale idle lifecycle
state. Status observations are tied to the complete mapping and lifecycle revision that initiated
them; results captured before a reload, mapping replacement, or newer lifecycle operation are
discarded rather than applied to newer state. Startup and reload inspect Docker mappings but do
not eagerly readiness-probe every running backend.

When a runtime hold is active on a server, `/autostopper status` displays a `held` badge (e.g.
`● survival   Ready · held; active 2m ago`).

Raw Docker stderr is deliberately restricted to operator logs and is never copied into player
messages.

## Operator lifecycle commands and runtime holds

AutoStopper provides operator commands to manage mapped backend containers directly:

- `/autostopper start <server>` (`autostopper.command.start`): Starts the mapped backend container
  and awaits verified readiness. If the server is already ready, returns immediately.
- `/autostopper stop <server>` (`autostopper.command.stop`): Stops the mapped backend container.
  Refused if players are connected or waiters are queued, preventing accidental player disconnection.
- `/autostopper restart <server>` (`autostopper.command.restart`): Atomically stops (if running),
  starts, and awaits readiness under a single coordinator-owned future. Refused if players are
  connected or waiters are queued.
- `/autostopper hold <server>` (`autostopper.command.hold`): Sets a runtime hold that suppresses
  automatic inactivity shutdown without preventing manual commands or player connections.
- `/autostopper release <server>` (`autostopper.command.release`): Releases an active hold, returning
  the server to normal inactivity evaluation.

### Hold lifecycle and reload semantics

Runtime holds:
- Are maintained in memory by `ServerHoldRegistry`.
- Survive unchanged configuration reloads (`/autostopper reload`).
- Are automatically cleared when a server mapping is removed or changed in `config.yml`.
- Are cleared on proxy shutdown or restart.
- Do not persist across proxy process restarts (configuration-driven persistent pinning is not
  supported; unmanaged servers should simply not be included in `monitored_servers`).
