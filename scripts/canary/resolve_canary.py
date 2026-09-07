#!/usr/bin/env python3
"""Resolve the latest Velocity builds from PaperMC Fill into a manifest.

Queries the official Fill v3 REST API (never the downloads webpage) with
an identifiable User-Agent, picks the newest stable and preview builds,
and writes the validated runtime manifest consumed by
VelocitySystemHarness in manifest mode.

Unknown version families stop with --needs-review output instead of
guessing a JVM, image, or support status. Upstream outages exit non-zero
so the workflow reports them distinctly from test failures.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from canary_lib import (  # noqa: E402
    FILL_API,
    USER_AGENT,
    CanaryError,
    UnknownFamilyError,
    build_manifest,
    extract_versions,
    pick_track_versions,
    select_build,
    validate_manifest,
)


def fetch_json(url: str, timeout: int = 30) -> object:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=timeout) as response:  # noqa: S310
        if response.status != 200:
            raise CanaryError(f"Fill Raw error {response.status} for {url}")
        return json.loads(response.read().decode("utf-8"))


def resolve(project: str, run_id: str) -> dict:
    versions = extract_versions(fetch_json(f"{FILL_API}/v3/projects/{project}"))
    stable_version, preview_version = pick_track_versions(versions)
    stable = select_build(
        fetch_json(f"{FILL_API}/v3/projects/{project}/versions/{stable_version}/builds"),
        stable_version,
        preview=False,
    )
    preview = select_build(
        fetch_json(
            f"{FILL_API}/v3/projects/{project}/versions/{preview_version}/builds"
        ),
        preview_version,
        preview=True,
    )
    manifest = build_manifest(stable, preview, run_id)
    validate_manifest(manifest)
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", default="velocity")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--run-id", default="local")
    arguments = parser.parse_args()
    try:
        manifest = resolve(arguments.project, arguments.run_id)
    except UnknownFamilyError as error:
        print(f"canary needs review: {error}")
        print(f"needs_review=true\nreview_reason={error}\n")
        return 0
    except CanaryError as error:
        print(f"canary error: {error}", file=sys.stderr)
        return 1
    arguments.output.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    stable, preview = manifest["runtimes"]
    print(
        f"Resolved stable Velocity {stable['version']} build {stable['build']} "
        f"and preview {preview['version']} build {preview['build']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
