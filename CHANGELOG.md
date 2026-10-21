# Changelog

All notable AutoStopper changes are documented here.

## [Unreleased]

## [2.3.0] - 2026-10-22

### Added

- AutoStopper talks to the Docker Engine API directly over the Unix socket or plain TCP, so the
  proxy no longer needs the Docker CLI. The new `docker.backend` (`auto`, `api`, `cli`) and
  `docker.endpoint` (`auto`, `unix://…`, `tcp://…`) settings are validated on load and reload.
  `backend: auto` uses the API, finding the daemon through `DOCKER_HOST`, then an
  `autostopper-docker` socket-proxy sidecar, then `/var/run/docker.sock`. It falls back to the CLI
  for `ssh://` or TLS `DOCKER_HOST`, a selected Docker context, or Windows. The selected backend
  and endpoint are logged and shown at the end of `/autostopper status`. Existing deployments that
  mount the Docker socket keep working unchanged (#143).

### Changed

- The examples give Velocity Docker access through an `autostopper-docker` socket proxy
  (`wollomatic/socket-proxy`, pinned by digest) on an internal network. The proxy allows only
  container inspect, start, and stop, and only from the `velocity` service. Velocity runs the stock
  `itzg/mc-proxy` image with no custom entrypoint, no socket mount, and no host-specific values.
  The README, security, troubleshooting, and configuration docs describe this setup, and a new
  [2.2.0 to 2.3.0 migration guide](docs/migration-2.2.0-to-2.3.0.md) covers moving an existing
  deployment. The custom `docker-entrypoint.sh` pattern and the Docker CLI backend
  (`docker.backend: cli`) are deprecated and planned for removal in 3.0.0. Mounting the socket
  directly remains supported (#144).
- The examples and README install AutoStopper from Modrinth at startup through the proxy image's
  `MODRINTH_PROJECTS`, pinned to one release, with `MINECRAFT_VERSION` set as the image requires.
  There is no JAR to download; upgrading means changing the pin. The proxy contacts Modrinth on
  every start. Installing the JAR manually remains documented as the alternative; use one method
  only (#142).

### Removed

- `docker-entrypoint.sh` from the examples. Existing deployments that use it keep working (#144).

## [2.2.0] - 2026-10-18

### Added

- Failed `/autostopper start`, `stop`, and `restart` commands now state the cause in chat. In game,
  hovering shows the remediation and the typed outcome code (matching `outcome=` in `velocity.log`),
  clicking opens the matching troubleshooting section, and a suggested next command (the retry or
  `/autostopper status`) follows the line. Console senders get the same cause, remediation, outcome,
  and link as plain text. Player connection messages are unchanged (#124).
- `/autostopper help` for operators, and `/autostopper status` when any server has failed, end with
  `Troubleshooting · Get help` links. Documentation links are pinned to the running release (#124).
- `docs/troubleshooting.md` documents every manual command failure outcome (#124).
- `docs/troubleshooting.md` has an outcome reference covering every `outcome=` code in lifecycle
  log lines, including player connection outcomes, `NO_MAPPING`, `PROXY_SHUTDOWN`, and success
  codes (#123).
- `/autostopper status <server>` shows one server in detail: state and, while waking, the startup
  stage, hold, waiting players, time since activity, and the last failure with its age, cause, and
  fix. The console prints the exact failure time; players hover the age for it. It inspects only
  that server's container and tab-completes mapped server names. In game, clicking a server name in
  `/autostopper status` opens its detail view (#125).
- `/autostopper status <server>` lists the server's last 10 completed lifecycle operations, newest
  first: startups, manual starts, stops, and restarts, and automatic stops, each with its age, who
  started it, its typed outcome (matching `outcome=` in `velocity.log`), and its duration. The
  history is in memory only; a proxy restart clears it, and a reload clears it for servers whose
  mapping was removed, added, or changed (#129).

### Changed

- `/autostopper status` shows the last failure's age (`last failure 3m ago during …`) instead of a
  raw ISO-8601 timestamp, and leaves the remediation to `/autostopper status <server>`. Activity and
  failure ages share one format, and ages of a day or more read `2d 3h ago` (#125).
- Velocity 4.1.0 (build 22) and 4.2.0 (build 30) are now tested stable runtimes alongside 4.0.0, with 4.2.0 the recommended production line and the stable example. The preview line now validates Velocity 4.2.1-SNAPSHOT (build 41). The packaged-runtime CI matrix, smoke profiles (`stable-4.0`, `stable-4.1`, `stable`, `preview`), release-candidate E2E, and release metadata were updated to match. Pull requests run only `legacy`, `stable`, and `preview`; the release gate also runs `stable-4.0` and `stable-4.1` (#134).
- `/autostopper` now shows clickable Docs, GitHub, Modrinth, and Changelog links to everyone, and
  the version opens the release notes; console senders get the URLs as plain text. Documentation
  and changelog links are pinned to the running release (#131).
- `/autostopper help` groups commands under General, Lifecycle, and Holds, uses consistent
  imperative descriptions (also used by usage messages), drops the `/autostopper` and
  `/autostopper help` entries, and shows each command's permission on hover. Sources without
  operator commands get the project links instead of an empty list (#131).
- A cancelled manual stop now reports `Stopping server <name> was cancelled.` instead of the start
  wording (#124).
- Readiness checks no longer hold an AutoStopper worker for the whole readiness window. Each
  attempt runs as a short, individually bounded task, and the probe interval between attempts waits
  on a timer instead of a sleeping worker. Concurrent startups no longer delay other servers' status
  checks, startups, stops, the inactivity scan, or `/autostopper status`. Readiness deadlines,
  probe intervals, outcomes, cancellation, and shutdown are unchanged. If AutoStopper is saturated
  before the first attempt, the startup still reports `OVERLOADED`; a later attempt skipped for
  saturation is retried at the next interval within the same deadline (#95).
- The canonical build is the checksummed Maven 3.10.0 Wrapper. Maven's auto-discovered repository
  prefix filter is disabled in `.mvn/maven.config` because the PaperMC repository can publish a
  prefix file that omits `com.velocitypowered`, which blocks Velocity API resolution.

### Fixed

- A startup opened by `/autostopper start` or `/autostopper restart` now logs
  `origin=MANUAL_COMMAND` in its `op=STARTUP` lifecycle line instead of `origin=PLAYER_CONNECTION`
  (#129).
- A startup whose next step throws unexpectedly now fails with the stage's error outcome, so the
  server becomes `FAILED` instead of staying in `STARTING` until the proxy restarts. A manual
  restart in that case reports `START_FAILED` instead of never completing (#118).
- A manual stop that loses its server to another operation now ends `CANCELLED` and leaves that
  operation's state alone, instead of stopping the container or returning the server to `READY` (#119).

## [2.1.1] - 2026-09-26

### Fixed

- Servers whose container stops outside AutoStopper (backend crash, in-game `/stop`, manual
  `docker stop`) no longer stay `READY` after a prior successful connection. The inactivity scan and
  refused connection attempts now reconcile the stopped container through the revision-guarded
  lifecycle path, so the next player connection wakes the server again (#93).
- Stops now honor the container's own stop grace period (`StopTimeout`, Compose
  `stop_grace_period`) instead of cutting `docker stop` off at a fixed 10-second deadline. The
  deadline is the grace period plus the Docker command timeout, with Docker's 10-second default when
  unset and a 10-minute cap. A stop whose CLI still times out is re-inspected and reported as
  stopped when the container has already exited. The stop command remains `docker stop <container>`
  (#94).
- The examples give managed backends `stop_grace_period: 60s` so large worlds can finish saving.

## [2.1.0] - 2026-08-16

### Added

- Added coordinator-owned operator lifecycle commands for mapped servers: `/autostopper start <server>`,
  `/autostopper stop <server>`, `/autostopper restart <server>`, `/autostopper hold <server>`, and
  `/autostopper release <server>`.
- Added command-specific permission nodes (`autostopper.command.start`, `autostopper.command.stop`,
  `autostopper.command.restart`, `autostopper.command.hold`, `autostopper.command.release`) while
  retaining `autostopper.admin` as an explicit umbrella.
- Implemented runtime shutdown holds that suppress automatic inactivity shutdown without blocking
  manual commands or player-driven wake-up. Holds survive unchanged reloads and clear on proxy
  restart, mapping removal, or mapping replacement.
- Manual stop and restart enforce strict safety invariants, refusing execution if connected players
  or lifecycle waiters are present, with an immediate deterministic pre-Docker safety re-check on the
  worker thread.
- Manual restart runs as one atomic coordinator-owned stop → start → readiness sequence under a single
  authoritative future.
- Surfaced hold state in `/autostopper status` and updated `/autostopper help` with permission-filtered
  entries and argument tab completion.
- Pinned stable Velocity 4.0.0 build 6 as the production support line on Java 25, tested by the
  packaged-runtime system tests and the release-candidate Docker/Minecraft gate. Velocity 3.5.1 on
  Java 21 remains the tested minimum/floor, and Velocity 4.1.0-SNAPSHOT on Java 25 remains clearly
  labelled preview validation.
- Added the `RUNNING_UNVERIFIED` operational state so a Docker-running container is not reported as
  ready until AutoStopper has completed the configured readiness contract for the current mapping
  lifecycle.
- Connecting players now receive authoritative inspection, startup, readiness, connection, and
  terminal lifecycle messages. Late arrivals see the current shared stage and unique waiter count.
- Redesigned command and chat presentation around the brand identity ("Empty servers sleep. Players wake them."). Replaced legacy bracketed prefixes with clean brand prompts (`AutoStopper ›`, `AutoStopper ✓`, `AutoStopper !`), added scannable deterministic status rows with humanized states and durations, and introduced permission-filtered help with fuzzy command matching.
- Added contributor-facing message style guide (`docs/message-style-guide.md`).
- Added structured, typed lifecycle telemetry and bounded process-lifetime observability (#74). Authoritative
  lifecycle operations emit machine-parsable `INFO` log lines (`op=`, `server=`, `origin=`, `outcome=`,
  `elapsed_ms=`, `waiters=`) with monotonic duration measurement, and intermediate stage durations emit
  `DEBUG` logs. Simultaneous waiters receive 1 shared startup record plus individual connection wait records.
  Aggregates use thread-safe bounded memory with strictly zero PII and observational exception insulation.

### Changed

- Operational status now reconciles Docker observations lazily with mapping-aware lifecycle
  revisions. Active transitions remain authoritative, stopped/degraded Docker state overrides
  stale quiescent state, and results captured before reload, mapping replacement, or newer
  lifecycle work are discarded without eager startup readiness probing.

### Fixed

- Restored command and lifecycle messages on Velocity 3.5.1 by avoiding the Adventure 5
  component-builder ABI incompatibility and validating every message factory against the pinned
  Velocity runtimes.

## [2.0.0] - 2026-08-13

### Added

- Tested support lines for Velocity 3.5.1 build 615 on Java 21 and Velocity 4.1.0-SNAPSHOT build 16
  on Java 25, using one Java 21 bytecode plugin JAR.
- Explicit, isolated `server_name` to `container_name` mappings with startup/reload preflight.
- Real readiness strategies: Minecraft status protocol, Docker health, or either signal, all with
  bounded per-server deadlines.
- Shared lifecycle coordination so simultaneous players use one startup/readiness operation and
  stop/start/reload races have legal, deterministic outcomes.
- Bounded off-thread Docker execution, bounded plugin shutdown, cancellation, and saturated-worker
  handling without blocking Velocity event or command workers.
- Capped exponential retry for failed automatic stops while preserving activity until a confirmed
  stop.
- Atomic configuration reload: an invalid candidate is rejected completely and the previous
  immutable snapshot remains active.
- Permission nodes `autostopper.command.status` and `autostopper.command.reload`, plus the explicit
  `autostopper.admin` umbrella.
- Typed operational state and sanitized `/autostopper status` diagnostics with operator actions;
  raw Docker stderr remains in operator logs.
- Pinned legacy/current Compose examples, packaged Velocity runtime tests, a real
  Docker/Compose/Minecraft release-candidate gate, reproducible Maven Wrapper builds, strict
  dependency/package checks, SpotBugs, JaCoCo, and deterministic CI gates.
- Installation, security, configuration, troubleshooting, Modrinth, and 1.1.2 migration
  documentation.

### Changed

- Generated inactivity timeout and public documentation now agree on `300` seconds.
- New installations generate no monitored mappings instead of controlling example containers by
  default.
- Velocity's actual lowercase data path is documented as `plugins/autostopper/config.yml`.
- A running Docker container must pass configured readiness before waiting players are connected.
- Inactivity scans retain activity on Docker failures and stop only explicitly mapped, running,
  player-free servers.
- Managed containers are expected to exist in advance and use `restart: "no"`; unmonitored hubs
  remain outside AutoStopper's lifecycle.
- Docker socket/group access is explicitly documented as a privileged, host-root-equivalent trust
  boundary rather than ordinary non-root isolation.
- The canonical build is the checksummed Maven 3.9.16 Wrapper on JDK 21 through 25.

### Removed

- The prototype's custom `/server` command/interceptor. Velocity owns `/server` and its permissions;
  AutoStopper now observes normal pre-connect events.
- Unbounded fixed-delay startup behavior, blocking Docker work on platform workers, and partial or
  silent configuration fallback.

### Migration

Read the complete [1.1.2 to 2.0.0 migration guide](docs/migration-1.1.2-to-2.0.0.md) before replacing
the artifact. The runtime matrix, lowercase data path, permissions, readiness policy, explicit
mappings, pinned examples, and Docker security boundary all require operator review.

## [1.1.2]

- Last 1.x migration source and prototype release tag.

[2.3.0]: https://github.com/Criseda/AutoStopper/compare/2.2.0...2.3.0
[2.2.0]: https://github.com/Criseda/AutoStopper/compare/2.1.1...2.2.0
[2.1.1]: https://github.com/Criseda/AutoStopper/compare/2.1.0...2.1.1
[2.1.0]: https://github.com/Criseda/AutoStopper/compare/2.0.0...2.1.0
[2.0.0]: https://github.com/Criseda/AutoStopper/compare/1.1.2...2.0.0
[1.1.2]: https://github.com/Criseda/AutoStopper/releases/tag/1.1.2
