# Releasing AutoStopper

AutoStopper publishes one release artifact through the protected
[`Validated release`](../.github/workflows/release.yml) workflow. The workflow rebuilds the tagged
commit once, runs deterministic CI, dependency review, and the live Docker/Minecraft gate against
that JAR, then sends the preserved byte sequence to GitHub Releases and Modrinth. A release is not
complete until both public downloads have been re-read and verified against the candidate.

## Protected repository configuration

Keep these controls configured outside the repository:

- protect stable numeric tags matching `*.*.*` from update and deletion;
- enable immutable GitHub Releases;
- keep the `release` environment restricted to stable tags plus protected branch `master` for the
  reviewed recovery workflow, with required maintainer approval and administrator bypass disabled;
- allow self-approval only while AutoStopper has a sole maintainer; require independent approval
  when a second active maintainer is available;
- store `MODRINTH_TOKEN` only as an environment secret in `release`; and
- give the Modrinth token only `VERSION_CREATE` and `VERSION_WRITE` access to AutoStopper project
  `PG4gqnzX`. It does not need repository, organization, or account-wide administration access.

The workflow's generated GitHub token receives read-only permissions until the environment-approved
publication job, where normal publication grants only `actions: read` and `contents: write`;
recovery additionally grants `attestations: read`. Never print, copy into release notes, or commit
either token. Rotate the Modrinth token immediately if its value or a publication runner may have
been exposed.

## Normal release

1. Prepare a short-lived release pull request from current `master`. Coordinate the stable
   `X.Y.Z` value in `pom.xml`, the dated `CHANGELOG.md` section and comparison link, and the
   `minecraftVersionRange` endpoints in `release/release-metadata.json` when the supported
   Minecraft range changes. The source Velocity descriptor must remain `${project.version}`.
   Do not hand-edit the Modrinth game-version selector; the workflow derives it from the range.
2. Merge only after the protected `master` checks pass. Do not tag an unmerged branch commit.
3. From a clean, current `master`, create a numeric tag with no `v` prefix at the exact merged
   commit, then push only that tag:

   ```sh
   git switch master
   git pull --ff-only origin master
   git tag X.Y.Z
   git push origin X.Y.Z
   ```

4. In GitHub Actions, inspect the `Validated release` run and approve its `release` environment
   only after preflight, deterministic CI, dependency review, and release-candidate E2E succeed.
5. Confirm that GitHub and Modrinth both show the release and that their JAR SHA-256 values equal
   the checksum attached to the GitHub release. Verify the GitHub build attestation as well:

   ```sh
   sha256sum -c AutoStopper-X.Y.Z.jar.sha256
   gh attestation verify AutoStopper-X.Y.Z.jar --repo Criseda/AutoStopper
   ```

The tag, POM, packaged Velocity descriptor, manifest, changelog, candidate evidence, filename, and
public metadata must all agree. The release workflow rejects prerelease tags and tags that are not
contained in protected `master`.

## What the workflow preserves

The release-candidate job uploads a run-attempt-specific bundle containing the exact tested JAR,
SHA-256 and SHA-512 files, curated destination notes, and a release manifest. GitHub attests the JAR
before publication. On a rerun, publication accepts multiple bundles only when every file is
byte-identical, then selects the newest attempt. Any disagreement blocks publication.

Publication is deliberately non-replacing:

- a new GitHub release is staged as a draft, receives only missing byte-identical assets, and is
  made public after verification;
- a new Modrinth version is staged as unlisted, its metadata and downloaded bytes are verified, and
  it is then listed;
- existing releases are a no-op only when their metadata and downloaded bytes match exactly; and
- the publisher contains no delete, retag, asset-replace, or Modrinth-file-replace operation.

Modrinth is listed immediately before the GitHub draft is made public. The final step re-fetches
both destinations and verifies the public state.

## Release notes

