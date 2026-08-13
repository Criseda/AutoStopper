# Legacy example: Java 21 and Velocity 3.5.1

This pinned example uses `itzg/mc-proxy:2026.8.0-java21`, Velocity 3.5.1 build 615, and Java 21.
Do not substitute the Java 25 / Velocity 4.1 values from the current example piecemeal.

> **DANGER — HOST-ROOT-EQUIVALENT ACCESS:** `docker-compose.yml` mounts
> `/var/run/docker.sock` into Velocity. Socket-group access is not ordinary non-root isolation.
> Read the complete [security guidance](../../docs/security.md) before starting the stack.

Follow the repository [installation guide](../../README.md#installation). Put the release JAR at
`velocity_server/plugins/AutoStopper.jar`; the plugin configuration is generated at
`velocity_server/plugins/autostopper/config.yml`.

Managed backends use `restart: "no"`. Register `purpur` and/or `fabric` in Velocity's `[servers]`
table before adding the matching AutoStopper mappings. Unmapped hubs remain outside AutoStopper's
lifecycle.
