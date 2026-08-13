# Modrinth listing source for AutoStopper 2.0.0

This file is the repository-reviewed source for the Modrinth project and version metadata. The
project-page copy and gallery were applied by the maintainer on 2026-08-13. The protected release
workflow publishes only the already-validated, byte-identical JAR and its version metadata.

The prior public listing was audited on 2026-08-13. Its old copy claimed Velocity 3.3+, a generic
Java 21+ runtime, a 900-second default, `plugins/AutoStopper`, unpinned `latest` images, one admin
permission, and socket-group setup without the host-control warning. The replacement below remains
the source of truth for future listing reviews.

## Project metadata

| Field | 2.0.0 value |
|---|---|
| Project type | Plugin |
| Loader/platform | Velocity |
| Side | Server/proxy only |
| Summary | Empty servers sleep. Players wake them. AutoStopper starts mapped Docker backends on demand and stops them after inactivity. |
| License | MIT |
| Source | `https://github.com/Criseda/AutoStopper` |
| Issues | `https://github.com/Criseda/AutoStopper/issues` |
| Releases | `https://github.com/Criseda/AutoStopper/releases` |
| Changelog | `https://github.com/Criseda/AutoStopper/blob/master/CHANGELOG.md` |
| Installation | `https://github.com/Criseda/AutoStopper#installation` |
| Migration | `https://github.com/Criseda/AutoStopper/blob/master/docs/migration-1.1.2-to-2.0.0.md` |

Use `docs/assets/autostopper-banner.png` as the project banner/gallery image. Keep Wiki and Discord
links only while their maintainer-selected destinations remain supported; remove them when they
become stale.

Upload the banner as the featured Modrinth gallery image with:

- title: `AutoStopper - Empty servers sleep. Players wake them.`
- description: `A stopped Docker backend wakes automatically when players connect through Velocity.`
- ordering: `0`

The PNG is 1672 by 941 pixels and approximately 1.6 MB, below Modrinth's 5 MiB gallery limit. After
upload, replace the raw GitHub image URL in the description below with the returned Modrinth CDN URL
so the published project page uses its own gallery asset.

## Version metadata

| Field | 2.0.0 value |
|---|---|
| Version number | `2.0.0` |
| Version title | `AutoStopper 2.0.0` |
| Version type | Release only after all Phase 4 gates pass |
| Loader | Velocity |
| Java | Java 21 bytecode; validated proxy runtimes are Java 21 and Java 25 as paired below |
| Compatible Minecraft range | Java Edition 1.7.2 through 26.2 when used through a supported Velocity runtime |
| Directly tested backend | Java Edition 1.21.4 (Purpur release-candidate stack) |
| Dependencies | None declared on Modrinth; Docker Engine/CLI/socket access are external operational prerequisites |
| File | Exact shaded JAR already validated by CI and the release-candidate E2E gate |
| Integrity | Publish and cross-check the same SHA-256 as the GitHub release |

The release pipeline derives the full per-version game list from the `minecraftVersionRange`
endpoints in [`release-metadata.json`](../release/release-metadata.json), expanded against the
release catalogue in `scripts/release/release_lib.py`; the 2.0.0 listing was created from the same
list. Modrinth's Minecraft game-version tags do not express the proxy/JVM contract. The full range
is compatible because AutoStopper uses Velocity API events and a generic Minecraft status request
rather than backend-specific APIs; only the listed backend is directly exercised by the
release-candidate stack. Backend implementation and Velocity player-forwarding requirements still
apply, and untested newer Velocity, Java, proxy-image, or backend combinations are not described as
supported merely because Modrinth offers a broad selector.

## Replacement project description

The Markdown below is intended to replace the Modrinth project description verbatim.

---

