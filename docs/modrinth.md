# Modrinth listing source for AutoStopper 2.0.0

This file is the repository-reviewed source for the Modrinth project and version metadata. Issue
#21 applies it when the already-validated, byte-identical 2.0.0 JAR is published. Do not mark the
version stable or upload a separately rebuilt artifact before that release workflow passes.

The public listing was audited on 2026-08-13. Its old copy still claimed Velocity 3.3+, a generic
Java 21+ runtime, a 900-second default, `plugins/AutoStopper`, unpinned `latest` images, one admin
permission, and socket-group setup without the host-control warning. Replace that copy in full.

## Project metadata

| Field | 2.0.0 value |
|---|---|
| Project type | Plugin |
| Loader/platform | Velocity |
| Side | Server/proxy only |
| Summary | Starts mapped Docker-based Minecraft servers on player connection and stops them after inactivity, with bounded readiness and retries. |
| License | MIT |
| Source | `https://github.com/Criseda/AutoStopper` |
| Issues | `https://github.com/Criseda/AutoStopper/issues` |
| Releases | `https://github.com/Criseda/AutoStopper/releases` |
| Changelog | `https://github.com/Criseda/AutoStopper/blob/master/CHANGELOG.md` |
| Installation | `https://github.com/Criseda/AutoStopper#installation` |
| Migration | `https://github.com/Criseda/AutoStopper/blob/master/docs/migration-1.1.2-to-2.0.0.md` |

Remove stale Wiki and Discord links unless maintained destinations are verified during #21. Do not
use a README URL as a fake Wiki destination.

## Version metadata

| Field | 2.0.0 value |
|---|---|
| Version number | `2.0.0` |
| Version title | `AutoStopper 2.0.0` |
| Version type | Release only after all Phase 4 gates pass |
| Loader | Velocity |
| Java | Java 21 bytecode; validated proxy runtimes are Java 21 and Java 25 as paired below |
| Validated Minecraft backend | Java Edition 1.21.4 (Purpur release-candidate stack) |
| Dependencies | None declared on Modrinth; Docker Engine/CLI/socket access are external operational prerequisites |
| File | Exact shaded JAR already validated by CI and the release-candidate E2E gate |
| Integrity | Publish and cross-check the same SHA-256 as the GitHub release |

Modrinth's Minecraft game-version tags do not express the proxy/JVM contract. Select only backend
versions explicitly validated for the release, then put this exact support table at the top of the
description and version changelog:

| Support line | Java | Velocity |
|---|---:|---|
| Legacy | 21 | 3.5.1 build 615 |
| Current | 25 | 4.1.0-SNAPSHOT build 16 |

Do not describe untested newer Velocity, Java, image, or backend combinations as supported merely
because Modrinth offers a broad game-version selector.

## Replacement project description

The Markdown below is intended to replace the project description verbatim.

---

# AutoStopper

AutoStopper is a Velocity proxy plugin that starts explicitly mapped Docker-based Minecraft servers
when a player connects and stops them after a configurable period with no players. It uses bounded
Minecraft status and/or Docker-health readiness, shares simultaneous startup requests, retries
failed automatic stops, and keeps Docker work off Velocity's event and command workers.

## Supported runtimes

| Support line | Proxy image | Java | Velocity |
|---|---|---:|---|
| Legacy | `itzg/mc-proxy:2026.8.0-java21` | 21 | 3.5.1 build 615 |
| Current | `itzg/mc-proxy:2026.8.0-java25` | 25 | 4.1.0-SNAPSHOT build 16 |

The same Java 21 bytecode AutoStopper JAR is tested on both lines. Keep the proxy image/JVM and
Velocity build from one row together. The release-candidate backend is Purpur 1.21.4.

## Security warning

> **DANGER — HOST-ROOT-EQUIVALENT ACCESS:** The tested setup mounts
> `/var/run/docker.sock` into Velocity. Any process that can use that socket can control the Docker
> host with privileges effectively equivalent to root. Adding the proxy user to the socket group is
> not ordinary non-root isolation. Prefer a dedicated host or VM, install only trusted plugins, and
> read the full [security guidance](https://github.com/Criseda/AutoStopper/blob/master/docs/security.md).

## Installation

1. Choose one complete pinned Compose example:
   [Java 21 / Velocity 3.5.1](https://github.com/Criseda/AutoStopper/tree/master/examples/velocity-legacy)
   or [Java 25 / Velocity 4.1](https://github.com/Criseda/AutoStopper/tree/master/examples/velocity-current).
2. Put the downloaded, unchanged JAR at `velocity_server/plugins/AutoStopper.jar`.
3. Review and accept the Docker socket security boundary, then run `docker compose up -d`.
4. Register each managed backend in Velocity's `[servers]` table.
5. Edit the generated lowercase path `velocity_server/plugins/autostopper/config.yml`. Map only
   real Velocity server names to existing Docker container names.
6. Restart Velocity or run `/autostopper reload`, then check `/autostopper status` and the startup
   preflight.

Managed backend containers must use `restart: "no"`. Leave always-on hubs out of
`monitored_servers`; AutoStopper does not intercept or stop unmonitored Velocity servers.

The generated defaults are a 300-second inactivity timeout, 10-second plugin shutdown deadline,
three stop attempts with 60-to-300-second capped exponential backoff, and per-server
`minecraft_status` readiness with a 120-second overall deadline.

## Commands and permissions

- `/autostopper` or `/as` — public plugin information
- `/autostopper help` — public permission-filtered help
- `/autostopper status` — `autostopper.command.status` or `autostopper.admin`
- `/autostopper reload` — `autostopper.command.reload` or `autostopper.admin`

AutoStopper does not register or replace Velocity's `/server` command and exposes no manual start or
stop command.

## Documentation

- [Complete installation and behavior](https://github.com/Criseda/AutoStopper#readme)
- [Configuration reference](https://github.com/Criseda/AutoStopper/blob/master/docs/configuration.md)
- [Troubleshooting](https://github.com/Criseda/AutoStopper/blob/master/docs/troubleshooting.md)
- [1.1.2 to 2.0.0 migration](https://github.com/Criseda/AutoStopper/blob/master/docs/migration-1.1.2-to-2.0.0.md)
- [Changelog](https://github.com/Criseda/AutoStopper/blob/master/CHANGELOG.md)
- [Issues](https://github.com/Criseda/AutoStopper/issues)

---

## 2.0.0 version changelog

Use the `2.0.0` section of [`CHANGELOG.md`](../CHANGELOG.md) as the version changelog. At publication,
replace `Unreleased` with the release date and replace any `HEAD` comparison link with the protected
2.0.0 tag. The Modrinth file hash, GitHub asset hash, tag, POM version, Velocity descriptor version,
and changelog version must all agree under issue #21's release workflow.
