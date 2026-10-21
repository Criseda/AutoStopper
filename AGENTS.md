# Repository Guidelines

## Project Direction & Architecture

AutoStopper is a production-ready, readiness-aware Docker lifecycle management plugin for Velocity networks. Master is the sole authoritative development and release branch. Preserve Velocity 3/4 compatibility, explicit mappings, off-thread bounded I/O, atomic configuration, typed outcomes, the shared lifecycle coordinator, real readiness, retry semantics, bounded shutdown, and structured telemetry diagnostics.

## Project Structure & Module Organization

Production code is under `src/main/java/me/criseda/autostopper` in responsibility-specific packages. Plugin metadata is in `src/main/resources/velocity-plugin.json`. Tests mirror production packages under `src/test/java`; shared helpers belong in `testing/`. `examples/` holds pinned Compose deployments, while `smoke/` loads the packaged JAR on all pinned Velocity lines. `target/`, IDE files, runtime caches, and `dependency-reduced-pom.xml` are generated.

## Build, Test, and Development Commands

- `.\mvnw.cmd verify` (Windows) or `./mvnw verify` (POSIX) is the canonical local verification entry point.
- `.\mvnw.cmd package` creates the shaded plugin JAR under `target/`.
- `.\smoke\run-smoke.ps1` (Windows) or `./smoke/run-smoke.sh` (POSIX) tests all pinned Velocity profiles; select one with `-Profile legacy` / `./smoke/run-smoke.sh legacy`, or `stable-4.0`, `stable-4.1`, `stable`, `preview`.

Use JDK 21+.

## Coding Style & Safety Invariants

Use four spaces, UTF-8, lowercase packages, `PascalCase` types, `camelCase` members, and `UPPER_SNAKE_CASE` constants. Match nearby code; no formatter is configured. Never block Velocity event or command workers. Preserve bounded deadlines, cancellation, legal lifecycle transitions, mapping isolation, and exactly-once future completion. Raw Docker stderr belongs only in operator logs.

## Testing Guidelines

Use JUnit 5 and Mockito; name classes `<ProductionClass>Test`. Cover production and failure behavior at the owning layer. Add deterministic tests for concurrency, timeouts, cancellation, reload, and shutdown races. Validate the shaded JAR for packaging changes. Smoke tests prove loadability, not live Docker behavior; the release-candidate E2E workflow owns the exact-candidate Docker/Compose/Minecraft gate.

## Commits & Pull Requests

Use short imperative subjects. Name branches by intent, using prefixes such as `feat/`, `fix/`, `docs/`, `refactor/`, `test/`, or `chore/`; include the primary issue and a concise topic when useful, for example `chore/release-2.1.0`. Keep one primary roadmap issue per branch and draft PR, targeting `master`. Link and close exactly one primary issue where practical. PRs must document behavior, risks, public/configuration impact, and every local verification command run. Update README, examples, migration notes, smoke pins, or `CHANGELOG.md` when their contracts change, and complete the PR template checklist. Every user-visible change (behavior, command or chat output, configuration, permissions, supported runtimes, documented contracts) adds an entry under `## [Unreleased]` in the matching Keep a Changelog subsection (`Added`, `Changed`, `Fixed`, `Removed`), reusing an existing heading and ending with the issue number, for example `(#124)`; internal refactors, test-only changes, CI, and Dependabot bumps need none. Only release-prep PRs (`chore/<issue>-prepare-<version>`, see `docs/releasing.md`) turn `[Unreleased]` into a dated `## [X.Y.Z] - YYYY-MM-DD` section with a comparison link; release tooling requires exactly one dated section per version.

## Security

Docker socket/group access is host-root-equivalent. Never commit secrets or real infrastructure details, and retain least-privilege, explicit mappings, bounded subprocesses, and sanitized player-facing errors.
