# Velocity packaged-JAR system tests

The `system-tests` Maven profile runs the final shaded AutoStopper JAR on both
supported Velocity lines. It uses disposable Docker containers so the host JDK
only needs to satisfy the normal build requirement.

| Profile | Runtime image | Velocity artifact | SHA-256 |
|---|---|---|---|
| `legacy` | `eclipse-temurin:21-jre` | `velocity-3.5.1-615.jar` | `b4e3164df5377346854dc6cb9e6a78022b1946ff69e89676313f5f6f1c6f0fb3` |
| `current` | `eclipse-temurin:25-jre` | `velocity-4.1.0-SNAPSHOT-16.jar` | `aebade8be3b15d7c3c61514a50ce857cbf78ee87bd32e8d16d2352c6ca3e472f` |

The harness downloads each content-addressed Velocity JAR, verifies its hash,
and copies the exact versioned `target/AutoStopper-<project-version>.jar` produced by the current
Maven invocation into an isolated runtime. Each profile must prove:

- Velocity discovers the plugin metadata and constructs the injected plugin;
- a valid configuration enables AutoStopper;
- an invalid configuration produces a specific, actionable startup failure;
- Velocity and AutoStopper complete a bounded graceful shutdown; and
- the plugin inside the container has the same SHA-256 as the packaged artifact.

The normal `verify` lifecycle also inspects the shaded JAR for Java 21 class
files, manifest and descriptor correctness, unique entries, relocated
SnakeYAML, absent Velocity-provided libraries, and unexpected classes.

## Requirements

- Docker Engine
- network access to the pinned PaperMC artifacts on the first run
- JDK 21 through 25 for the Maven Wrapper build

## Usage

```powershell
.\mvnw.cmd verify -Psystem-tests
.\mvnw.cmd verify -Psystem-tests -Dvelocity.system.profiles=legacy
.\smoke\run-smoke.ps1 -Profile current
```

```sh
./mvnw verify -Psystem-tests
./mvnw verify -Psystem-tests -Dvelocity.system.profiles=current
```

Startup and shutdown are bounded. Runtime logs are retained under
`target/velocity-system-tests/logs/`; disposable fixture directories and test
containers are removed even when an assertion fails.

These tests deliberately do not mount the Docker socket, start Minecraft, or
exercise a real managed container. The live gate is the reusable
`release-candidate-e2e.yml` workflow and Maven `release-candidate-e2e` profile; the protected
release workflow must pass that gate against the exact JAR it publishes.
