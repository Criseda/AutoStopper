# Security policy

## Reporting a vulnerability

Use GitHub's private vulnerability reporting so the report stays confidential while it is
assessed. On the repository's [Security tab](https://github.com/Criseda/AutoStopper/security),
select **Report a vulnerability**, fill in the affected version and impact, and submit. If private
vulnerability reporting is unavailable, email the maintainer at
[laurentiu.cristian.preda@gmail.com](mailto:laurentiu.cristian.preda@gmail.com) with
`AutoStopper security` in the subject line.

Do **not** open a public issue, pull request, or discussion for a suspected vulnerability, and do
not post proof-of-concept material publicly.

Include in the report:

- the exact AutoStopper release and download source;
- the Velocity runtime, proxy image, Java, and operating system involved;
- a minimal, redacted reproduction including the affected configuration;
- the observed and expected behavior; and
- an assessment of impact, including anything that could indicate a compromise of the Docker
  host.

## Response expectations

Reports are handled by a single maintainer on a best-effort basis. There is no guaranteed
acknowledgment or fix timeline. Do not share the report or any draft fix with others before the
maintainer agrees to disclosure; coordinated disclosure is preferred and will be arranged
individually for accepted reports.

## Scope

AutoStopper controls a Docker daemon through the local socket. As documented in
[`docs/security.md`](docs/security.md), that access is host-root-equivalent: a compromise of the
proxy process or a plugin running alongside AutoStopper is a compromise of the Docker host. Reports
that rely on already-root access, or that require an attacker to have configuration or reload
permission, may be considered by design rather than treated as AutoStopper vulnerabilities.

## Supported versions

Only the current release receives security fixes. That release is
[2.1.0](https://github.com/Criseda/AutoStopper/releases/tag/2.1.0) at the time of writing; older
releases, including 1.1.2 and 2.0.0, are unsupported. Fixes are released as a new current release; there is
no separate security-patch line for older versions. Follow the [migration
guide](docs/migration-1.1.2-to-2.0.0.md) and install only from GitHub or Modrinth releases,
verifying the published SHA-256 checksum.

## Incident response for operators

If an untrusted plugin or proxy container may have used the Docker socket, follow the incident
response steps in [`docs/security.md`](docs/security.md): isolate the host, preserve logs, rotate
credentials, rebuild from trusted sources, and audit before restoring service.
