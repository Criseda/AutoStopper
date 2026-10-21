# Legacy example: Java 21 and Velocity 3.5.1

This pinned example is the minimum supported (floor) line. It uses
`itzg/mc-proxy:2026.8.0-java21`, Velocity 3.5.1 build 615, and Java 21. For production on the
stable Velocity 4.x line, prefer the [stable example](../velocity-stable/). Do not substitute the
Java 25 / Velocity 4.x values from the stable or preview examples piecemeal.

> **Docker access:** `docker-compose.yml` runs an `autostopper-docker` socket proxy that holds
> `/var/run/docker.sock` as root and lets Velocity only inspect, start, and stop containers.
> Velocity uses the stock image with no custom entrypoint and no socket mount. Read the complete
> [security guidance](../../docs/security.md) before starting the stack.

Follow the repository [installation guide](../../README.md#installation). Put the release JAR at
`velocity_server/plugins/AutoStopper.jar`; the plugin configuration is generated at
`velocity_server/plugins/autostopper/config.yml`.

Managed backends use `restart: "no"`. Register `purpur` and/or `fabric` in Velocity's `[servers]`
table before adding the matching AutoStopper mappings. Unmapped hubs remain outside AutoStopper's
lifecycle.
