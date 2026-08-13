# Contributing to AutoStopper

AutoStopper uses one authoritative branch: `master`. It is the default development, integration,
and release branch. Long-lived `dev`, `develop`, `release`, and `hotfix` branches are retired and
must not be recreated. Protected publication and recovery are documented in
[`docs/releasing.md`](docs/releasing.md).

## Branch workflow

Create every change from the current `master` on a short-lived branch. Use a prefix that describes
the work:

- `feat/` for product behavior;
- `fix/` for defects and hotfixes;
- `docs/` for documentation;
- `refactor/` for behavior-preserving code changes;
- `test/` for test and verification work;
- `chore/` for build, dependency, release-preparation, and repository maintenance;
- `agent/` for short-lived changes prepared through an automated coding-agent workflow.

Include the primary issue and a concise topic when useful, for example
`chore/17-protected-branch-strategy`. Do not reuse a merged branch for later work.

All changes, including maintainer changes and hotfixes, reach `master` through a pull request.
Direct pushes, force pushes, and deletion of `master` are prohibited. The protected-branch ruleset
has no routine bypass. Any emergency ruleset change must be recorded in an issue, limited to the
minimum necessary action, and restored before ordinary development resumes.

Keep one primary roadmap issue per branch and pull request where practical. Pull requests should
describe behavior, risks, public or configuration impact, and every verification command run.

## Required pull-request checks

The following GitHub Actions checks are required before a pull request can merge:

- `Java 21 / verify`
- `Java 25 / verify`
- `Velocity legacy / packaged runtime`
- `Velocity stable / packaged runtime`
- `Velocity preview / packaged runtime`
- `Dependency vulnerability review`

The branch must be up to date with `master`, every required check must succeed, and every review
conversation must be resolved. The release-candidate E2E workflow is a separate protected release
gate; it is not an ordinary pull-request check.

These check names are part of the repository governance contract. Renaming a workflow job requires
a coordinated update to the protected-branch ruleset so that protection never silently disappears
or permanently blocks merges.

AutoStopper currently has one maintainer, so GitHub requires zero approving reviews while still
requiring a pull request, successful checks, and resolved conversations. The maintainer reviews
external contributions before merging. When a second active maintainer is available, update the
ruleset to require one approval, dismiss stale approvals after new commits, and require approval of
the most recent reviewable push by someone other than its author.

## Merge methods and branch cleanup

GitHub merge commits, squash merges, and rebase merges are all available. Choose the method that
best represents the reviewed change:

- prefer a squash merge for an ordinary focused pull request whose intermediate branch commits do
  not add lasting value;
- use a rebase merge when a small, clean series of independently meaningful commits should remain
  visible in a linear `master` history;
- use a merge commit when preserving the branch topology or a multi-commit integration as one
  explicit pull-request event is valuable.

The person merging is responsible for selecting deliberately and leaving a useful final commit or
merge message. Supporting rebase merges does not permit force-pushing or otherwise rewriting
published `master` history.

GitHub automatically deletes a pull request's head branch after merge. Delete abandoned remote
branches when their pull requests close after preserving any work that is still required. Prune
deleted remote-tracking branches locally with:

```sh
git fetch --prune
```

Tags are release records, not stale branches, and are not removed by branch cleanup.

## Releases, version ownership, and tags

The release maintainer owns each coordinated version change. Prepare it in a short-lived
`chore/<issue>-prepare-<version>` branch and update the POM, Velocity plugin descriptor, changelog,
and other public version metadata together in one pull request.

After that pull request merges, create the stable release tag at the exact resulting protected
`master` commit. The protected tag workflow reruns the required checks, builds one candidate, runs
the release-candidate gate against that exact JAR, and preserves it for publication. The tag must
not point to an earlier branch tip or a separately rebuilt candidate. Follow the repository's
existing tag style, such as `2.0.0` rather than `v2.0.0`.

Tag protection, artifact publication, release secrets, GitHub Releases, Modrinth, checksums, and
publication recovery are defined in [`docs/releasing.md`](docs/releasing.md).

## Hotfix flow

Start a hotfix from the current protected `master` on a short-lived `fix/<issue>-<topic>` branch.
Use the normal pull-request, review-conversation, and required-check policy. Include the patch
version and changelog update in the hotfix pull request when it will produce a release.

After merge, tag the resulting `master` commit and let the protected release workflow validate and
publish its exact candidate. There is no `dev` branch to back-merge into; subsequent work starts
from the updated `master`.

## Historical `dev`/`master` reconciliation

The former branches were reconciled without rewriting history by pull request #2 and merge commit
[`3168614a00bd02c2b8f4befbde044133ac7fbbd2`](https://github.com/Criseda/AutoStopper/commit/3168614a00bd02c2b8f4befbde044133ac7fbbd2).
The pre-merge `master` tip
[`9e089293e17e0bd65571124f82750ab0a670cb3e`](https://github.com/Criseda/AutoStopper/commit/9e089293e17e0bd65571124f82750ab0a670cb3e)
was also the merge base, so `master` had no commits unique from the merged `dev` history.

Every commit unique to `dev` received an explicit **keep** decision and remains reachable through
the merge:

| Commit | Decision | Subject |
|---|---|---|
| [`3f43d0d310b87531a91d713daae925bdb29e114c`](https://github.com/Criseda/AutoStopper/commit/3f43d0d310b87531a91d713daae925bdb29e114c) | Keep | Bump version to 1.1.2 and enhance server start handling |
| [`7e4f15b3e827f05b636629a42fd2423cd60926f3`](https://github.com/Criseda/AutoStopper/commit/7e4f15b3e827f05b636629a42fd2423cd60926f3) | Keep | Change version to 1.1.2-rc1 and enhance command version handling #1 |
| [`6f9267228a08d705cb3ce1e5827b685f4b957b60`](https://github.com/Criseda/AutoStopper/commit/6f9267228a08d705cb3ce1e5827b685f4b957b60) | Keep | Fix format #1 |
| [`9cb52ba98b4c388364e92b6e99aab1a7ff14b009`](https://github.com/Criseda/AutoStopper/commit/9cb52ba98b4c388364e92b6e99aab1a7ff14b009) | Keep | Enhance ActivityTracker to check server status during inactivity evaluation and update unit tests |
| [`d7f586ed43aaad85f2df1a7b7a9514a4ce870c47`](https://github.com/Criseda/AutoStopper/commit/d7f586ed43aaad85f2df1a7b7a9514a4ce870c47) | Keep | Update README.md to enhance license and credits sections with links |
| [`45d4ff2e059ca9f58c2f4bd61cba694b0b1a2e49`](https://github.com/Criseda/AutoStopper/commit/45d4ff2e059ca9f58c2f4bd61cba694b0b1a2e49) | Keep | Remove need for root, adding user group instead for docker management |
| [`ee936e57a494517d77eb87f9128041574464d4f9`](https://github.com/Criseda/AutoStopper/commit/ee936e57a494517d77eb87f9128041574464d4f9) | Keep | Bump version from 1.1.2-rc1 to 1.1.2 in POM and related tests |

No unique commit was dropped, cherry-picked, or otherwise reconciled outside that merge. The
lightweight `1.1.2` tag still points to the merge commit, and `1.0`, `1.0.1`, `1.1.1`, and
`1.1.2-rc1` remain reachable from `master`. The old `dev` branch has been deleted locally and from
the origin; its required history remains preserved by `master` and the release tags.
