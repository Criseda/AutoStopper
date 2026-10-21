# Preview example: Java 25 and Velocity 4.2.1 snapshot

This pinned example validates the next 4.x patch release ahead of its release. It uses
`itzg/mc-proxy:2026.8.0-java25`, Velocity 4.2.1-SNAPSHOT build 41, and Java 25. Velocity 4.2.1 is a
PaperMC snapshot; it is not a production baseline. Velocity 4.x requires at least Java 25; do not
run this build on Java 21. For production, prefer the [stable example](../velocity-stable/).

> **Docker access:** `docker-compose.yml` runs an `autostopper-docker` socket proxy that holds
> `/var/run/docker.sock` as root and lets Velocity only inspect, start, and stop containers.
> Velocity uses the stock image with no custom entrypoint and no socket mount. Read the complete
> [security guidance](../../docs/security.md) before starting the stack.

Follow the repository [installation guide](../../README.md#installation). The proxy installs
AutoStopper from Modrinth at startup (`MODRINTH_PROJECTS`, pinned to one release); the plugin
configuration is generated at `velocity_server/plugins/autostopper/config.yml`.

Managed backends use `restart: "no"`. Register `purpur` and/or `fabric` in Velocity's `[servers]`
table before adding the matching AutoStopper mappings. Unmapped hubs remain outside AutoStopper's
lifecycle.