Both public note texts are derived from exactly one dated `## [X.Y.Z]` section in `CHANGELOG.md`:

1. The file must contain exactly one `## [X.Y.Z]` heading for the tagged version, and the section
   must not be empty. Its comparison link
   `[X.Y.Z]: https://github.com/Criseda/AutoStopper/compare/PREVIOUS...X.Y.Z` must appear exactly
   once.
2. The note text is the section content up to the next level-two heading. Relative documentation
   links of the form `](docs/...` are rewritten to
   `](https://github.com/Criseda/AutoStopper/blob/X.Y.Z/docs/...` so they resolve against the
   tagged commit.
3. That rewritten text is stored as `modrinth-changelog.md` and becomes the Modrinth changelog.
4. The GitHub release body is stored as `release-notes.md` and appends a `### Verification` block
   containing the candidate SHA-256, the build and release-candidate run URL, and the
   `gh attestation verify AutoStopper-X.Y.Z.jar --repo Criseda/AutoStopper` instruction.
5. Both generated note files travel inside the candidate bundle, and their digests are recorded in
   the release manifest. Publication refuses any bundle whose note files do not match those
   digests.

The Modrinth game-version list is likewise derived rather than hand-curated: `prepare-candidate`
expands the `minecraftVersionRange` endpoints from `release/release-metadata.json` against the
release catalogue in `scripts/release/release_lib.py` into every release in between (currently
Java Edition 1.7.2 through 26.2), and records the exact expanded list in the candidate manifest.
Publication rejects a range whose endpoint is unknown or reversed, so a release cannot silently
publish an empty, partial, or mistyped selector.

## Hotfix

Create a short-lived `fix/<issue>-<topic>` branch from current protected `master`. Include the patch
version bump, dated changelog entry, comparison link, fix, and tests in the same pull request. After
merge, follow the normal release process and tag the merged commit with the next patch version.
There is no long-lived hotfix or release branch and nothing to back-merge.

## Reruns and partial publication

Rerun the failed job first. If that run's tagged workflow code cannot complete recovery, merge the
fix through protected `master` without moving the release tag. Add protected branch `master` to the
`release` environment's deployment branches, then manually run `Validated release recovery` from
`master` with the immutable tag and failed source-run ID. The recovery workflow requires every
source candidate gate to have passed, reuses only that run's preserved artifact, verifies its
manifest, hashes, and build attestation, and enters the same approval-gated publisher.

If a whole tagged workflow rerun is required, the run-attempt artifact name avoids overwriting prior
evidence; all available attempts must still be byte-identical. Safe states resume automatically:

| Observed state | Recovery |
|---|---|
| Neither destination exists | Rerun publication; both are staged, verified, and promoted. |
| Matching GitHub draft exists | Missing matching assets are uploaded; the draft is verified and resumed. |
| Matching unlisted Modrinth version exists | It is verified and promoted without a replacement upload. |
| One matching public destination exists | It is verified without mutation and the other destination is completed. |
| Existing metadata, hash, or downloaded bytes differ | Stop. Preserve logs, treat it as a release incident, and use a new corrected version after investigation. |

Never recover by moving, deleting, or recreating a stable tag, overwriting an asset, or weakening
the environment approval. Do not manually publish a separately rebuilt JAR. Automated recovery is
intentionally unable to erase conflicting state.

## Rollback

A published stable release is an immutable record. Rollback means restoring the last known-good
JAR and its matching configuration in deployments, not rewriting the bad release. Keep the bad
tag and GitHub assets intact, record the operational advisory, and publish the fix as a new patch
version through the complete workflow. On Modrinth, a maintainer may withdraw the affected version
from discovery if needed, but must never replace its file under the same version number.

For a 2.0.0-to-1.1.2 operational rollback, restore the saved 1.1.2 JAR and configuration together;
the formats are not interchangeable. Follow the rollback section of the
[migration guide](migration-1.1.2-to-2.0.0.md).
