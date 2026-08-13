# Migrating from AutoStopper 1.1.2 to 2.0.0

AutoStopper 2.0.0 is a substantial operational upgrade, not a drop-in JAR swap. Preserve the old
deployment and configuration until the new mapping preflight and a real player connection succeed.

## Breaking and security-relevant changes

| 1.1.2 | 2.0.0 | Required action |
|---|---|---|
| Documentation described Velocity 3.3+ and Java 21+ generically. | The tested lines are exactly Velocity 3.5.1 build 615 on Java 21 and Velocity 4.1.0-SNAPSHOT build 16 on Java 25. The plugin JAR itself is Java 21 bytecode. | Choose one complete pinned example. Do not mix its proxy image/JVM and Velocity build. |
| README used `plugins/AutoStopper`. | Velocity's data directory follows plugin ID `autostopper`: `plugins/autostopper/config.yml`. | Move or merge any manually created mixed-case configuration into the lowercase directory. |
| Generated defaults included example `purpur` and `fabric` mappings. | New installs generate `monitored_servers: []`. | Add only real Velocity server names and existing Docker containers. |
| Configuration accepted loose values and could fall back silently. | YAML is strictly typed and validated; duplicate/unknown Velocity mappings, duplicate containers, invalid ranges, and partial readiness targets reject the whole candidate. Failed reloads retain the previous snapshot. | Correct all validation errors before expecting a reload to apply. |
| Only inactivity timeout and mappings were configured. | Shutdown deadline, bounded stop retries, and per-server readiness policies are public configuration. | Review the new fields and select a readiness strategy for every mapping. |
| The prototype registered custom `/server` interception behavior. | AutoStopper no longer registers or replaces `/server`, does not check `velocity.command.server`, and exposes no manual start/stop command. Connections are intercepted through Velocity's pre-connect event. | Restore normal Velocity `/server` command and permission configuration. Remove any permission assumptions that depended on the old interceptor. |
| AutoStopper commands were effectively public. | Status requires `autostopper.command.status`; reload requires `autostopper.command.reload`; `autostopper.admin` grants both. | Grant the minimum required nodes to operators before cutover. |
| A running container was treated as ready after a delay. | Startup uses bounded Minecraft status and/or Docker-health readiness. Concurrent players share one startup. | Ensure the configured readiness endpoint is reachable from the proxy container and health-only images define a `HEALTHCHECK`. |
| Docker/socket permission instructions implied ordinary non-root access. | Socket access is documented as host-root-equivalent control. | Reassess the host trust boundary. Prefer a dedicated host/VM and restrict plugins, configuration, and Docker access. |
| Stops and shutdown behavior were not fully bounded or diagnostic. | Docker work is off-thread and bounded; failed stops retry with capped backoff; shutdown cancels plugin work within a configured deadline; `/autostopper status` includes safe remediation. | Set retry/shutdown policy and monitor the startup preflight before enabling player traffic. |

## Upgrade procedure

1. **Back up the 1.1.2 deployment.** Preserve the plugin JAR, Velocity configuration, AutoStopper
   configuration, Compose files, backend data, and the exact container names returned by
   `docker ps -a`.
2. **Choose the support line.** Copy either `examples/velocity-legacy` or
   `examples/velocity-current` as a complete unit. Keep the image tag and Velocity version/build
   unchanged for the initial cutover.
3. **Review Docker security.** Read [Docker socket security](security.md). The entrypoint's socket
   group setup enables host-root-equivalent daemon control even though Velocity runs as
   `bungeecord`.
4. **Prepare Velocity first.** Ensure every intended `server_name` exists in
   `velocity.toml` under `[servers]` and is reachable from the proxy container. Leave hubs and
   lobbies out of AutoStopper mappings if they must remain always on.
5. **Prepare containers.** Create every mapped backend before starting AutoStopper. Use stable,
   unique `container_name` values and `restart: "no"` for managed backends.
6. **Replace the plugin JAR.** Stop Velocity, remove old AutoStopper JAR copies from `plugins`, and
   install exactly one validated 2.0.0 JAR. Do not rename or modify its contents.
7. **Write the new configuration** at `plugins/autostopper/config.yml`. A minimal migrated example
   is:

   ```yaml
   inactivity_timeout_seconds: 300
   shutdown_timeout_seconds: 10
   stop_retry:
     max_attempts: 3
     initial_backoff_seconds: 60
     max_backoff_seconds: 300
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

   `inactivity_timeout_seconds` remains `300`, matching the generated 1.1.2 code default. Do not
   carry forward the stale `900`-second value from the old README unless 15 minutes is your
   deliberate policy.
8. **Grant operator permissions.** Give status-only staff `autostopper.command.status`. Give
   configuration operators `autostopper.command.reload`. Reserve `autostopper.admin` for users who
   should receive both capabilities.
9. **Start and inspect preflight.** Start Velocity and require `AutoStopper plugin initialized!`
   plus a healthy or understood mapping preflight in the log. Run `/autostopper status`; resolve
   `DOCKER_UNAVAILABLE`, `FAILED`, and any missing-container detail before player testing.
10. **Test the player lifecycle.** With a disposable or backed-up backend, confirm the first player
    starts a stopped container, a second simultaneous player shares startup, readiness completes,
    both players connect, and the empty server stops after the inactivity period.
11. **Retain rollback material** until the deployment has completed at least one start, readiness,
    inactivity stop, and restart cycle.

## Configuration conversion notes

The old fields keep their names:

```yaml
inactivity_timeout_seconds: 300
monitored_servers:
  - server_name: purpur
    container_name: purpur-server
```

Add the new top-level shutdown and retry blocks. A missing `readiness` block defaults to
`minecraft_status` using the Velocity-registered backend address, but an explicit Compose service
name and port is recommended because it makes container-network routing unambiguous.

Container and server names must be exact, unique, nonblank strings without leading or trailing
whitespace. `server_name` must already exist in Velocity when AutoStopper loads or reloads.

## Rollback

If cutover fails, stop Velocity and restore the complete backed-up 1.1.2 plugin, configuration,
Velocity files, and Compose deployment together. Do not leave multiple AutoStopper JARs installed,
and do not assume 2.0-only readiness/retry configuration is understood by the old release. Restore
backend containers/data from backup if lifecycle testing changed them.

Rollback does not remove the Docker socket risk. If the failure involved suspected untrusted daemon
access, follow the incident-response guidance in [Docker socket security](security.md) instead of
treating a JAR rollback as remediation.
