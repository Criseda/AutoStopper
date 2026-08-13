# Repository Guidelines

## Project Direction & Architecture

AutoStopper is moving from the 1.1.2 prototype to a supportable 2.0.0. GitHub issue #18 is the authoritative implementation order and release checklist. Phases 0-2 are complete; preserve Velocity 3/4 compatibility, explicit mappings, off-thread bounded I/O, atomic configuration, typed outcomes, the shared lifecycle, real readiness, retry semantics, bounded shutdown, and diagnostics. Do not call the project production-ready until all Phase 3 and 4 gates pass.

## Project Structure & Module Organization

Production code is under `src/main/java/me/criseda/autostopper` in responsibility-specific packages. Plugin metadata is in `src/main/resources/velocity-plugin.json`. Tests mirror production packages under `src/test/java`; shared helpers belong in `testing/`. `examples/` holds pinned Compose deployments, while `smoke/` loads the packaged JAR on all pinned Velocity lines. `target/`, IDE files, runtime caches, and `dependency-reduced-pom.xml` are generated.

## Build, Test, and Development Commands

- `mvn clean verify` is the current complete local verification command.
- `mvn clean package` creates the shaded plugin JAR under `target/`.
- `.\smoke\run-smoke.ps1` tests all pinned Velocity profiles; use `-Profile legacy`, `stable`, or `preview` to select one.

Use JDK 21+. After issue #15 lands, use the pinned Wrapper (`.\mvnw.cmd verify` or `./mvnw verify`) as the canonical entry point.

## Coding Style & Safety Invariants

Use four spaces, UTF-8, lowercase packages, `PascalCase` types, `camelCase` members, and `UPPER_SNAKE_CASE` constants. Match nearby code; no formatter is configured. Never block Velocity event or command workers. Preserve bounded deadlines, cancellation, legal lifecycle transitions, mapping isolation, and exactly-once future completion. Raw Docker stderr belongs only in operator logs.

## Testing Guidelines

Use JUnit 5 and Mockito; name classes `<ProductionClass>Test`. Cover production and failure behavior at the owning layer. Add deterministic tests for concurrency, timeouts, cancellation, reload, and shutdown races. Validate the shaded JAR for packaging changes. Smoke tests prove loadability, not live Docker behavior; issue #25 owns the exact-candidate Docker/Compose/Minecraft gate. Phase 3 separately adds integration tests, SpotBugs, coverage gates, and CI.

## Commits & Pull Requests

Use short imperative subjects. Name branches by intent, using prefixes such as `feat/`, `fix/`, `docs/`, `refactor/`, `test/`, or `chore/`; include the primary issue and a concise topic when useful, for example `chore/15-reproducible-build`. Keep one primary roadmap issue per branch and draft PR, targeting `master` until issue #17 establishes the final branch strategy. Link and close exactly one primary issue where practical. PRs must document behavior, risks, public/configuration impact, and every local verification command run. Update README, examples, migration notes, or smoke pins when their contracts change.

## Security

Docker socket/group access is host-root-equivalent. Never commit secrets or real infrastructure details, and retain least-privilege, explicit mappings, bounded subprocesses, and sanitized player-facing errors.
