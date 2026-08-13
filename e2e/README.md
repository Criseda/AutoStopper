# Release-candidate Docker/Minecraft E2E

This opt-in gate runs the exact packaged AutoStopper JAR through the pinned
stable Velocity 4.0.0 line in the Compose topology, a real Purpur 1.21.4
backend, and pinned headless Minecraft protocol clients. It covers first and
simultaneous joins, readiness, idle stop, restart, never-ready behavior, and
failed-stop retry.

## Security

The proxy receives `/var/run/docker.sock`. Access to that socket is
host-root-equivalent: run this only on an isolated development or CI machine,
never on an untrusted shared Docker host. The fixture uses unique names and
only removes resources from its own generated Compose projects.

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
cleaned. On failure, proxy/backend/client logs, Docker command audits, Compose
state, inspections, and project-scoped resource listings remain as evidence.

The scheduled workflow also exposes `workflow_call`; the protected-tag release
workflow added by issue #21 must call this gate before publishing the candidate.
