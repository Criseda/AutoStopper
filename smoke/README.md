# Velocity packaged-JAR system tests

The `system-tests` Maven profile runs the final shaded AutoStopper JAR on all
supported Velocity lines. It uses disposable Docker containers so the host JDK
only needs to satisfy the normal build requirement.

| Profile | Role | Runtime image | Proxy JVM | Velocity artifact | Official download URL | SHA-256 |
|---|---|---|---|---|---|---|
| `legacy` | Minimum/floor | `eclipse-temurin:21-jre` | 21 | `velocity-3.5.1-615.jar` | `https://fill-data.papermc.io/v1/objects/b4e3164df5377346854dc6cb9e6a78022b1946ff69e89676313f5f6f1c6f0fb3/velocity-3.5.1-615.jar` | `b4e3164df5377346854dc6cb9e6a78022b1946ff69e89676313f5f6f1c6f0fb3` |
| `stable-4.0` | Production | `eclipse-temurin:25-jre` | 25 | `velocity-4.0.0-6.jar` | `https://fill-data.papermc.io/v1/objects/4540289f48c83e305fc2f2c495a84d1f4d0b7f360830251e169dd5a208740e70/velocity-4.0.0-6.jar` | `4540289f48c83e305fc2f2c495a84d1f4d0b7f360830251e169dd5a208740e70` |
| `stable-4.1` | Production | `eclipse-temurin:25-jre` | 25 | `velocity-4.1.0-22.jar` | `https://fill-data.papermc.io/v1/objects/bff15036c6a43f0db66600ee405407aa71e95fbf05d692e35e8ab364c7c9083d/velocity-4.1.0-22.jar` | `bff15036c6a43f0db66600ee405407aa71e95fbf05d692e35e8ab364c7c9083d` |
| `stable` | Production (recommended, 4.2) | `eclipse-temurin:25-jre` | 25 | `velocity-4.2.0-30.jar` | `https://fill-data.papermc.io/v1/objects/35a5596a5468a035d8a32c8de5ebb0dc6b8d8f0cc3ff5169d514aca762af8aa8/velocity-4.2.0-30.jar` | `35a5596a5468a035d8a32c8de5ebb0dc6b8d8f0cc3ff5169d514aca762af8aa8` |
| `preview` | Snapshot validation | `eclipse-temurin:25-jre` | 25 | `velocity-4.2.1-SNAPSHOT-41.jar` | `https://fill-data.papermc.io/v1/objects/aab3cc7e60ee758ea8cfff3cb0ab88e92aececfd0025061c90f00d6aa9f1aa1e/velocity-4.2.1-SNAPSHOT-41.jar` | `aab3cc7e60ee758ea8cfff3cb0ab88e92aececfd0025061c90f00d6aa9f1aa1e` |

Each row records the complete pinned runtime: the compatible proxy JVM, the exact Velocity build
downloaded from PaperMC's official Fill downloads service, and its content-addressed object URL and
SHA-256 digest. The `stable` line (4.2) is the recommended production runtime, alongside the
`stable-4.0` and `stable-4.1` lines; the `preview` line validates the next 4.x
snapshot ahead of release. Pull requests run `legacy`, `stable`, and `preview`; the release
workflow also runs `stable-4.0` and `stable-4.1`.

The harness downloads each content-addressed Velocity JAR, verifies its hash,
and copies the exact versioned `target/AutoStopper-<project-version>.jar` produced by the current
Maven invocation into an isolated runtime. Each profile must prove:

- Velocity discovers the plugin metadata and constructs the injected plugin;
- a valid configuration enables AutoStopper;
- an invalid configuration produces a specific, actionable startup failure;
- Velocity and AutoStopper complete a bounded graceful shutdown; and
- the plugin inside the container has the same SHA-256 as the packaged artifact.

Each profile starts Velocity with `smoke/<profile>/velocity.toml`. Keep each fixture's
`config-version` at a value that Velocity itself has shipped, so newer Velocity builds (including
the compatibility canary's latest stable and preview) apply their own config migrations instead of
reading an old key layout as current.

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
./smoke/run-smoke.sh preview
```

`run-smoke.ps1` and `run-smoke.sh` are equivalent: both take an optional profile (`legacy`,
`stable-4.0`, `stable-4.1`, `stable`, `preview`, or `all`, the default) and run the Maven command
above from the repository root.

Startup and shutdown are bounded. Runtime logs are retained under
`target/velocity-system-tests/logs/`; disposable fixture directories and test
containers are removed even when an assertion fails.

The weekly [Velocity compatibility canary](../.github/workflows/velocity-compatibility-canary.yml)
reuses this harness in manifest mode (`-Dvelocity.system.manifest=<manifest>`,
optionally `-Dvelocity.system.plugin=<jar>`) to run the same scenarios against
newly resolved upstream builds without compiling new profiles into Java source.
Manifest entries are validated (Fill `https://fill-data.papermc.io` URL,
64-hex SHA-256, allowlisted `eclipse-temurin` image) before any container starts.

These tests deliberately do not mount the Docker socket, start Minecraft, or
exercise a real managed container. The live gate is the reusable
`release-candidate-e2e.yml` workflow and Maven `release-candidate-e2e` profile; the protected
release workflow must pass that gate against the exact JAR it publishes on the pinned stable
Velocity 4.2.0 line.
