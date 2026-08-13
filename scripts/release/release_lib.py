"""Validated, idempotent release support for AutoStopper.

The publisher deliberately has no replace or delete operation. Existing GitHub
or Modrinth state is either byte-for-byte compatible with the candidate or the
release fails for a maintainer to investigate.
"""

from __future__ import annotations

import hashlib
import json
import re
import shutil
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any

STABLE_VERSION = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
RELEASE_HEADING = re.compile(r"^## \[([^]]+)] - (\d{4}-\d{2}-\d{2})$", re.MULTILINE)
SECOND_LEVEL_HEADING = re.compile(r"^## ", re.MULTILINE)
GITHUB_API = "https://api.github.com"
MODRINTH_API = "https://api.modrinth.com/v2"
USER_AGENT = "Criseda/AutoStopper-release/2.0 (+https://github.com/Criseda/AutoStopper)"


class ReleaseError(RuntimeError):
    """Raised when release state violates an integrity contract."""


def parse_version(value: str) -> tuple[int, int, int]:
    match = STABLE_VERSION.fullmatch(value)
    if not match:
        raise ReleaseError(
            f"Release version must be stable SemVer X.Y.Z, got {value!r}"
        )
    return tuple(int(part) for part in match.groups())


def previous_stable_tag(tags: list[str], version: str) -> str:
    current = parse_version(version)
    candidates = [
        (parse_version(tag), tag) for tag in tags if STABLE_VERSION.fullmatch(tag)
    ]
    older = [candidate for candidate in candidates if candidate[0] < current]
    if not older:
        raise ReleaseError(f"No stable release tag exists before {version}")
    return max(older)[1]


def digest(path: Path, algorithm: str) -> str:
    result = hashlib.new(algorithm)
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def digest_bytes(content: bytes, algorithm: str = "sha256") -> str:
    return hashlib.new(algorithm, content).hexdigest()


def load_metadata(repository: Path) -> dict[str, Any]:
    metadata_path = repository / "release" / "release-metadata.json"
    try:
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ReleaseError(f"Cannot read {metadata_path}: {error}") from error
    if metadata.get("schemaVersion") != 1:
        raise ReleaseError("release-metadata.json must use schemaVersion 1")
    return metadata


def pom_version(repository: Path) -> str:
    root = ET.parse(repository / "pom.xml").getroot()
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    value = root.findtext("m:version", namespaces=namespace)
    if not value:
        raise ReleaseError("pom.xml has no project version")
    return value.strip()


def changelog_section(repository: Path, version: str, previous_tag: str) -> str:
    changelog = (repository / "CHANGELOG.md").read_text(encoding="utf-8")
    matching = [
        match
        for match in RELEASE_HEADING.finditer(changelog)
        if match.group(1) == version
    ]
    if len(matching) != 1:
        raise ReleaseError(
            f"CHANGELOG.md must contain exactly one dated [{version}] section"
        )
    match = matching[0]
    next_heading = SECOND_LEVEL_HEADING.search(changelog, match.end())
    end = next_heading.start() if next_heading else len(changelog)
    content = changelog[match.end() : end].strip()
    if not content:
        raise ReleaseError(f"CHANGELOG.md section {version} is empty")
    expected_link = f"[{version}]: https://github.com/Criseda/AutoStopper/compare/{previous_tag}...{version}"
    if changelog.count(expected_link) != 1:
        raise ReleaseError(
            f"CHANGELOG.md must contain comparison link: {expected_link}"
        )
    return content


