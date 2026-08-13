# Docker socket security

## The trust boundary

> **DANGER — HOST-ROOT-EQUIVALENT ACCESS:** Mounting `/var/run/docker.sock` gives the Velocity
> container control of the Docker daemon. A process with access can create privileged containers,
> mount host filesystems, replace workloads, and otherwise take control of the host. Unix group
> membership only makes the socket reachable by a non-root UID; it does not reduce the daemon's
> authority or provide ordinary non-root isolation.

AutoStopper uses the Docker CLI to inspect, start, and stop explicitly configured containers. The
tested examples make the host socket available to the proxy and add the image's `bungeecord` user
to the socket group. That is a functional permission setup, not a security sandbox.

## Deployment expectations

- Prefer a dedicated Docker host or VM for the Minecraft stack. Do not place unrelated sensitive
  workloads or secrets on the same daemon.
- Treat every Velocity plugin and the proxy image as trusted host-level code. Keep the plugin set
  minimal and update it deliberately.
- Restrict who can edit the Compose files, proxy data directory, plugin JARs, entrypoint, and
  AutoStopper configuration. Anyone who can replace code in the proxy can inherit socket access.
- Never publish an unauthenticated Docker TCP endpoint. The pinned examples use only the local Unix
  socket.
- Do not assume a read-only bind mount protects the daemon. AutoStopper needs write operations, and
  Docker's API authority is not meaningfully reduced by a read-only mount of the socket inode.
- Use explicit `server_name` to `container_name` mappings. Do not generate container names from
  player input or grant untrusted users configuration-reload access.
- Keep managed containers on `restart: "no"`. Use an always-on restart policy only for deliberately
  unmonitored services such as a hub.
- Protect proxy logs. Raw Docker stderr is kept there for operators and may reveal local container
  names or host details; player-facing diagnostics are sanitized.
- Back up data before changing container lifecycle policy, and test upgrades on an isolated host.

Rootless Docker or a narrowly scoped Docker API proxy can reduce host impact in some deployments,
but neither is part of AutoStopper's tested installation matrix. A filtering proxy would need to
allow the exact inspect, start, and stop operations used by AutoStopper without making container
identity ambiguous. Validate such a design independently before relying on it.

## Example mount

The public examples contain this mount only with an adjacent warning:

```yaml
volumes:
  - ./velocity_server:/server
  # SECURITY: host-root-equivalent Docker control; read docs/security.md first.
  - /var/run/docker.sock:/var/run/docker.sock
```

If accepting that boundary is inappropriate for the host, do not install AutoStopper with direct
socket access. The plugin does not currently provide a lower-privilege remote lifecycle backend.

## Incident response

If an untrusted plugin or proxy container may have used the socket:

1. isolate the Docker host from the network;
2. preserve relevant proxy, daemon, and container logs;
3. rotate credentials and secrets accessible from the host or its containers;
4. rebuild the host and workloads from trusted sources rather than trusting container removal
   alone; and
5. audit configuration, images, volumes, mounts, and daemon access before restoring service.

Removing the socket mount prevents future access from that container, but it does not reverse
changes already made through the daemon.
