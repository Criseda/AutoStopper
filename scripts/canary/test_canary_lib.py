"""Offline unit tests for the compatibility-canary resolver logic."""

from __future__ import annotations

import unittest

from canary_lib import (
    CanaryError,
    UnknownFamilyError,
    build_manifest,
    extract_versions,
    family_runtime,
    pick_track_versions,
    render_summary,
    select_build,
    validate_manifest,
)


def builds(*entries: tuple[str, str, str]) -> list[dict]:
    return [
        {
            "number": number,
            "channel": channel,
            "downloads": {
                "proxy:default": {
                    "name": f"velocity-{number}.jar",
                    "url": f"https://fill-data.papermc.io/v1/objects/{'a' * 64}/v.jar",
                    "checksums": {"sha256": "b" * 64},
                }
            },
        }
        for number, channel, _ in entries
    ]


STABLE = {
    "version": "4.0.0",
    "build": "6",
    "channel": "STABLE",
    "name": "velocity-4.0.0-6.jar",
    "url": "https://fill-data.papermc.io/v1/objects/" + "c" * 64 + "/v.jar",
    "sha256": "C" * 64,
}
PREVIEW = {
    "version": "4.1.0-SNAPSHOT",
    "build": "27",
    "channel": "SNAPSHOT",
    "name": "velocity-4.1.0-SNAPSHOT-27.jar",
    "url": "https://fill-data.papermc.io/v1/objects/" + "d" * 64 + "/v.jar",
    "sha256": "d" * 64,
}


class ExtractVersionsTest(unittest.TestCase):
    def test_grouped_map_shape(self) -> None:
        payload = {"versions": {"stable": ["4.0.0", "3.5.1"], "preview": ["4.1.0-SNAPSHOT"]}}
        self.assertEqual(
            extract_versions(payload), ["4.0.0", "3.5.1", "4.1.0-SNAPSHOT"]
        )

    def test_flat_list_shape(self) -> None:
        self.assertEqual(extract_versions({"versions": ["4.0.0"]}), ["4.0.0"])

    def test_empty_payload_fails_closed(self) -> None:
        with self.assertRaises(CanaryError):
            extract_versions({"versions": []})


class TrackSelectionTest(unittest.TestCase):
    def test_stable_is_highest_release(self) -> None:
        stable, preview = pick_track_versions(["3.5.1", "4.0.0", "4.1.0-SNAPSHOT"])
        self.assertEqual((stable, preview), ("4.0.0", "4.1.0-SNAPSHOT"))

    def test_preview_falls_back_without_snapshots(self) -> None:
        stable, preview = pick_track_versions(["3.5.1", "4.0.0"])
        self.assertEqual((stable, preview), ("4.0.0", "4.0.0"))


class BuildSelectionTest(unittest.TestCase):
    def test_stable_prefers_recommended_over_newer_snapshot(self) -> None:
        payload = builds(("28", "SNAPSHOT", ""), ("6", "RECOMMENDED", ""))
        self.assertEqual(select_build(payload, "4.0.0", preview=False)["build"], "6")

    def test_preview_prefers_snapshot(self) -> None:
        payload = builds(("6", "STABLE", ""), ("27", "SNAPSHOT", ""))
        self.assertEqual(select_build(payload, "4.1.0-SNAPSHOT", preview=True)["build"], "27")

    def test_empty_builds_fail_closed(self) -> None:
        with self.assertRaises(CanaryError):
            select_build([], "4.0.0", preview=False)


class FamilyRuntimeTest(unittest.TestCase):
    def test_known_families(self) -> None:
        self.assertEqual(family_runtime("3.5.1"), (21, "eclipse-temurin:21-jre"))
        self.assertEqual(family_runtime("4.0.0"), (25, "eclipse-temurin:25-jre"))

    def test_unknown_major_needs_review(self) -> None:
        with self.assertRaises(UnknownFamilyError):
            family_runtime("5.0.0")


class ManifestTest(unittest.TestCase):
    def test_round_trip_and_summary(self) -> None:
        manifest = build_manifest(STABLE, PREVIEW, "run-1", "2026-09-07T00:00:00+00:00")
        runtimes = validate_manifest(manifest)
        self.assertEqual([r["channel"] for r in runtimes], ["stable", "preview"])
        summary = render_summary(manifest, "release.jar", "master.jar")
        self.assertIn("4.0.0", summary)
        self.assertIn("Advisory only", summary)

    def test_rejects_non_fill_url(self) -> None:
        manifest = build_manifest(STABLE, PREVIEW, "run-1")
        manifest["runtimes"][0]["downloadUrl"] = "https://example.com/v.jar"
        with self.assertRaises(CanaryError):
            validate_manifest(manifest)

    def test_rejects_bad_checksum(self) -> None:
        manifest = build_manifest(STABLE, PREVIEW, "run-1")
        manifest["runtimes"][1]["sha256"] = "not-a-hash"
        with self.assertRaises(CanaryError):
            validate_manifest(manifest)

    def test_rejects_wrong_schema(self) -> None:
        with self.assertRaises(CanaryError):
            validate_manifest({"schema": "other", "runtimes": []})


if __name__ == "__main__":
    unittest.main()
