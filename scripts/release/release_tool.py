#!/usr/bin/env python3
"""CLI entrypoint for AutoStopper's validated release pipeline."""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

from release_lib import (
    HttpClient,
    ReleaseError,
    ReleasePublisher,
    prepare_candidate,
    previous_stable_tag,
    select_candidate,
    validate_source,
    verify_candidate,
)


def require_secret(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        raise ReleaseError(f"Required environment secret {name} is not set")
    return value


def tags(repository: Path) -> list[str]:
    result = subprocess.run(
        ["git", "tag", "--list"],
        cwd=repository,
        check=True,
        text=True,
        capture_output=True,
    )
    return result.stdout.splitlines()


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    subcommands = result.add_subparsers(dest="command", required=True)

    previous = subcommands.add_parser("previous-tag")
    previous.add_argument("--repository", type=Path, default=Path.cwd())
    previous.add_argument("--version", required=True)

    source = subcommands.add_parser("validate-source")
    source.add_argument("--repository", type=Path, default=Path.cwd())
    source.add_argument("--version", required=True)
    source.add_argument("--previous-tag", required=True)

    prepare = subcommands.add_parser("prepare-candidate")
    prepare.add_argument("--repository", type=Path, default=Path.cwd())
    prepare.add_argument("--jar", type=Path, required=True)
    prepare.add_argument("--evidence-manifest", type=Path, required=True)
    prepare.add_argument("--output", type=Path, required=True)
    prepare.add_argument("--version", required=True)
    prepare.add_argument("--previous-tag", required=True)
    prepare.add_argument("--commit", required=True)
    prepare.add_argument("--repository-name", required=True)
    prepare.add_argument("--run-url", required=True)

    verify = subcommands.add_parser("verify-candidate")
    verify.add_argument("--candidate", type=Path, required=True)
    verify.add_argument("--version", required=True)
    verify.add_argument("--commit", required=True)
    verify.add_argument("--repository-name", required=True)

    select = subcommands.add_parser("select-candidate")
    select.add_argument("--downloads", type=Path, required=True)
    select.add_argument("--output", type=Path, required=True)
    select.add_argument("--artifact-prefix", required=True)
    select.add_argument("--version", required=True)
    select.add_argument("--commit", required=True)
    select.add_argument("--repository-name", required=True)

    publish = subcommands.add_parser("publish")
    publish.add_argument("--candidate", type=Path, required=True)
    publish.add_argument("--version", required=True)
    publish.add_argument("--commit", required=True)
    publish.add_argument("--repository-name", required=True)
    return result


def main() -> int:
    arguments = parser().parse_args()
    if arguments.command == "previous-tag":
        print(previous_stable_tag(tags(arguments.repository), arguments.version))
    elif arguments.command == "validate-source":
        validate_source(arguments.repository, arguments.version, arguments.previous_tag)
        print(f"Release source metadata agrees on {arguments.version}")
    elif arguments.command == "prepare-candidate":
        manifest = prepare_candidate(
            arguments.repository,
            arguments.jar,
            arguments.evidence_manifest,
            arguments.output,
            arguments.version,
            arguments.previous_tag,
            arguments.commit,
            arguments.repository_name,
            arguments.run_url,
        )
        print(
            f"Prepared {manifest['artifact']['name']} with SHA-256 {manifest['artifact']['sha256']}"
        )
    elif arguments.command == "verify-candidate":
        manifest = verify_candidate(
            arguments.candidate,
            arguments.version,
            arguments.commit,
            arguments.repository_name,
        )
        print(f"Verified release candidate SHA-256 {manifest['artifact']['sha256']}")
    elif arguments.command == "select-candidate":
        manifest = select_candidate(
            arguments.downloads,
            arguments.output,
            arguments.artifact_prefix,
            arguments.version,
            arguments.commit,
            arguments.repository_name,
        )
        print(f"Selected release candidate SHA-256 {manifest['artifact']['sha256']}")
    elif arguments.command == "publish":
        manifest = verify_candidate(
            arguments.candidate,
            arguments.version,
            arguments.commit,
            arguments.repository_name,
        )
        publisher = ReleasePublisher(
            HttpClient(),
            arguments.candidate,
            manifest,
            require_secret("GITHUB_TOKEN"),
            require_secret("MODRINTH_TOKEN"),
        )
        publisher.publish()
        print(f"Published byte-identical AutoStopper {arguments.version} releases")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ReleaseError, OSError, subprocess.SubprocessError) as error:
        print(f"release error: {error}", file=sys.stderr)
        raise SystemExit(1)