def validate_source(repository: Path, version: str, previous_tag: str) -> str:
    parse_version(version)
    parse_version(previous_tag)
    if parse_version(previous_tag) >= parse_version(version):
        raise ReleaseError(f"Previous tag {previous_tag} must be older than {version}")
    actual_pom_version = pom_version(repository)
    if actual_pom_version != version:
        raise ReleaseError(
            f"pom.xml version is {actual_pom_version}, expected tag version {version}"
        )

    descriptor_path = repository / "src" / "main" / "resources" / "velocity-plugin.json"
    descriptor = json.loads(descriptor_path.read_text(encoding="utf-8"))
    if descriptor.get("version") != "${project.version}":
        raise ReleaseError(
            "source velocity-plugin.json must derive version from ${project.version}"
        )

    plugin_source = (
        repository
        / "src"
        / "main"
        / "java"
        / "me"
        / "criseda"
        / "autostopper"
        / "AutoStopperPlugin.java"
    ).read_text(encoding="utf-8")
    annotation_versions = re.findall(
        r"@Plugin\(.*?\bversion\s*=\s*\"([^\"]+)\"", plugin_source, flags=re.DOTALL
    )
    if annotation_versions != [version]:
        raise ReleaseError(
            f"AutoStopperPlugin @Plugin version is {annotation_versions!r}, expected [{version!r}]"
        )

    metadata = load_metadata(repository)
    expected_name = metadata.get("artifactName")
    if expected_name != "AutoStopper-{version}.jar":
        raise ReleaseError("release metadata must name the versioned final shaded JAR")
    modrinth = metadata.get("modrinth", {})
    if modrinth.get("projectId") != "PG4gqnzX":
        raise ReleaseError("release metadata has the wrong Modrinth project ID")
    if modrinth.get("loaders") != ["velocity"]:
        raise ReleaseError("Modrinth loader metadata must be exactly Velocity")
    if modrinth.get("gameVersions") != ["1.21.4"]:
        raise ReleaseError(
            "Modrinth game-version metadata must match the tested 1.21.4 backend"
        )
    if modrinth.get("environment") != "server_only":
        raise ReleaseError("Modrinth environment must be server_only")
    if metadata.get("javaBytecode") != 21:
        raise ReleaseError("release metadata must declare Java 21 bytecode")
    expected_runtimes = [
        {
            "line": "legacy",
            "java": 21,
            "version": "3.5.1",
            "build": "615",
            "image": "itzg/mc-proxy:2026.8.0-java21",
        },
        {
            "line": "current",
            "java": 25,
            "version": "4.1.0-SNAPSHOT",
            "build": "16",
            "image": "itzg/mc-proxy:2026.8.0-java25",
        },
    ]
    if metadata.get("velocityRuntimes") != expected_runtimes:
        raise ReleaseError(
            "release metadata does not match the tested Velocity support matrix"
        )
    expected_release_candidate = {
        "velocityImage": "itzg/mc-proxy:2026.8.0-java25",
        "velocityVersion": "4.1.0-SNAPSHOT-16",
        "backendImage": "itzg/minecraft-server:java21",
        "minecraftVersion": "1.21.4",
        "purpurBuild": "2416",
    }
    if metadata.get("releaseCandidate") != expected_release_candidate:
        raise ReleaseError(
            "release metadata does not match the live candidate test matrix"
        )

    return changelog_section(repository, version, previous_tag)


def _manifest_attribute(manifest: str, name: str) -> str | None:
    values = re.findall(rf"^{re.escape(name)}:\s*(.+)$", manifest, flags=re.MULTILINE)
    if len(values) > 1:
        raise ReleaseError(f"JAR manifest contains duplicate {name} attributes")
    return values[0].strip() if values else None


def verify_jar(jar: Path, version: str) -> None:
    try:
        with zipfile.ZipFile(jar) as archive:
            descriptor = json.loads(
                archive.read("velocity-plugin.json").decode("utf-8")
            )
            manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
    except (OSError, KeyError, zipfile.BadZipFile, json.JSONDecodeError) as error:
        raise ReleaseError(f"Cannot inspect release JAR {jar}: {error}") from error
    if descriptor.get("version") != version:
        raise ReleaseError(
            f"Packaged velocity-plugin.json version is {descriptor.get('version')!r}, expected {version}"
        )
    if _manifest_attribute(manifest, "Implementation-Version") != version:
        raise ReleaseError(
            "Packaged manifest Implementation-Version does not match the tag"
        )


def _absolute_release_links(content: str, repository: str, tag: str) -> str:
    return content.replace(
        "](docs/",
        f"](https://github.com/{repository}/blob/{tag}/docs/",
    )


