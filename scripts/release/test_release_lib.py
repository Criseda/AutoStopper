from __future__ import annotations

import hashlib
import json
import shutil
import tempfile
import unittest
import urllib.parse
import zipfile
from pathlib import Path

from release_lib import (
    GITHUB_API,
    MINECRAFT_RELEASE_VERSIONS,
    MODRINTH_API,
    PUBLISH_RELEASE_JOB,
    REQUIRED_RELEASE_JOBS,
    ReleaseError,
    ReleasePublisher,
    Response,
    expand_minecraft_range,
    load_metadata,
    parse_version,
    pom_version,
    prepare_candidate,
    previous_stable_tag,
    select_candidate,
    validate_recovery_run,
    validate_source,
    verify_candidate,
)


def repository_release_context() -> tuple[Path, str, str]:
    repository = Path(__file__).resolve().parents[2]
    version = pom_version(repository)
    previous = load_metadata(repository)["previousTag"]
    return repository, version, previous


SYNTHETIC_SOURCE_RUN_ID = 987654321


class FakeHttpClient:
    def __init__(self, directory: Path, manifest: dict) -> None:
        self.directory = directory
        self.manifest = manifest
        self.jar = (directory / manifest["artifact"]["name"]).read_bytes()
        self.release_notes = (directory / "release-notes.md").read_text(
            encoding="utf-8"
        )
        self.modrinth_notes = (directory / "modrinth-changelog.md").read_text(
            encoding="utf-8"
        )
        self.github_release = None
        self.additional_github_releases: list[dict] = []
        self.github_assets: dict[str, bytes] = {}
        self.modrinth_version = None
        self.modrinth_project = {
            "id": "PG4gqnzX",
            "project_type": "mod",
            "client_side": "unsupported",
            "server_side": "required",
        }
        self.mutations: list[tuple[str, str]] = []

    @staticmethod
    def response(status: int, payload=None, body: bytes | None = None) -> Response:
        if body is None:
            body = json.dumps(payload).encode("utf-8") if payload is not None else b""
        return Response(status, body, {})

    def request(self, method: str, url: str, **kwargs) -> Response:
        if url.startswith(f"{GITHUB_API}/repos/Criseda/AutoStopper/releases/tags/"):
            return (
                self.response(200, self.github_release)
                if self.github_release and not self.github_release["draft"]
                else self.response(404)
            )
        if method == "GET" and url.startswith(
            f"{GITHUB_API}/repos/Criseda/AutoStopper/releases?"
        ):
            releases = self.additional_github_releases.copy()
            if self.github_release:
                releases.insert(0, self.github_release)
            return self.response(200, releases)
        if (
            method == "POST"
            and url == f"{GITHUB_API}/repos/Criseda/AutoStopper/releases"
        ):
            self.mutations.append((method, url))
            payload = json.loads(kwargs["body"])
            self.github_release = {
                **payload,
                "id": 7,
                "assets": [],
                "upload_url": "https://uploads.github.test/releases/7/assets{?name,label}",
            }
            return self.response(201, self.github_release)
        if method == "POST" and url.startswith(
            "https://uploads.github.test/releases/7/assets?"
        ):
            self.mutations.append((method, url))
            name = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)["name"][0]
            content = kwargs["body"]
            self.github_assets[name] = content
            asset = {
                "name": name,
                "url": f"https://api.github.test/assets/{urllib.parse.quote(name)}",
                "digest": f"sha256:{hashlib.sha256(content).hexdigest()}",
            }
            self.github_release["assets"].append(asset)
            return self.response(201, asset)
        if method == "GET" and url.startswith("https://api.github.test/assets/"):
            name = urllib.parse.unquote(url.rsplit("/", 1)[1])
            return self.response(200, body=self.github_assets[name])
        if (
            method == "PATCH"
            and url == f"{GITHUB_API}/repos/Criseda/AutoStopper/releases/7"
        ):
            self.mutations.append((method, url))
            self.github_release["draft"] = json.loads(kwargs["body"])["draft"]
            return self.response(200, self.github_release)

        if method == "GET" and url == f"{MODRINTH_API}/project/PG4gqnzX":
            self.assert_modrinth_token(kwargs)
            return self.response(200, self.modrinth_project)
        if method == "GET" and url == f"{MODRINTH_API}/project/PG4gqnzX/version":
            self.assert_modrinth_token(kwargs)
            versions = [self.modrinth_version] if self.modrinth_version else []
            return self.response(200, versions)
        if method == "POST" and url == f"{MODRINTH_API}/version":
            self.assert_modrinth_token(kwargs)
            if b'"environment":"server_only"' not in kwargs["body"]:
                raise AssertionError(
                    "Modrinth upload did not declare a server-only environment"
                )
            self.mutations.append((method, url))
            artifact = self.manifest["artifact"]
            self.modrinth_version = {
                "id": "version1",
                "name": "AutoStopper 2.0.0",
                "version_number": "2.0.0",
                "changelog": self.modrinth_notes,
                "dependencies": [],
                "game_versions": list(expand_minecraft_range("1.7.2", "26.2")),
                "version_type": "release",
                "loaders": ["velocity"],
                "featured": True,
                "status": "unlisted",
                "project_id": "PG4gqnzX",
                "files": [
                    {
                        "filename": artifact["name"],
                        "primary": True,
                        "hashes": {
                            "sha1": artifact["sha1"],
                            "sha512": artifact["sha512"],
                        },
                        "url": "https://cdn.modrinth.test/autostopper.jar",
                    }
                ],
            }
            return self.response(200, self.modrinth_version)
        if method == "PATCH" and url == f"{MODRINTH_API}/version/version1":
            self.mutations.append((method, url))
            self.modrinth_version["status"] = json.loads(kwargs["body"])["status"]
            return self.response(204)
        if method == "GET" and url == "https://cdn.modrinth.test/autostopper.jar":
            return self.response(200, body=self.jar)
        raise AssertionError(f"Unhandled fake request: {method} {url}")

    @staticmethod
    def assert_modrinth_token(arguments: dict) -> None:
        if arguments.get("token") != "modrinth-token":
            raise AssertionError("Modrinth request did not use the protected token")


