# Support

## Where to ask for help

- **Usage and setup questions** belong in the
  [GitHub issue tracker](https://github.com/Criseda/AutoStopper/issues) with the `question` label,
  or on the [Modrinth plugin page](https://modrinth.com/plugin/autostopper) discussion area if
  available.
- **Defects** should be filed as [bug reports](https://github.com/Criseda/AutoStopper/issues/new?template=bug_report.yml)
  with the runtime details the form asks for. Read the
  [troubleshooting guide](docs/troubleshooting.md) and search for existing issues first.
- **Security vulnerabilities** must not be filed publicly. See
  [SECURITY.md](SECURITY.md) for private disclosure.
- Read the [configuration reference](docs/configuration.md) and the
  [Docker socket security guidance](docs/security.md) before reporting an installation problem.

Support is provided on a best-effort basis by the maintainer. There is no guaranteed response
time, and there is no separate paid or prioritized channel.

## Supported versions

Only the current release is supported. That release is
[2.1.0](https://github.com/Criseda/AutoStopper/releases/tag/2.1.0) at the time of writing; older
releases, including 1.1.2 and 2.0.0, are unsupported and receive no fixes. Users of older versions must follow the
[migration guide](docs/migration-1.1.2-to-2.0.0.md) before replacing the artifact.

Install only from the project's GitHub or Modrinth release pages and verify the published SHA-256
checksum before use.

## Supported runtimes

AutoStopper ships one Java 21 bytecode JAR compiled against Velocity API 3.5.1 and tested on three
runtime lines. Keep each proxy image and Velocity build together.

| Support line | Role | Proxy JVM | Velocity runtime |
|---|---:|---:|---|
| Legacy | Minimum supported | 21 | `3.5.1`, build `615` |
| Stable | Production (recommended) | 25 | `4.0.0`, build `6` |
| Preview | Snapshot validation only | 25 | `4.1.0-SNAPSHOT`, build `16` |

Velocity 4.x requires at least Java 25, so the stable and preview lines cannot run on Java 21.
Velocity 4.1 is supplied as a snapshot and is validated only as preview, not as a production
baseline. Other Velocity, Java, proxy-image, and backend combinations are not part of the tested
matrix; issues on unsupported combinations may be closed without investigation.

## What makes a report actionable

A good bug report answers: exact AutoStopper version and download source, exact Velocity version
and build, proxy image tag and JVM, host operating system, the relevant (redacted) configuration
and sanitized logs, and minimal reproduction steps. The bug report form prompts for these; see
also the [pull-request and issue expectations](CONTRIBUTING.md) for how issues are triaged.
