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
[2.3.0](https://github.com/Criseda/AutoStopper/releases/tag/2.3.0) at the time of writing; older
releases, including 1.1.2, 2.0.0, 2.1.0, 2.1.1, and 2.2.0, are unsupported and receive no fixes. Users of 2.2.0 should read
the [2.2.0 to 2.3.0 migration guide](docs/migration-2.2.0-to-2.3.0.md) (the JAR is a drop-in
replacement); users of 1.1.2 must follow the [1.1.2 to 2.0.0 migration
guide](docs/migration-1.1.2-to-2.0.0.md) before replacing the artifact.

Install only from the project's GitHub or Modrinth release pages and verify the published SHA-256
checksum before use.

## Supported runtimes

AutoStopper ships one Java 21 bytecode JAR compiled against Velocity API 3.5.1 and tested on five
runtime lines (Stable 4.0 and 4.1 are verified at release, the rest on every pull request). Keep each proxy image and Velocity build together.

| Support line | Role | Proxy JVM | Velocity runtime |
|---|---:|---:|---|
| Legacy | Minimum supported | 21 | `3.5.1`, build `615` |
| Stable 4.0 | Production | 25 | `4.0.0`, build `6` |
| Stable 4.1 | Production | 25 | `4.1.0`, build `22` |
| Stable 4.2 | Production (recommended) | 25 | `4.2.0`, build `30` |
| Preview | Snapshot validation only | 25 | `4.2.1-SNAPSHOT`, build `41` |

Velocity 4.x requires at least Java 25, so the stable and preview lines cannot run on Java 21.
Velocity 4.2.1 is supplied as a snapshot and is validated only as preview, not as a production
baseline. Other Velocity, Java, proxy-image, and backend combinations are not part of the tested
matrix; issues on unsupported combinations may be closed without investigation.

AutoStopper is supported on the exact stable runtime lines listed above. Newer stable and preview
Velocity builds are tested automatically as compatibility canaries by the weekly
[Velocity compatibility canary workflow](https://github.com/Criseda/AutoStopper/actions/workflows/velocity-compatibility-canary.yml),
which exercises the latest public release JAR and a fresh `master` build. A green canary is
evidence of observed compatibility, not a blanket guarantee for future versions.
Preview/snapshot builds remain best-effort.

## What makes a report actionable

A good bug report answers: exact AutoStopper version and download source, exact Velocity version
and build, proxy image tag and JVM, host operating system, the relevant (redacted) configuration
and sanitized logs, and minimal reproduction steps. The bug report form prompts for these; see
also the [pull-request and issue expectations](CONTRIBUTING.md) for how issues are triaged.