def candidate(directory: Path) -> dict:
    jar = b"release-candidate-bytes"
    jar_name = "AutoStopper-2.0.0.jar"
    (directory / jar_name).write_bytes(jar)
    sha1 = hashlib.sha1(jar).hexdigest()
    sha256 = hashlib.sha256(jar).hexdigest()
    sha512 = hashlib.sha512(jar).hexdigest()
    (directory / f"{jar_name}.sha256").write_text(
        f"{sha256}  {jar_name}\n", encoding="ascii"
    )
    (directory / "release-notes.md").write_text(
        "Curated GitHub notes\n", encoding="utf-8"
    )
    (directory / "modrinth-changelog.md").write_text(
        "Curated Modrinth notes\n", encoding="utf-8"
    )
    return {
        "repository": "Criseda/AutoStopper",
        "tag": "2.0.0",
        "commit": "a" * 40,
        "artifact": {
            "name": jar_name,
            "size": len(jar),
            "sha1": sha1,
            "sha256": sha256,
            "sha512": sha512,
        },
        "compatibility": {
            "modrinth": {
                "projectId": "PG4gqnzX",
                "loaders": ["velocity"],
                "gameVersions": list(expand_minecraft_range("1.7.2", "26.2")),
                "environment": "server_only",
            }
        },
    }