def prepare_candidate(
    repository_path: Path,
    jar: Path,
    evidence_manifest_path: Path,
    output_directory: Path,
    version: str,
    previous_tag: str,
    commit: str,
    repository_name: str,
    run_url: str,
) -> dict[str, Any]:
    notes = validate_source(repository_path, version, previous_tag)
    metadata = load_metadata(repository_path)
    expected_name = metadata["artifactName"].format(version=version)
    if jar.name != expected_name:
        raise ReleaseError(
            f"Candidate filename is {jar.name}, expected {expected_name}"
        )
    verify_jar(jar, version)

    try:
        evidence = json.loads(evidence_manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ReleaseError(f"Cannot read E2E candidate manifest: {error}") from error
    sha256 = digest(jar, "sha256")
    sha512 = digest(jar, "sha512")
    sha1 = digest(jar, "sha1")
    expected_evidence = {
        "commit": commit,
        "projectVersion": version,
        "artifact": expected_name,
        "size": jar.stat().st_size,
        "sha256": sha256,
        **metadata["releaseCandidate"],
    }
    for key, expected in expected_evidence.items():
        if evidence.get(key) != expected:
            raise ReleaseError(
                f"E2E candidate manifest {key} is {evidence.get(key)!r}, expected {expected!r}"
            )

    output_directory.mkdir(parents=True, exist_ok=False)
    output_jar = output_directory / expected_name
    shutil.copyfile(jar, output_jar)
    checksum = f"{sha256}  {expected_name}\n"
    checksum512 = f"{sha512}  {expected_name}\n"
    (output_directory / f"{expected_name}.sha256").write_text(
        checksum, encoding="ascii"
    )
    (output_directory / f"{expected_name}.sha512").write_text(
        checksum512, encoding="ascii"
    )

    modrinth_notes = (
        _absolute_release_links(notes, repository_name, version).strip() + "\n"
    )
    github_notes = (
        modrinth_notes
        + "\n### Verification\n\n"
        + f"- SHA-256: `{sha256}` (also attached as `{expected_name}.sha256`).\n"
        + f"- Build and release-candidate evidence: [{run_url}]({run_url}).\n"
        + f"- Verify build provenance with `gh attestation verify {expected_name} --repo {repository_name}`.\n"
    )
    (output_directory / "modrinth-changelog.md").write_bytes(
        modrinth_notes.encode("utf-8")
    )
    (output_directory / "release-notes.md").write_bytes(github_notes.encode("utf-8"))

    manifest = {
        "schemaVersion": 1,
        "repository": repository_name,
        "tag": version,
        "commit": commit,
        "previousTag": previous_tag,
        "artifact": {
            "name": expected_name,
            "size": output_jar.stat().st_size,
            "sha1": sha1,
            "sha256": sha256,
            "sha512": sha512,
        },
        "compatibility": {
            "javaBytecode": metadata["javaBytecode"],
            "velocityRuntimes": metadata["velocityRuntimes"],
            "modrinth": metadata["modrinth"],
            "releaseCandidate": metadata["releaseCandidate"],
        },
        "evidence": {
            "workflowRun": run_url,
            "candidateManifestSha256": digest(evidence_manifest_path, "sha256"),
        },
        "releaseNotesSha256": digest_bytes(github_notes.encode("utf-8")),
        "modrinthChangelogSha256": digest_bytes(modrinth_notes.encode("utf-8")),
    }
    (output_directory / "release-manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    verify_candidate(output_directory, version, commit, repository_name)
    return manifest


def verify_candidate(
    candidate_directory: Path, version: str, commit: str, repository_name: str
) -> dict[str, Any]:
    manifest_path = candidate_directory / "release-manifest.json"
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ReleaseError(f"Cannot read release manifest: {error}") from error
    if manifest.get("schemaVersion") != 1:
        raise ReleaseError("Release manifest schema is not 1")
    expected_top = {"tag": version, "commit": commit, "repository": repository_name}
    for key, expected in expected_top.items():
        if manifest.get(key) != expected:
            raise ReleaseError(f"Release manifest {key} does not match {expected!r}")

    artifact = manifest.get("artifact", {})
    expected_name = f"AutoStopper-{version}.jar"
    if artifact.get("name") != expected_name:
        raise ReleaseError("Release manifest artifact name does not match the version")
    jar = candidate_directory / expected_name
    if not jar.is_file():
        raise ReleaseError(f"Release candidate is missing {expected_name}")
    verify_jar(jar, version)
    expected_digests = {
        "size": jar.stat().st_size,
        "sha1": digest(jar, "sha1"),
        "sha256": digest(jar, "sha256"),
        "sha512": digest(jar, "sha512"),
    }
    for key, expected in expected_digests.items():
        if artifact.get(key) != expected:
            raise ReleaseError(
                f"Release manifest artifact {key} does not match the candidate"
            )
    for algorithm in ("sha256", "sha512"):
        checksum_path = candidate_directory / f"{expected_name}.{algorithm}"
        expected_content = f"{artifact[algorithm]}  {expected_name}\n"
        if checksum_path.read_text(encoding="ascii") != expected_content:
            raise ReleaseError(f"{checksum_path.name} does not match the candidate")
    release_notes = (candidate_directory / "release-notes.md").read_bytes()
    modrinth_notes = (candidate_directory / "modrinth-changelog.md").read_bytes()
    if digest_bytes(release_notes) != manifest.get("releaseNotesSha256"):
        raise ReleaseError("Release notes digest does not match the release manifest")
    if digest_bytes(modrinth_notes) != manifest.get("modrinthChangelogSha256"):
        raise ReleaseError(
            "Modrinth changelog digest does not match the release manifest"
        )
    return manifest


def select_candidate(
    downloads_directory: Path,
    output_directory: Path,
    artifact_prefix: str,
    version: str,
    commit: str,
    repository_name: str,
) -> dict[str, Any]:
    """Select the newest byte-identical candidate from one or more run attempts."""
    attempts: list[tuple[int, Path, dict[str, Any]]] = []
    pattern = re.compile(rf"^{re.escape(artifact_prefix)}-([1-9][0-9]*)$")
    if not downloads_directory.is_dir():
        raise ReleaseError(
            f"Candidate download directory does not exist: {downloads_directory}"
        )
    for child in downloads_directory.iterdir():
        match = pattern.fullmatch(child.name)
        if child.is_dir() and match:
            attempts.append(
                (
                    int(match.group(1)),
                    child,
                    verify_candidate(child, version, commit, repository_name),
                )
            )
    if not attempts:
        raise ReleaseError(
            f"No candidate artifacts match {artifact_prefix}-<run-attempt>"
        )

    attempts.sort(key=lambda item: item[0])
    expected_files = {
        f"AutoStopper-{version}.jar",
        f"AutoStopper-{version}.jar.sha256",
        f"AutoStopper-{version}.jar.sha512",
        "modrinth-changelog.md",
        "release-manifest.json",
        "release-notes.md",
    }
    baseline = attempts[0][1]
    baseline_files = {path.name for path in baseline.iterdir() if path.is_file()}
    if baseline_files != expected_files:
        raise ReleaseError("Candidate artifact contains missing or unexpected files")
    for _, directory, _ in attempts[1:]:
        actual_files = {path.name for path in directory.iterdir() if path.is_file()}
        if actual_files != expected_files:
            raise ReleaseError(
                "Candidate artifact contains missing or unexpected files"
            )
        for name in expected_files:
            if (directory / name).read_bytes() != (baseline / name).read_bytes():
                raise ReleaseError(
                    f"Release candidate run attempts disagree on {name}; publication is blocked"
                )

    selected = attempts[-1]
    if output_directory.exists():
        raise ReleaseError(
            f"Candidate output directory already exists: {output_directory}"
        )
    shutil.copytree(selected[1], output_directory)
    return verify_candidate(output_directory, version, commit, repository_name)


@dataclass(frozen=True)
class Response:
    status: int
    body: bytes
    headers: dict[str, str]

    def json(self) -> Any:
        return json.loads(self.body.decode("utf-8"))


class HttpClient:
    def request(
        self,
        method: str,
        url: str,
        *,
        token: str | None = None,
        github: bool = False,
        body: bytes | None = None,
        content_type: str | None = None,
        accept: str = "application/json",
    ) -> Response:
        headers = {"Accept": accept, "User-Agent": USER_AGENT}
        if github:
            headers["X-GitHub-Api-Version"] = "2022-11-28"
        if token:
            headers["Authorization"] = f"Bearer {token}" if github else token
        if content_type:
            headers["Content-Type"] = content_type
        request = urllib.request.Request(url, data=body, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return Response(
                    response.status, response.read(), dict(response.headers.items())
                )
        except urllib.error.HTTPError as error:
            response_body = error.read()
            if error.code == 404:
                return Response(404, response_body, dict(error.headers.items()))
            detail = response_body.decode("utf-8", errors="replace")
            raise ReleaseError(
                f"{method} {url} failed with HTTP {error.code}: {detail}"
            ) from error
        except urllib.error.URLError as error:
            raise ReleaseError(f"{method} {url} failed: {error.reason}") from error


class ReleasePublisher:
    def __init__(
        self,
        client: HttpClient,
        candidate_directory: Path,
        manifest: dict[str, Any],
        github_token: str,
        modrinth_token: str,
    ) -> None:
        self.client = client
        self.directory = candidate_directory
        self.manifest = manifest
        self.github_token = github_token
        self.modrinth_token = modrinth_token
        self.repository = manifest["repository"]
        self.version = manifest["tag"]
        self.artifact = manifest["artifact"]
        self.jar = self.directory / self.artifact["name"]
        self.checksum = self.directory / f"{self.artifact['name']}.sha256"
        self.release_notes = (self.directory / "release-notes.md").read_text(
            encoding="utf-8"
        )
        self.modrinth_notes = (self.directory / "modrinth-changelog.md").read_text(
            encoding="utf-8"
        )

    def publish(self) -> None:
        self._verify_modrinth_project()
        github_release = self._stage_github()
        modrinth_version = self._stage_modrinth()
        self._verify_github(github_release)
        self._verify_modrinth(modrinth_version)

        if modrinth_version["status"] == "unlisted":
            response = self.client.request(
                "PATCH",
                f"{MODRINTH_API}/version/{modrinth_version['id']}",
                token=self.modrinth_token,
                body=json.dumps({"status": "listed"}).encode("utf-8"),
                content_type="application/json",
            )
            if response.status != 204:
                raise ReleaseError(
                    f"Unexpected Modrinth promotion status {response.status}"
                )

        if github_release["draft"]:
            response = self.client.request(
                "PATCH",
                f"{GITHUB_API}/repos/{self.repository}/releases/{github_release['id']}",
                token=self.github_token,
                github=True,
                body=json.dumps({"draft": False}).encode("utf-8"),
                content_type="application/json",
            )
            if response.status != 200:
                raise ReleaseError(
                    f"Unexpected GitHub publication status {response.status}"
                )

        final_modrinth = self._find_modrinth_version(required=True)
        final_github = self._get_github_release(required=True)
        self._verify_modrinth_project()
        self._verify_modrinth(final_modrinth, expected_status="listed")
        self._verify_github(final_github, expected_draft=False)

    def _get_github_release(self, *, required: bool = False) -> dict[str, Any] | None:
        tag = urllib.parse.quote(self.version, safe="")
        response = self.client.request(
            "GET",
            f"{GITHUB_API}/repos/{self.repository}/releases/tags/{tag}",
            token=self.github_token,
            github=True,
        )
        if response.status == 404:
            if required:
                raise ReleaseError(f"GitHub release {self.version} does not exist")
            return None
        if response.status != 200:
            raise ReleaseError(
                f"Unexpected GitHub release lookup status {response.status}"
            )
        return response.json()

    def _stage_github(self) -> dict[str, Any]:
        release = self._get_github_release()
        if release is None:
            payload = {
                "tag_name": self.version,
                "target_commitish": self.manifest["commit"],
                "name": f"AutoStopper {self.version}",
                "body": self.release_notes,
                "draft": True,
                "prerelease": False,
            }
            response = self.client.request(
                "POST",
                f"{GITHUB_API}/repos/{self.repository}/releases",
                token=self.github_token,
                github=True,
                body=json.dumps(payload).encode("utf-8"),
                content_type="application/json",
            )
            if response.status != 201:
                raise ReleaseError(
                    f"Unexpected GitHub draft creation status {response.status}"
                )
            release = response.json()

        self._validate_github_metadata(release)
        assets = {asset["name"]: asset for asset in release.get("assets", [])}
        expected_assets = {
            self.jar.name: (self.jar, "application/java-archive"),
            self.checksum.name: (self.checksum, "text/plain"),
        }
        for name, (path, content_type) in expected_assets.items():
            if name in assets:
                self._verify_github_asset(assets[name], path)
                continue
            if not release["draft"]:
                raise ReleaseError(
                    f"Published GitHub release is missing immutable asset {name}"
                )
            upload_url = release["upload_url"].split("{", 1)[0]
            response = self.client.request(
                "POST",
                f"{upload_url}?{urllib.parse.urlencode({'name': name})}",
                token=self.github_token,
                github=True,
                body=path.read_bytes(),
                content_type=content_type,
            )
            if response.status != 201:
                raise ReleaseError(
                    f"Unexpected GitHub asset upload status {response.status}"
                )
        return self._get_github_release(required=True)

    def _validate_github_metadata(self, release: dict[str, Any]) -> None:
        expected = {
            "tag_name": self.version,
            "name": f"AutoStopper {self.version}",
            "body": self.release_notes,
            "prerelease": False,
        }
        for key, value in expected.items():
            if release.get(key) != value:
                raise ReleaseError(
                    f"Existing GitHub release {key} is {release.get(key)!r}, expected {value!r}"
                )

    def _verify_github_asset(self, asset: dict[str, Any], path: Path) -> None:
        expected_digest = f"sha256:{digest(path, 'sha256')}"
        api_digest = asset.get("digest")
        if api_digest and api_digest != expected_digest:
            raise ReleaseError(
                f"GitHub asset {asset['name']} digest is {api_digest}, expected {expected_digest}"
            )
        response = self.client.request(
            "GET",
            asset["url"],
            token=self.github_token,
            github=True,
            accept="application/octet-stream",
        )
        if response.status != 200 or response.body != path.read_bytes():
            raise ReleaseError(
                f"GitHub asset {asset['name']} is not byte-identical to the candidate"
            )

    def _verify_github(
        self, release: dict[str, Any], *, expected_draft: bool | None = None
    ) -> None:
        self._validate_github_metadata(release)
        if expected_draft is not None and release.get("draft") is not expected_draft:
            raise ReleaseError(f"GitHub release draft state is not {expected_draft}")
        assets = {asset["name"]: asset for asset in release.get("assets", [])}
        for path in (self.jar, self.checksum):
            if path.name not in assets:
                raise ReleaseError(f"GitHub release is missing {path.name}")
            self._verify_github_asset(assets[path.name], path)

    def _find_modrinth_version(
        self, *, required: bool = False
    ) -> dict[str, Any] | None:
        project_id = self.manifest["compatibility"]["modrinth"]["projectId"]
        response = self.client.request(
            "GET",
            f"{MODRINTH_API}/project/{project_id}/version",
            token=self.modrinth_token,
        )
        if response.status != 200:
            raise ReleaseError(
                f"Unexpected Modrinth version lookup status {response.status}"
            )
        matches = [
            item
            for item in response.json()
            if item.get("version_number") == self.version
        ]
        if len(matches) > 1:
            raise ReleaseError(
                f"Modrinth contains duplicate version number {self.version}"
            )
        if not matches:
            if required:
                raise ReleaseError(f"Modrinth version {self.version} does not exist")
            return None
        return matches[0]

    def _verify_modrinth_project(self) -> None:
        project_id = self.manifest["compatibility"]["modrinth"]["projectId"]
        response = self.client.request(
            "GET", f"{MODRINTH_API}/project/{project_id}", token=self.modrinth_token
        )
        if response.status != 200:
            raise ReleaseError(
                f"Unexpected Modrinth project lookup status {response.status}"
            )
        project = response.json()
        expected = {
            "id": project_id,
            "project_type": "mod",
            "client_side": "unsupported",
            "server_side": "required",
        }
        for key, value in expected.items():
            if project.get(key) != value:
                raise ReleaseError(
                    f"Modrinth project {key} is {project.get(key)!r}, expected {value!r}"
                )

    @staticmethod
    def _multipart(
        data: dict[str, Any], field: str, filename: str, content: bytes
    ) -> tuple[bytes, str]:
        boundary = f"autostopper-{uuid.uuid4().hex}"
        encoded_data = json.dumps(data, separators=(",", ":")).encode("utf-8")
        body = bytearray()
        body.extend(f"--{boundary}\r\n".encode())
        body.extend(b'Content-Disposition: form-data; name="data"\r\n')
        body.extend(b"Content-Type: application/json\r\n\r\n")
        body.extend(encoded_data)
        body.extend(b"\r\n")
        body.extend(f"--{boundary}\r\n".encode())
        body.extend(
            f'Content-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'.encode()
        )
        body.extend(b"Content-Type: application/java-archive\r\n\r\n")
        body.extend(content)
        body.extend(b"\r\n")
        body.extend(f"--{boundary}--\r\n".encode())
        return bytes(body), f"multipart/form-data; boundary={boundary}"

    def _stage_modrinth(self) -> dict[str, Any]:
        version = self._find_modrinth_version()
        if version is None:
            compatibility = self.manifest["compatibility"]["modrinth"]
            payload = {
                "name": f"AutoStopper {self.version}",
                "version_number": self.version,
                "changelog": self.modrinth_notes,
                "dependencies": [],
                "game_versions": compatibility["gameVersions"],
                "version_type": "release",
                "loaders": compatibility["loaders"],
                "featured": True,
                "status": "unlisted",
                "project_id": compatibility["projectId"],
                "file_parts": ["primary"],
                "primary_file": "primary",
                "environment": compatibility["environment"],
            }
            body, content_type = self._multipart(
                payload, "primary", self.jar.name, self.jar.read_bytes()
            )
            response = self.client.request(
                "POST",
                f"{MODRINTH_API}/version",
                token=self.modrinth_token,
                body=body,
                content_type=content_type,
            )
            if response.status != 200:
                raise ReleaseError(
                    f"Unexpected Modrinth version creation status {response.status}"
                )
            version = response.json()
        self._verify_modrinth(version)
        return version

    def _verify_modrinth(
        self, version: dict[str, Any], *, expected_status: str | None = None
    ) -> None:
        compatibility = self.manifest["compatibility"]["modrinth"]
        expected = {
            "name": f"AutoStopper {self.version}",
            "version_number": self.version,
            "changelog": self.modrinth_notes,
            "dependencies": [],
            "game_versions": compatibility["gameVersions"],
            "version_type": "release",
            "loaders": compatibility["loaders"],
            "featured": True,
            "project_id": compatibility["projectId"],
        }
        for key, value in expected.items():
            if version.get(key) != value:
                raise ReleaseError(
                    f"Existing Modrinth version {key} is {version.get(key)!r}, expected {value!r}"
                )
        status = version.get("status")
        if status not in {"unlisted", "listed"}:
            raise ReleaseError(
                f"Modrinth version status {status!r} is not safe to resume"
            )
        if expected_status and status != expected_status:
            raise ReleaseError(
                f"Modrinth version status is {status}, expected {expected_status}"
            )
        files = version.get("files", [])
        if len(files) != 1:
            raise ReleaseError("Modrinth release must contain exactly one file")
        file = files[0]
        if file.get("filename") != self.jar.name or file.get("primary") is not True:
            raise ReleaseError(
                "Modrinth primary file metadata does not match the candidate"
            )
        expected_hashes = {
            "sha1": self.artifact["sha1"],
            "sha512": self.artifact["sha512"],
        }
        if file.get("hashes") != expected_hashes:
            raise ReleaseError("Modrinth file hashes do not match the candidate")
        response = self.client.request(
            "GET", file["url"], accept="application/java-archive"
        )
        if response.status != 200 or response.body != self.jar.read_bytes():
            raise ReleaseError("Modrinth file is not byte-identical to the candidate")
