# Modrinth listing maintenance

[`modrinth.md`](modrinth.md) is the project description published on Modrinth. This file is for
maintainers: it explains how that page reaches Modrinth and which listing metadata is set by hand.

## How the page is published

The protected release workflow publishes the description automatically. After the GitHub release and
the Modrinth version are both verified live, `release_tool.py publish` replaces the project body with
the contents of `docs/modrinth.md` rendered for the tag. Re-running a release is safe: a body that
already matches is left alone, and an update is re-fetched and compared byte for byte.

Before publishing, the workflow rewrites repository-relative targets so a release page never points
at a file that later moves on `master`:

| Source in `docs/modrinth.md` | Published as |
|---|---|
| `![alt](docs/assets/x.png)` | `https://raw.githubusercontent.com/Criseda/AutoStopper/<tag>/docs/assets/x.png` |
| `[text](docs/x.md#anchor)` | `https://github.com/Criseda/AutoStopper/blob/<tag>/docs/x.md#anchor` |

`validate-source` runs in release preflight and in CI and fails the release when the page:

- is longer than Modrinth's 65,536 character limit;
- names a specific product version such as `AutoStopper 2.1.1`; the page must stay version independent;
- links to anything other than an `https://` URL, an existing `docs/` file, or (for images) an
  existing `docs/assets/` file;
- drops the Docker socket security warning; or
- omits the image or version of any tested runtime in `release/release-metadata.json`.

Edit `docs/modrinth.md` like any other documentation. No per-release edits are needed.

## Diagrams

The images under `docs/assets/modrinth/` are rendered from the SVG and HTML sources in
`docs/assets/modrinth/src/`. Modrinth serves PNG reliably, so commit both. After editing a source,
re-render with Edge or Chrome installed:

```powershell
.\scripts\render-modrinth-assets.ps1
```

The in-game image mirrors the real message formats in `AutoStopperMessages`. Update
`src/in-game.html` when status or lifecycle wording changes.

## Project metadata set on Modrinth

These fields live on Modrinth, not in the release pipeline, and are changed by a maintainer.

| Field | Value |
|---|---|
| Project type | Plugin |
| Loader/platform | Velocity |
| Side | Server/proxy only |
| Summary | Empty servers sleep. Players wake them. AutoStopper starts mapped Docker backends on demand and stops them after inactivity. |
| License | MIT |
| Source link | `https://github.com/Criseda/AutoStopper` |
| Issues link | `https://github.com/Criseda/AutoStopper/issues` |
| Wiki link | `https://github.com/Criseda/AutoStopper/blob/master/README.md` |
| Discord link | the project's Discord invite |
| Donation links | PayPal, Buy Me a Coffee, GitHub Sponsors |

Modrinth only offers these fixed link types, so there are no fields for releases, the changelog,
installation, or migration guides. The project description ([`modrinth.md`](modrinth.md)) links
those instead, and the release workflow keeps it current.

Upload `docs/assets/autostopper-banner.png` (1672 by 941 pixels, under Modrinth's 5 MiB limit) as the
featured gallery image:

- title: `AutoStopper - Empty servers sleep. Players wake them.`
- description: `A stopped Docker backend wakes automatically when players connect through Velocity.`
- ordering: `0`

Keep Wiki and Discord links only while their maintainer-selected destinations remain supported.

## Version metadata

The release pipeline sets these on every version from the validated candidate; do not edit them by
hand.

| Field | Value |
|---|---|
| Version number | the release tag, for example `2.1.1` |
| Version title | `AutoStopper <version>` |
| Version type | Release |
| Loader | Velocity |
| Changelog | the dated section of `CHANGELOG.md` for that version |
| Game versions | expanded from `minecraftVersionRange` in `release/release-metadata.json` |
| Dependencies | none declared; Docker access is an operational prerequisite |
| File | the exact shaded JAR validated by CI and the release-candidate E2E gate |
| Integrity | the same SHA-256 as the GitHub release |

Modrinth's game-version tags do not express the proxy and JVM contract. The full range is offered
because AutoStopper uses Velocity API events and a generic Minecraft status request. Only the Purpur
backend in the release-candidate stack is exercised directly, and untested Velocity, Java, proxy
image or backend combinations are not described as supported merely because the selector is broad.
