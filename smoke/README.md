# Velocity packaged-JAR system tests

The `system-tests` Maven profile runs the final shaded AutoStopper JAR on all
supported Velocity lines. It uses disposable Docker containers so the host JDK
only needs to satisfy the normal build requirement.

| Profile | Role | Runtime image | Proxy JVM | Velocity artifact | Official download URL | SHA-256 |
|---|---|---|---|---|---|---|
| `legacy` | Minimum/floor | `eclipse-temurin:21-jre` | 21 | `velocity-3.5.1-615.jar` | `https://fill-data.papermc.io/v1/objects/b4e3164df5377346854dc6cb9e6a78022b1946ff69e89676313f5f6f1c6f0fb3/velocity-3.5.1-615.jar` | `b4e3164df5377346854dc6cb9e6a78022b1946ff69e89676313f5f6f1c6f0fb3` |
| `stable` | Production | `eclipse-temurin:25-jre` | 25 | `velocity-4.0.0-6.jar` | `https://fill-data.papermc.io/v1/objects/4540289f48c83e305fc2f2c495a84d1f4d0b7f360830251e169dd5a208740e70/velocity-4.0.0-6.jar` | `4540289f48c83e305fc2f2c495a84d1f4d0b7f360830251e169dd5a208740e70` |
| `preview` | Snapshot validation | `eclipse-temurin:25-jre` | 25 | `velocity-4.1.0-SNAPSHOT-16.jar` | `https://fill-data.papermc.io/v1/objects/aebade8be3b15d7c3c61514a50ce857cbf78ee87bd32e8d16d2352c6ca3e472f/velocity-4.1.0-SNAPSHOT-16.jar` | `aebade8be3b15d7c3c61514a50ce857cbf78ee87bd32e8d16d2352c6ca3e472f` |

Each row records the complete pinned runtime: the compatible proxy JVM, the exact Velocity build
downloaded from PaperMC's official Fill downloads service, and its content-addressed object URL and
SHA-256 digest. The `stable` line is the recommended production runtime; the `preview` line
validates the next 4.x snapshot ahead of release.

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
.\mvnw.cmd verify -Psystem-tests -Dvelocity.system.profiles=stable
.\smoke\run-smoke.ps1 -Profile preview
```

```sh
./mvnw verify -Psystem-tests
./mvnw verify -Psystem-tests -Dvelocity.system.profiles=stable
./mvnw verify -Psystem-tests -Dvelocity.system.profiles=preview
```

Startup and shutdown are bounded. Runtime logs are retained under
`target/velocity-system-tests/logs/`; disposable fixture directories and test
containers are removed even when an assertion fails.

These tests deliberately do not mount the Docker socket, start Minecraft, or
exercise a real managed container. The live gate is the reusable
`release-candidate-e2e.yml` workflow and Maven `release-candidate-e2e` profile; the protected
release workflow must pass that gate against the exact JAR it publishes on the pinned stable
Velocity 4.0.0 line.
