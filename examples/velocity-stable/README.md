# Stable example: Java 25 and Velocity 4.0.0

This pinned example is the recommended production line. It uses
`itzg/mc-proxy:2026.8.0-java25`, Velocity 4.0.0 build 6, and Java 25. Velocity 4.x requires at
least Java 25; do not run this build on Java 21.

> **DANGER — HOST-ROOT-EQUIVALENT ACCESS:** `docker-compose.yml` mounts
> `/var/run/docker.sock` into Velocity. Socket-group access is not ordinary non-root isolation.
> Read the complete [security guidance](../../docs/security.md) before starting the stack.

Follow the repository [installation guide](../../README.md#installation). Put the release JAR at
`velocity_server/plugins/AutoStopper.jar`; the plugin configuration is generated at
`velocity_server/plugins/autostopper/config.yml`.

Managed backends use `restart: "no"`. Register `purpur` and/or `fabric` in Velocity's `[servers]`
table before adding the matching AutoStopper mappings. Unmapped hubs remain outside AutoStopper's
lifecycle.
