"""Shared logic for the Velocity compatibility canary (issue #62).

The canary resolves the latest stable and preview Velocity builds from
PaperMC's Fill downloads service and records them in a validated runtime
manifest. The packaged-runtime harness then executes its usual
discovery/enablement/failure/shutdown scenarios against the manifest
instead of its compiled-in pins.

All functions here are pure (no network) so they stay unit-testable.
Networking lives in resolve_canary.py.
"""

from __future__ import annotations

import re
from datetime import datetime, timezone

FILL_API = "https://fill.papermc.io"
USER_AGENT = (
    "AutoStopper-compatibility-canary/2.1.0 "
    "(https://github.com/Criseda/AutoStopper)"
)
MANIFEST_SCHEMA = "autostopper-velocity-canary/v1"
DOWNLOAD_HOST_SUFFIX = "fill-data.papermc.io"
ALLOWED_IMAGE_PREFIX = "eclipse-temurin:"
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
VERSION_RE = re.compile(r"^(\d+)\.(\d+)\.(\d+)(?:-(.+))?$")

# Fill build channels that count as production-ready for the stable slot.
STABLE_CHANNELS = frozenset({"STABLE", "RECOMMENDED"})
# Channels that count as preview when picking the snapshot slot.
PREVIEW_CHANNELS = frozenset({"SNAPSHOT", "BETA", "ALPHA", "EXPERIMENTAL", "PREVIEW"})

# Version family -> (proxy JVM, runtime image). Anything else is unknown
# and must stop for explicit maintainer review (never inferred).
FAMILY_RUNTIMES = {
    3: (21, "eclipse-temurin:21-jre"),
    4: (25, "eclipse-temurin:25-jre"),
}

# Download keys tried in order; Fill embeds the URL so the exact key may
# vary between project types. Fail closed if none match.
DOWNLOAD_KEYS = ("proxy:default", "server:default", "default")


class CanaryError(Exception):
    """Base error for canary resolution and manifest validation."""


class UnknownFamilyError(CanaryError):
    """A resolved Velocity major version has no mapped runtime."""

    def __init__(self, version: str) -> None:
        super().__init__(
            f"Velocity {version} belongs to an unknown version family; "
            "explicit maintainer review is required"
        )
        self.version = version


def parse_version_tuple(version: str) -> tuple[int, int, int, int, str]:
    """Sort key for Velocity versions: numeric parts, releases before snapshots."""
    match = VERSION_RE.match(version.strip())
    if not match:
        raise CanaryError(f"Unparseable Velocity version: {version!r}")
    major, minor, patch, suffix = match.groups()
    # Releases sort after snapshots of the same triple so the newest
    # production line wins the stable slot.
    return (int(major), int(minor), int(patch), 0 if suffix else 1, suffix or "")


def extract_versions(project_payload: object) -> list[str]:
    """Pull a flat version list out of GET /v3/projects/{project}.

    Tolerant of the map-of-groups and flat-list shapes Fill has used;
    raises CanaryError when nothing usable is found (fail closed, never
    guess from the downloads page).
    """
    versions: list[str] = []

    def collect(value: object) -> None:
        if isinstance(value, str):
            versions.append(value)
        elif isinstance(value, dict):
            for key in ("key", "name", "version"):
                item = value.get(key)
                if isinstance(item, str):
                    versions.append(item)
                    break
        elif isinstance(value, (list, tuple)):
            for item in value:
                collect(item)

    if isinstance(project_payload, dict):
        payload = project_payload.get("versions", project_payload)
        if isinstance(payload, dict):
            for group in payload.values():
                collect(group)
        else:
            collect(payload)
    else:
        collect(project_payload)
    seen = list(dict.fromkeys(v.strip() for v in versions if v and v.strip()))
    if not seen:
        raise CanaryError("Fill project payload contains no versions")
    return seen


def pick_track_versions(versions: list[str]) -> tuple[str, str]:
    """Return (stable_version, preview_version) from a version list.

    Stable is the highest non-snapshot version; preview is the highest
    snapshot-style version, falling back to the overall newest when the
    project publishes no snapshots.
    """
    if not versions:
        raise CanaryError("No Velocity versions to choose from")
    ordered = sorted(versions, key=parse_version_tuple)
    releases = [v for v in ordered if "SNAPSHOT" not in v.upper()]
    snapshots = [v for v in ordered if "SNAPSHOT" in v.upper()]
    stable = releases[-1] if releases else ordered[-1]
    preview = snapshots[-1] if snapshots else ordered[-1]
    return stable, preview


def _build_number(build: dict) -> str:
    for key in ("number", "id", "build"):
        value = build.get(key)
        if value is not None:
            return str(value)
    raise CanaryError(f"Fill build entry has no number/id: {sorted(build)}")


def _build_download(build: dict) -> tuple[str, str, str]:
    downloads = build.get("downloads")
    if not isinstance(downloads, dict):
        raise CanaryError("Fill build entry has no downloads map")
    for key in DOWNLOAD_KEYS:
        entry = downloads.get(key)
        if not isinstance(entry, dict):
            continue
        url = entry.get("url")
        name = entry.get("name", "")
        checksums = entry.get("checksums", {})
        sha256 = checksums.get("sha256") if isinstance(checksums, dict) else None
        if isinstance(url, str) and isinstance(sha256, str) and url and sha256:
            return str(name), url, sha256
    raise CanaryError(
        f"Fill build entry has no usable download (tried {', '.join(DOWNLOAD_KEYS)})"
    )