![AutoStopper - Empty servers sleep. Players wake them.](https://raw.githubusercontent.com/Criseda/AutoStopper/master/docs/assets/autostopper-banner.png)

# Stop running empty Minecraft servers

Your players should not have to care whether a backend server is running, and you should not have
to keep every server online when nobody is using it.

**AutoStopper gives a Velocity network both.** It stops mapped Docker-based backend servers after
they have been empty for a while, then starts them automatically the next time a player connects.
Players use your network normally; AutoStopper handles the container lifecycle and waits until the
server is genuinely ready before sending them through.

> Install it on Velocity, map the containers you want it to manage, and let quiet servers sleep.

## Why use AutoStopper?

### Save resources when your network is quiet

AutoStopper watches only the servers you explicitly map. Once a monitored server has no connected
players and passes its inactivity timeout, its container is stopped. Always-on hubs and lobbies can
remain completely unmonitored.

### Wake servers when players need them

When a player connects to a sleeping backend, AutoStopper starts the mapped container and keeps the
request moving. Players do not need a client mod, a special account, or a replacement `/server`
command.

### Wait for Minecraft, not just a running container

`Running=true` does not mean a Minecraft server is ready. AutoStopper can wait for a valid
Minecraft status response, Docker health, or either signal before connecting the player. Every
probe and Docker command has a bounded deadline.

### Handle the rush back in

If several players arrive at once, they share one startup and readiness operation instead of
racing multiple container starts. Waiting players are connected when the backend becomes ready.

### Fail clearly and recover safely

Failed automatic stops use capped retries without pretending the server stopped. Atomic reloads
keep the previous configuration active if the replacement is invalid. `/autostopper status` shows
safe operational details and a suggested action while raw Docker output stays in operator logs.

## What the player experiences

1. The player selects a monitored server through normal Velocity routing.
2. If its container is stopped, AutoStopper tells the player it is waking the server.
3. AutoStopper starts one shared lifecycle operation and waits for real readiness.
4. The player is connected as soon as the backend can answer Minecraft traffic.
5. After everyone leaves and the inactivity period expires, the container sleeps again.

If the backend is already available, the connection proceeds normally. Servers omitted from
`monitored_servers` are never intercepted, started, or stopped by AutoStopper.

## Tested support

One Java 21 bytecode AutoStopper JAR is tested on all three complete runtime lines below:

| Support line | Role | Proxy image | Java | Velocity |
|---|---|---:|---:|---|
| Legacy | Minimum/floor | `itzg/mc-proxy:2026.8.0-java21` | 21 | 3.5.1 build 615 |
| Stable | Production | `itzg/mc-proxy:2026.8.0-java25` | 25 | 4.0.0 build 6 |
| Preview | Snapshot validation | `itzg/mc-proxy:2026.8.0-java25` | 25 | 4.1.0-SNAPSHOT build 16 |

Velocity 4.x requires at least Java 25; the preview line validates a PaperMC snapshot and is not a
production baseline. Keep the proxy image/JVM and Velocity build from one row together. The real
release-candidate stack is exercised with a Purpur 1.21.4 backend, actual protocol clients,
simultaneous joins, idle stop, restart, never-ready behavior, and failed-stop retry on the stable
line.

## Quick start

1. Choose the complete
   [Java 21 / Velocity 3.5.1 example](https://github.com/Criseda/AutoStopper/tree/master/examples/velocity-legacy)
   (minimum), [Java 25 / Velocity 4.0 example](https://github.com/Criseda/AutoStopper/tree/master/examples/velocity-stable)
   (recommended production), or [Java 25 / Velocity 4.1 preview example](https://github.com/Criseda/AutoStopper/tree/master/examples/velocity-preview).
2. Put the downloaded, unchanged JAR at `velocity_server/plugins/AutoStopper.jar`.
3. Register each managed backend in Velocity's `[servers]` table.
4. Start the stack once and edit the generated lowercase path
   `velocity_server/plugins/autostopper/config.yml`.
5. Map only real Velocity server names to existing Docker containers, then restart Velocity or run
   `/autostopper reload`.
6. Run `/autostopper status` and resolve any degraded mapping before opening the proxy to players.

> **Important Docker security warning:** the tested installation mounts `/var/run/docker.sock`
> into Velocity. Access to that socket is effectively equivalent to root control of the Docker
> host. Adding the proxy user to the socket group is not ordinary non-root isolation. Prefer a
> dedicated host or VM, install only trusted plugins, and read the full
> [security guidance](https://github.com/Criseda/AutoStopper/blob/master/docs/security.md) before
> starting the stack.

Managed backend containers must already exist and use `restart: "no"`, otherwise Docker may undo a
successful inactivity stop. Leave always-on hubs out of `monitored_servers`.

## Sensible defaults, explicit control

- 5-minute inactivity timeout
- 10-second plugin shutdown deadline
- 3 automatic stop attempts with 60-to-300-second capped backoff
- Minecraft status readiness with a 120-second overall deadline
- no monitored containers until you explicitly add mappings

Every value is configurable, validated as one atomic snapshot, and documented in the
[configuration reference](https://github.com/Criseda/AutoStopper/blob/master/docs/configuration.md).

## Commands and permissions

| Command | Permission |
|---|---|
| `/autostopper` or `/as` | Public plugin information |
| `/autostopper help` | Public, permission-filtered help |
| `/autostopper status` | `autostopper.command.status` or `autostopper.admin` |
| `/autostopper reload` | `autostopper.command.reload` or `autostopper.admin` |

AutoStopper does not register or replace Velocity's `/server` command and exposes no manual start or
stop command.

## Learn more

- [Complete installation guide](https://github.com/Criseda/AutoStopper#installation)
- [Configuration reference](https://github.com/Criseda/AutoStopper/blob/master/docs/configuration.md)
- [Troubleshooting](https://github.com/Criseda/AutoStopper/blob/master/docs/troubleshooting.md)
- [1.1.2 to 2.0.0 migration](https://github.com/Criseda/AutoStopper/blob/master/docs/migration-1.1.2-to-2.0.0.md)
- [Changelog](https://github.com/Criseda/AutoStopper/blob/master/CHANGELOG.md)
- [Report an issue](https://github.com/Criseda/AutoStopper/issues)

**Ready to let empty servers sleep?** Choose a pinned example, review the Docker security boundary,
and install the same validated JAR published here and on GitHub Releases.

---

## 2.0.0 version changelog

The protected release workflow derives the version changelog from the dated `2.0.0` section of
[`CHANGELOG.md`](../CHANGELOG.md) and the game-version list from the `minecraftVersionRange` in
[`release-metadata.json`](../release/release-metadata.json). The Modrinth file hash, GitHub asset
hash, tag, POM version, Velocity descriptor version, and changelog version must all agree. See
[`releasing.md`](releasing.md) for the publication and recovery procedure.
