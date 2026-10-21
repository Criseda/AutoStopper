# Docker socket security

## The trust boundary

> **DANGER — HOST-ROOT-EQUIVALENT ACCESS:** Whatever can use `/var/run/docker.sock` controls the
> Docker daemon. It can create privileged containers, mount host filesystems, replace workloads,
> and otherwise take control of the host. Unix group membership only makes the socket reachable by
> a non-root UID; it does not reduce the daemon's authority.

AutoStopper inspects, starts, and stops explicitly configured containers through the Docker Engine
API. The examples keep the socket away from Velocity: a socket-proxy sidecar holds it, and Velocity
reaches Docker only through the proxy's allowlist.

## Socket-proxy sidecar

The examples run [`wollomatic/socket-proxy`](https://github.com/wollomatic/socket-proxy) as the
`autostopper-docker` service:

```yaml
autostopper-docker:
  image: wollomatic/socket-proxy:1.13.1@sha256:3935b709275e4ec35d6ed5a5c4a1f0d01ed31eec5e7234efc3357ecd47689002
  restart: unless-stopped
  user: "0:0"
  read_only: true
  cap_drop:
    - ALL
  command:
    - -listenip=0.0.0.0
    - -allowfrom=velocity
    - -allowGET=^(/v[0-9.]+)?/(_ping|containers/[a-zA-Z0-9_.-]+/json)$
    - -allowPOST=^(/v[0-9.]+)?/containers/[a-zA-Z0-9_.-]+/(start|stop)$
  volumes:
    - /var/run/docker.sock:/var/run/docker.sock:ro
  networks:
    - autostopper-internal
```

What each part does:

- **Allowlist.** Velocity may ping the daemon, inspect a container, and start or stop it. Every
  other request is refused with 403 or 405, including container create, exec, kill, delete,
  restart, logs, and listing containers or images.
- **`-allowfrom=velocity`.** Only the `velocity` service may connect. This matters on Linux: the
  Docker host can reach containers on `internal: true` networks through the bridge address, so with
  a CIDR such as `0.0.0.0/0` any local user could use the proxy. The name is looked up per request,
  and an unresolvable name is refused.
- **`autostopper-internal` with `internal: true`.** Containers on other networks cannot reach the
  proxy, and nothing is published on the host.
- **`user: "0:0"`.** The proxy runs as root so it can open the socket on any host without knowing
  the socket's group ID. `read_only` and `cap_drop: ALL` limit what that root process can do inside
  its container. The socket mount is `:ro`, which only stops the proxy from replacing the socket
  file; API calls still pass through.
- **Digest pin.** The proxy holds the real socket, so it is pinned by digest. Update the tag and
  digest together, deliberately.
- **`restart: unless-stopped`.** The proxy exits if it cannot open the socket, and a daemon restart
  stops it. The restart policy brings it back with the stack.

What it does **not** protect:

- **Any container can be stopped or started.** The container-name pattern matches every container
  on the host, not only your backends. Pin it to your backends if other workloads share the daemon:

  ```yaml
  - -allowPOST=^(/v[0-9.]+)?/containers/(purpur-server|fabric-server)/(start|stop)$
  ```

  Pin `-allowGET` the same way to keep other containers out of inspect:

  ```yaml
  - -allowGET=^(/v[0-9.]+)?/(_ping|containers/(purpur-server|fabric-server)/json)$
  ```
- **Inspect returns a container's environment variables**, which often hold passwords and tokens.
  Pinning `-allowGET` to your backends keeps other containers' secrets out of reach.
- **The proxy itself** is a root process holding the socket. Treat its image like any other
  host-level code.

Within those limits, a compromised Velocity, plugin, or proxy container can stop and start
containers and read what inspect returns, but it cannot create, reconfigure, or exec into
containers through Docker.

## Direct socket mount

You can instead mount `/var/run/docker.sock` into Velocity and give the proxy user the socket's
group (see [Alternatives](../README.md#alternatives)). Then any process in the proxy container has
full, host-root-equivalent control of Docker. A read-only bind mount does not change that, because
API calls still go through. The deprecated custom entrypoint from 2.2.0 and earlier examples is
the same boundary.

## Deployment expectations

- Prefer a dedicated Docker host or VM for the Minecraft stack. Do not place unrelated sensitive
  workloads or secrets on the same daemon.
- Treat every Velocity plugin and the proxy image as trusted code. Keep the plugin set minimal and
  update it deliberately.
- Restrict who can edit the Compose files, proxy data directory, plugin JARs, and AutoStopper
  configuration. Anyone who can replace code in the proxy inherits its Docker access.
- Never publish the Docker API or the socket proxy's port. Without TLS, AutoStopper's TCP endpoint is
  meant for a private container network only.
- Use explicit `server_name` to `container_name` mappings. Do not generate container names from
  player input or grant untrusted users configuration-reload access.
- Keep managed containers on `restart: "no"`. Use an always-on restart policy only for deliberately
  unmonitored services such as a hub.
- Protect proxy logs. Raw Docker error output is kept there for operators and may reveal local
  container names or host details; player-facing diagnostics are sanitized.
- Back up data before changing container lifecycle policy, and test upgrades on an isolated host.

Rootless Docker can reduce host impact further, but it is not part of AutoStopper's tested
installation matrix.

## Incident response

If an untrusted plugin or proxy container may have used Docker access:

1. isolate the Docker host from the network;
2. preserve relevant proxy, socket-proxy, daemon, and container logs;
3. rotate credentials and secrets accessible from the host or its containers, including any in
   container environment variables;
4. rebuild the host and workloads from trusted sources rather than trusting container removal
   alone; and
5. audit configuration, images, volumes, mounts, and daemon access before restoring service.

Removing the socket mount or the proxy prevents future access, but it does not reverse changes
already made through the daemon.