def verified_candidate(directory: Path, note: str = "Curated GitHub notes\n") -> dict:
    jar_name = "AutoStopper-2.0.0.jar"
    jar_path = directory / jar_name
    with zipfile.ZipFile(jar_path, "w") as archive:
        archive.writestr("velocity-plugin.json", json.dumps({"version": "2.0.0"}))
        archive.writestr(
            "META-INF/MANIFEST.MF",
            "Manifest-Version: 1.0\nImplementation-Version: 2.0.0\n",
        )
    jar = jar_path.read_bytes()
    sha1 = hashlib.sha1(jar).hexdigest()
    sha256 = hashlib.sha256(jar).hexdigest()
    sha512 = hashlib.sha512(jar).hexdigest()
    modrinth_note = "Curated Modrinth notes\n"
    (directory / f"{jar_name}.sha256").write_text(
        f"{sha256}  {jar_name}\n", encoding="ascii"
    )
    (directory / f"{jar_name}.sha512").write_text(
        f"{sha512}  {jar_name}\n", encoding="ascii"
    )
    (directory / "release-notes.md").write_bytes(note.encode("utf-8"))
    (directory / "modrinth-changelog.md").write_bytes(modrinth_note.encode("utf-8"))
    manifest = {
        "schemaVersion": 1,
        "repository": "Criseda/AutoStopper",
        "tag": "2.0.0",
        "commit": "a" * 40,
        "artifact": {
            "name": jar_name,
            "size": len(jar),
            "sha1": sha1,
            "sha256": sha256,
            "sha512": sha512,
        },
        "releaseNotesSha256": hashlib.sha256(note.encode()).hexdigest(),
        "modrinthChangelogSha256": hashlib.sha256(modrinth_note.encode()).hexdigest(),
    }
    (directory / "release-manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return manifest


class VersionTest(unittest.TestCase):
    def test_stable_versions_and_previous_tag(self) -> None:
        self.assertEqual((2, 0, 0), parse_version("2.0.0"))
        self.assertEqual(
            "1.1.2",
            previous_stable_tag(
                ["1.0", "1.1.2-rc1", "1.1.1", "1.1.2", "2.0.0"], "2.0.0"
            ),
        )

    def test_prerelease_tag_is_rejected(self) -> None:
        with self.assertRaisesRegex(ReleaseError, "stable SemVer"):
            parse_version("2.0.0-rc1")

    def test_repository_source_is_coordinated(self) -> None:
        repository, version, previous = repository_release_context()
        notes = validate_source(repository, version, previous)
        self.assertTrue(notes)
        self.assertNotIn(f"## [{previous}]", notes)

    def test_repository_source_rejects_wrong_previous_tag(self) -> None:
        repository, version, _ = repository_release_context()
        with self.assertRaisesRegex(ReleaseError, "previousTag"):
            validate_source(repository, version, "1.1.1")


class MinecraftRangeTest(unittest.TestCase):
    def test_full_range_includes_every_catalogue_release(self) -> None:
        self.assertEqual(
            MINECRAFT_RELEASE_VERSIONS, expand_minecraft_range("1.7.2", "26.2")
        )
        self.assertEqual(83, len(MINECRAFT_RELEASE_VERSIONS))
        self.assertEqual("1.7.2", MINECRAFT_RELEASE_VERSIONS[0])
        self.assertEqual("26.2", MINECRAFT_RELEASE_VERSIONS[-1])

    def test_expands_inclusive_subrange_from_catalogue_order(self) -> None:
        expanded = expand_minecraft_range("1.21.4", "26.2")
        self.assertEqual("1.21.4", expanded[0])
        self.assertEqual("26.2", expanded[-1])
        self.assertEqual(
            MINECRAFT_RELEASE_VERSIONS[
                MINECRAFT_RELEASE_VERSIONS.index("1.21.4") :
            ],
            expanded,
        )

    def test_rejects_endpoint_missing_from_catalogue(self) -> None:
        with self.assertRaisesRegex(ReleaseError, "not in the known release catalogue"):
            expand_minecraft_range("1.6.4", "26.2")
        with self.assertRaisesRegex(ReleaseError, "not in the known release catalogue"):
            expand_minecraft_range("1.7.2", "26.3")

    def test_rejects_reversed_range(self) -> None:
        with self.assertRaisesRegex(ReleaseError, "sorts after"):
            expand_minecraft_range("26.2", "1.7.2")

    def test_repository_metadata_range_expands_to_published_2_0_0_list(self) -> None:
        repository = Path(__file__).resolve().parents[2]
        metadata = load_metadata(repository)
        minecraft_range = metadata["modrinth"]["minecraftVersionRange"]
        self.assertEqual(["1.7.2", "26.2"], minecraft_range)
        self.assertEqual(
            MINECRAFT_RELEASE_VERSIONS, expand_minecraft_range(*minecraft_range)
        )


class PublisherTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.directory = Path(self.temporary.name)
        self.manifest = candidate(self.directory)
        self.client = FakeHttpClient(self.directory, self.manifest)
        self.publisher = ReleasePublisher(
            self.client, self.directory, self.manifest, "github-token", "modrinth-token"
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_fresh_publish_then_rerun_is_non_mutating(self) -> None:
        self.publisher.publish()
        self.assertFalse(self.client.github_release["draft"])
        self.assertEqual("listed", self.client.modrinth_version["status"])
        first_mutations = list(self.client.mutations)
        self.publisher.publish()
        self.assertEqual(first_mutations, self.client.mutations)

    def test_existing_draft_hidden_from_tag_lookup_is_resumed(self) -> None:
        staged = self.publisher._stage_github()
        self.assertTrue(staged["draft"])

        self.publisher.publish()

        self.assertFalse(self.client.github_release["draft"])
        self.assertEqual("listed", self.client.modrinth_version["status"])
        self.assertEqual(
            1,
            self.client.mutations.count(
                ("POST", f"{GITHUB_API}/repos/Criseda/AutoStopper/releases")
            ),
        )
        self.assertEqual(
            2,
            sum(
                method == "POST"
                and url.startswith("https://uploads.github.test/releases/7/assets?")
                for method, url in self.client.mutations
            ),
        )

    def test_duplicate_releases_in_authenticated_listing_are_rejected(self) -> None:
        self.publisher._stage_github()
        self.client.additional_github_releases.append(dict(self.client.github_release))

        with self.assertRaisesRegex(ReleaseError, "duplicate releases"):
            self.publisher.publish()

    def test_conflicting_github_asset_is_rejected_without_clobber(self) -> None:
        self.publisher.publish()
        jar_name = self.manifest["artifact"]["name"]
        self.client.github_assets[jar_name] = b"different"
        self.client.github_release["assets"][0]["digest"] = "sha256:" + "0" * 64
        with self.assertRaisesRegex(ReleaseError, "digest"):
            self.publisher.publish()

    def test_conflicting_modrinth_hash_is_rejected_without_replace(self) -> None:
        self.publisher.publish()
        self.client.modrinth_version["files"][0]["hashes"]["sha512"] = "0" * 128
        with self.assertRaisesRegex(ReleaseError, "hashes"):
            self.publisher.publish()

    def test_conflicting_modrinth_project_side_is_rejected_without_upload(self) -> None:
        self.client.modrinth_project["client_side"] = "optional"
        with self.assertRaisesRegex(ReleaseError, "client_side"):
            self.publisher.publish()
        self.assertEqual([], self.client.mutations)


class CandidateSelectionTest(unittest.TestCase):
    def test_selects_single_directly_extracted_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            downloads = root / "downloads"
            downloads.mkdir()
            manifest = verified_candidate(downloads)
            output = root / "selected"

            selected = select_candidate(
                downloads,
                output,
                f"release-candidate-{'a' * 40}",
                "2.0.0",
                "a" * 40,
                "Criseda/AutoStopper",
            )

            self.assertEqual(
                manifest["artifact"]["sha256"], selected["artifact"]["sha256"]
            )

    def test_selects_latest_identical_run_attempt(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = root / f"release-candidate-{'a' * 40}-1"
            first.mkdir()
            manifest = verified_candidate(first)
            second = root / f"release-candidate-{'a' * 40}-2"
            shutil.copytree(first, second)
            output = root / "selected"

            selected = select_candidate(
                root,
                output,
                f"release-candidate-{'a' * 40}",
                "2.0.0",
                "a" * 40,
                "Criseda/AutoStopper",
            )

            self.assertEqual(
                manifest["artifact"]["sha256"], selected["artifact"]["sha256"]
            )
            self.assertEqual(
                (second / "release-manifest.json").read_bytes(),
                (output / "release-manifest.json").read_bytes(),
            )

    def test_disagreeing_run_attempts_block_publication(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = root / f"release-candidate-{'a' * 40}-1"
            first.mkdir()
            verified_candidate(first)
            second = root / f"release-candidate-{'a' * 40}-2"
            second.mkdir()
            verified_candidate(second, "Different but internally valid notes\n")

            with self.assertRaisesRegex(ReleaseError, "run attempts disagree"):
                select_candidate(
                    root,
                    root / "selected",
                    f"release-candidate-{'a' * 40}",
                    "2.0.0",
                    "a" * 40,
                    "Criseda/AutoStopper",
                )

    def test_mixed_direct_and_attempt_layout_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            downloads = root / "downloads"
            downloads.mkdir()
            verified_candidate(downloads)
            attempt = downloads / f"release-candidate-{'a' * 40}-1"
            attempt.mkdir()
            verified_candidate(attempt)

            with self.assertRaisesRegex(ReleaseError, "both a direct bundle"):
                select_candidate(
                    downloads,
                    root / "selected",
                    f"release-candidate-{'a' * 40}",
                    "2.0.0",
                    "a" * 40,
                    "Criseda/AutoStopper",
                )


def recovery_documents(directory: Path) -> tuple[Path, Path]:
    run_attempt = 1
    run = {
        "id": SYNTHETIC_SOURCE_RUN_ID,
        "event": "push",
        "head_branch": "2.0.0",
        "head_sha": "a" * 40,
        "status": "completed",
        "conclusion": "failure",
        "path": ".github/workflows/release.yml",
        "run_attempt": run_attempt,
        "repository": {"full_name": "Criseda/AutoStopper"},
    }
    jobs = [
        {
            "name": name,
            "status": "completed",
            "conclusion": "success",
            "run_attempt": run_attempt,
        }
        for name in sorted(REQUIRED_RELEASE_JOBS)
    ]
    jobs.append(
        {
            "name": PUBLISH_RELEASE_JOB,
            "status": "completed",
            "conclusion": "failure",
            "run_attempt": run_attempt,
        }
    )
    run_path = directory / "run.json"
    jobs_path = directory / "jobs.json"
    run_path.write_text(json.dumps(run), encoding="utf-8")
    jobs_path.write_text(json.dumps({"jobs": jobs}), encoding="utf-8")
    return run_path, jobs_path


class RecoveryRunTest(unittest.TestCase):
    def test_accepts_failed_publish_after_all_candidate_gates_pass(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            run, jobs = recovery_documents(Path(temporary))
            run.write_bytes(b"\xef\xbb\xbf" + run.read_bytes())
            validate_recovery_run(
                run,
                jobs,
                SYNTHETIC_SOURCE_RUN_ID,
                "2.0.0",
                "a" * 40,
                "Criseda/AutoStopper",
            )

    def test_rejects_failed_candidate_gate(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            run, jobs = recovery_documents(root)
            document = json.loads(jobs.read_text(encoding="utf-8"))
            document["jobs"][0]["conclusion"] = "failure"
            jobs.write_text(json.dumps(document), encoding="utf-8")

            with self.assertRaisesRegex(ReleaseError, "prerequisite job did not pass"):
                validate_recovery_run(
                    run,
                    jobs,
                    SYNTHETIC_SOURCE_RUN_ID,
                    "2.0.0",
                    "a" * 40,
                    "Criseda/AutoStopper",
                )

    def test_rejects_different_source_commit(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            run, jobs = recovery_documents(Path(temporary))
            with self.assertRaisesRegex(ReleaseError, "head_sha"):
                validate_recovery_run(
                    run,
                    jobs,
                    SYNTHETIC_SOURCE_RUN_ID,
                    "2.0.0",
                    "b" * 40,
                    "Criseda/AutoStopper",
                )


class CandidatePreparationTest(unittest.TestCase):
    def test_prepares_and_reverifies_e2e_candidate(self) -> None:
        repository, version, previous = repository_release_context()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / f"AutoStopper-{version}.jar"
            with zipfile.ZipFile(source, "w") as archive:
                archive.writestr(
                    "velocity-plugin.json", json.dumps({"version": version})
                )
                archive.writestr(
                    "META-INF/MANIFEST.MF",
                    f"Manifest-Version: 1.0\nImplementation-Version: {version}\n",
                )
            evidence = root / "candidate-manifest.json"
            evidence.write_text(
                json.dumps(
                    {
                        "commit": "a" * 40,
                        "projectVersion": version,
                        "artifact": source.name,
                        "size": source.stat().st_size,
                        "sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                        "velocityImage": "itzg/mc-proxy:2026.8.0-java25",
                        "velocityVersion": "4.1.0-SNAPSHOT-16",
                        "backendImage": "itzg/minecraft-server:java21",
                        "minecraftVersion": "1.21.4",
                        "purpurBuild": "2416",
                    }
                ),
                encoding="utf-8",
            )
            output = root / "release"

            prepared = prepare_candidate(
                repository,
                source,
                evidence,
                output,
                version,
                previous,
                "a" * 40,
                "Criseda/AutoStopper",
                "https://github.test/actions/runs/1",
            )
            verified = verify_candidate(
                output, version, "a" * 40, "Criseda/AutoStopper"
            )

            self.assertEqual(prepared, verified)
            self.assertEqual(source.read_bytes(), (output / source.name).read_bytes())
            self.assertEqual(
                list(expand_minecraft_range("1.7.2", "26.2")),
                prepared["compatibility"]["modrinth"]["gameVersions"],
            )


if __name__ == "__main__":
    unittest.main()
