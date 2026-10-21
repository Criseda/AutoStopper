# Release-candidate Docker/Minecraft E2E

This opt-in gate runs the exact packaged AutoStopper JAR through the pinned
stable Velocity 4.2.0 line in the Compose topology, a real Purpur 1.21.4
backend, and pinned headless Minecraft protocol clients. It covers first and
simultaneous joins, readiness, idle stop, restart, never-ready behavior, and
failed-stop retry.

## Topology

The default scenarios (`happy-path`, `never-ready`, `failed-stop`) use the
socket-proxy topology from the examples. Velocity runs the stock
`itzg/mc-proxy` image with no entrypoint and no socket mount, has no `docker`
configuration, and finds Docker through `endpoint: auto`:

```text
velocity --tcp--> autostopper-docker --unix--> docker-api-fault-proxy --unix--> /var/run/docker.sock
         (internal network)          (shared volume)             (test only)
```

- `autostopper-docker` is the socket proxy from the examples: same pinned
  image, same `-allowfrom=velocity` and allowlist. The harness fails if the
  examples pin a different image. Its only difference is `-socketpath`, which
  points at the relay instead of the host socket.
- `docker-api-fault-proxy` (`docker-api-fault-proxy.js`) is test-only. It has
  no network, logs every Engine API call that passed the allowlist
  (`AUDIT POST /containers/<name>/start 204`), and in `failed-stop` answers the
  first stop of the backend with HTTP 500. The harness asserts on those audit
  lines and checks that nothing other than inspect, start, and stop of the
  mapped backend reached Docker. `happy-path` also checks that the socket
  proxy refuses container create and list with 403.

The `cli-backend` scenario keeps the deprecated Docker CLI backend covered
until it is removed in 3.0.0. It layers `docker-compose.cli.yml` over the base
file: the 2.2.0-style entrypoint installs the CLI, mounts the socket into
Velocity, and wraps the CLI with an audit log (`docker-cli-wrapper.sh`). It
covers one wake and one idle stop.

## Security

The test-only relay, and Velocity in the `cli-backend` scenario, receive
`/var/run/docker.sock`. Access to that socket is host-root-equivalent: run
this only on an isolated development or CI machine, never on an untrusted
shared Docker host. The fixture uses unique names and only removes resources
from its own generated Compose projects.

## Prerequisites

- Docker Engine with Linux containers and Docker Compose v2
- JDK 21 through 25
- At least 3 GB of free memory and sufficient disk space for the pinned images
- Network access on the first run for images, Velocity, Purpur, and locked npm packages

No Minecraft or Microsoft account is required. The fixture is offline-mode and
disposable; that mode is test-only and does not change the public example.

## Run

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify -Prelease-candidate-e2e
```

```sh
./mvnw --batch-mode --no-transfer-progress clean verify -Prelease-candidate-e2e
```

The harness writes its candidate manifest and scenario evidence under
`target/release-candidate-e2e/evidence/`. Scenario work directories are always
cleaned. On failure, proxy/backend/client logs, the Engine API audit
(`docker-api-audit.log`) or CLI audit (`docker-commands.log`), the socket-proxy
log, Compose state, inspections, and project-scoped resource listings remain
as evidence.

The scheduled workflow also exposes `workflow_call`; the protected-tag release
workflow added by issue #21 must call this gate before publishing the candidate.