def select_build(builds_payload: object, version: str, preview: bool) -> dict:
    """Pick one build record for a version.

    Stable mode prefers STABLE/RECOMMENDED channels; preview mode prefers
    snapshot-style channels. Falls back to the first entry rather than
    failing when a project uses an unexpected channel name.
    """
    if not isinstance(builds_payload, list) or not builds_payload:
        raise CanaryError(f"Fill builds payload for {version} is empty")
    builds = [b for b in builds_payload if isinstance(b, dict)]
    if not builds:
        raise CanaryError(f"Fill builds payload for {version} has no build objects")
    if preview:
        wanted = [b for b in builds if str(b.get("channel", "")).upper() in PREVIEW_CHANNELS]
    else:
        wanted = [b for b in builds if str(b.get("channel", "")).upper() in STABLE_CHANNELS]
    chosen = wanted[0] if wanted else builds[0]
    name, url, sha256 = _build_download(chosen)
    return {
        "version": version,
        "build": _build_number(chosen),
        "channel": str(chosen.get("channel", "UNKNOWN")),
        "name": name,
        "url": url,
        "sha256": sha256,
    }


def family_runtime(version: str) -> tuple[int, str]:
    """Map a Velocity version to (JVM, image); raise on unknown majors."""
    major = parse_version_tuple(version)[0]
    try:
        return FAMILY_RUNTIMES[major]
    except KeyError:
        raise UnknownFamilyError(version) from None


def build_manifest(
    stable: dict,
    preview: dict,
    run_id: str,
    generated_at: str | None = None,
) -> dict:
    """Assemble the manifest dict the harness consumes."""
    runtimes = []
    for slot, selected in (("stable", stable), ("preview", preview)):
        jvm, image = family_runtime(selected["version"])
        runtimes.append(
            {
                "channel": slot,
                "version": selected["version"],
                "build": str(selected["build"]),
                "downloadUrl": selected["url"],
                "sha256": selected["sha256"].lower(),
                "image": image,
                "jvm": jvm,
            }
        )
    return {
        "schema": MANIFEST_SCHEMA,
        "generator": "scripts/canary/resolve_canary.py",
        "runId": run_id,
        "generatedAt": generated_at
        or datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "runtimes": runtimes,
    }


def validate_manifest(manifest: object) -> list[dict]:
    """Validate a manifest and return its normalized runtime entries."""
    if not isinstance(manifest, dict):
        raise CanaryError("Canary manifest must be an object")
    if manifest.get("schema") != MANIFEST_SCHEMA:
        raise CanaryError(
            f"Canary manifest schema must be {MANIFEST_SCHEMA!r}"
        )
    runtimes = manifest.get("runtimes")
    if not isinstance(runtimes, list) or not runtimes:
        raise CanaryError("Canary manifest has no runtimes")
    seen_channels = set()
    normalized = []
    for entry in runtimes:
        if not isinstance(entry, dict):
            raise CanaryError("Canary manifest runtime must be an object")
        channel = entry.get("channel")
        version = entry.get("version")
        build = entry.get("build")
        url = entry.get("downloadUrl")
        sha256 = entry.get("sha256")
        image = entry.get("image")
        jvm = entry.get("jvm")
        if channel not in ("stable", "preview"):
            raise CanaryError(f"Canary manifest has unknown channel: {channel!r}")
        if channel in seen_channels:
            raise CanaryError(f"Canary manifest repeats channel: {channel!r}")
        seen_channels.add(channel)
        if not isinstance(version, str) or not VERSION_RE.match(version.strip()):
            raise CanaryError(f"Canary manifest has bad version: {version!r}")
        if not isinstance(build, str) or not build.strip():
            raise CanaryError("Canary manifest runtime needs a build id")
        if (
            not isinstance(url, str)
            or not url.startswith("https://")
            or DOWNLOAD_HOST_SUFFIX not in url
        ):
            raise CanaryError(
                "Canary manifest downloadUrl must be an https "
                f"{DOWNLOAD_HOST_SUFFIX} URL, got {url!r}"
            )
        if not isinstance(sha256, str) or not SHA256_RE.match(sha256.lower()):
            raise CanaryError("Canary manifest runtime needs a 64-hex sha256")
        if not isinstance(image, str) or not image.startswith(ALLOWED_IMAGE_PREFIX):
            raise CanaryError(
                "Canary manifest image must start with "
                f"{ALLOWED_IMAGE_PREFIX!r}, got {image!r}"
            )
        if not isinstance(jvm, int) or not 17 <= jvm <= 30:
            raise CanaryError(f"Canary manifest has bad jvm: {jvm!r}")
        normalized.append(
            {
                "channel": channel,
                "version": version,
                "build": build,
                "downloadUrl": url,
                "sha256": sha256.lower(),
                "image": image,
                "jvm": jvm,
            }
        )
    return normalized


def render_summary(manifest: dict, release_jar: str, master_jar: str) -> str:
    """Render the Actions job-summary table for a resolved manifest."""
    runtimes = validate_manifest(manifest)
    lines = [
        "## Velocity compatibility canary",
        "",
        f"Manifest run `{manifest.get('runId', '?')}` "
        f"generated `{manifest.get('generatedAt', '?')}`.",
        "",
        "| Channel | Velocity | Build | JVM | Image | SHA-256 |",
        "|---|---|---|---|---|---|",
    ]
    for runtime in runtimes:
        lines.append(
            f"| {runtime['channel']} | {runtime['version']} | {runtime['build']} "
            f"| {runtime['jvm']} | `{runtime['image']}` "
            f"| `{runtime['sha256'][:12]}…` |"
        )
    lines += [
        "",
        f"Release JAR: `{release_jar}`; master JAR: `{master_jar}` "
        "(reported separately per run).",
        "",
        "> Advisory only: a green canary is observed compatibility, not a "
        "support guarantee. Preview builds remain best-effort.",
    ]
    return "\n".join(lines) + "\n"
