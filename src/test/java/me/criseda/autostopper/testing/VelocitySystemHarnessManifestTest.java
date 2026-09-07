package me.criseda.autostopper.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocitySystemHarnessManifestTest {
    @TempDir
    Path temporaryDirectory;

    private Path writeManifest(String body) throws Exception {
        Path manifest = temporaryDirectory.resolve("canary-manifest.yml");
        Files.writeString(manifest, body, StandardCharsets.UTF_8);
        return manifest;
    }

    private String validManifest() {
        return """
                schema: autostopper-velocity-canary/v1
                generator: scripts/canary/resolve_canary.py
                runId: test-run
                generatedAt: '2026-09-07T00:00:00+00:00'
                runtimes:
                  - channel: stable
                    version: 4.0.0
                    build: '6'
                    downloadUrl: 'https://fill-data.papermc.io/v1/objects/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/v.jar'
                    sha256: bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
                    image: 'eclipse-temurin:25-jre'
                    jvm: 25
                  - channel: preview
                    version: 4.1.0-SNAPSHOT
                    build: '27'
                    downloadUrl: 'https://fill-data.papermc.io/v1/objects/cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc/v.jar'
                    sha256: dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd
                    image: 'eclipse-temurin:25-jre'
                    jvm: 25
                """;
    }

    @Test
    void loadsValidatedManifestRuntimes() throws Exception {
        List<VelocitySystemHarness.RuntimeProfile> runtimes =
                VelocitySystemHarness.loadManifestRuntimes(writeManifest(validManifest()));

        assertEquals(List.of("stable", "preview"),
                runtimes.stream().map(VelocitySystemHarness.RuntimeProfile::name).toList());
        assertEquals("eclipse-temurin:25-jre", runtimes.get(0).image());
        assertTrue(runtimes.get(1).velocityLabel().contains("4.1.0-SNAPSHOT"));
    }

    @Test
    void loadsJsonFlowStyleManifestAsWrittenByResolver() throws Exception {
        Path manifest = writeManifest("""
                {"schema": "autostopper-velocity-canary/v1", "runId": "dry-run",
                 "runtimes": [
                  {"channel": "stable", "version": "4.1.1", "build": "24",
                   "downloadUrl": "https://fill-data.papermc.io/v1/objects/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/v.jar",
                   "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                   "image": "eclipse-temurin:25-jre", "jvm": 25},
                  {"channel": "preview", "version": "4.1.2-SNAPSHOT", "build": "27",
                   "downloadUrl": "https://fill-data.papermc.io/v1/objects/cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc/v.jar",
                   "sha256": "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                   "image": "eclipse-temurin:25-jre", "jvm": 25}]}
                """);

        List<VelocitySystemHarness.RuntimeProfile> runtimes =
                VelocitySystemHarness.loadManifestRuntimes(manifest);

        assertEquals(List.of("stable", "preview"),
                runtimes.stream().map(VelocitySystemHarness.RuntimeProfile::name).toList());
    }

    @Test
    void rejectsNonFillDownloadUrl() throws Exception {
        Path manifest = writeManifest(validManifest().replace("fill-data.papermc.io", "example.com"));

        assertThrows(IllegalStateException.class,
                () -> VelocitySystemHarness.loadManifestRuntimes(manifest));
    }

    @Test
    void rejectsBadChecksum() throws Exception {
        Path manifest = writeManifest(validManifest().replace(
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "not-a-hash"));

        assertThrows(IllegalStateException.class,
                () -> VelocitySystemHarness.loadManifestRuntimes(manifest));
    }

    @Test
    void rejectsWrongSchema() throws Exception {
        Path manifest = writeManifest("schema: something-else\nruntimes: []\n");

        assertThrows(IllegalStateException.class,
                () -> VelocitySystemHarness.loadManifestRuntimes(manifest));
    }
}
