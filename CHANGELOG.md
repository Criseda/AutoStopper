# Changelog

All notable AutoStopper changes are documented here.

## [Unreleased]

### Added

- Pinned stable Velocity 4.0.0 build 6 as the production support line on Java 25, tested by the
  packaged-runtime system tests and the release-candidate Docker/Minecraft gate. Velocity 3.5.1 on
  Java 21 remains the tested minimum/floor, and Velocity 4.1.0-SNAPSHOT on Java 25 remains clearly
  labelled preview validation.
- Added the `RUNNING_UNVERIFIED` operational state so a Docker-running container is not reported as
  ready until AutoStopper has completed the configured readiness contract for the current mapping
  lifecycle.
- Connecting players now receive authoritative inspection, startup, readiness, connection, and
  terminal lifecycle messages. Late arrivals see the current shared stage and unique waiter count,
- Redesigned command and chat presentation around the brand identity ("Empty servers sleep. Players wake them."). Replaced legacy bracketed prefixes with clean brand prompts (`AutoStopper ›`, `AutoStopper ✓`, `AutoStopper !`), added scannable deterministic status rows with humanized states and durations, and introduced permission-filtered help with fuzzy command matching.
- Added contributor-facing message style guide (`docs/message-style-guide.md`).

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

[2.0.0]: https://github.com/Criseda/AutoStopper/compare/1.1.2...2.0.0
[1.1.2]: https://github.com/Criseda/AutoStopper/releases/tag/1.1.2
