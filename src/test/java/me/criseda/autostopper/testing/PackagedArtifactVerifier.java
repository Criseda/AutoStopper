package me.criseda.autostopper.testing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Verifies contracts that only exist in the final shaded plugin JAR. */
public final class PackagedArtifactVerifier {
    private PackagedArtifactVerifier() {
    }

    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 3) {
            throw new IllegalArgumentException(
                    "Expected arguments: <plugin-jar> <project-version> <plugin-main-class>");
        }

        Path artifact = Path.of(arguments[0]);
        String expectedVersion = arguments[1];
        String expectedPluginMain = arguments[2];

        try (JarFile jar = new JarFile(artifact.toFile())) {
            verifyManifest(jar.getManifest(), expectedVersion);
            verifyPluginDescriptor(jar, expectedVersion, expectedPluginMain);
            requireEntry(jar, expectedPluginMain.replace('.', '/') + ".class");
        }
    }

    private static void verifyManifest(Manifest manifest, String expectedVersion) {
        if (manifest == null) {
            throw new IllegalStateException("Packaged plugin JAR has no manifest");
        }

        Attributes attributes = manifest.getMainAttributes();
        String applicationMain = attributes.getValue(Attributes.Name.MAIN_CLASS);
        if (applicationMain != null) {
            throw new IllegalStateException(
                    "Velocity plugin JAR must not declare an application Main-Class: " + applicationMain);
        }

        String implementationVersion = attributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION);
        if (!expectedVersion.equals(implementationVersion)) {
            throw new IllegalStateException("Manifest Implementation-Version is " + implementationVersion
                    + ", expected " + expectedVersion);
        }
    }

    private static void verifyPluginDescriptor(
            JarFile jar, String expectedVersion, String expectedPluginMain) throws IOException {
        JarEntry descriptorEntry = requireEntry(jar, "velocity-plugin.json");
        String descriptor;
        try (InputStream input = jar.getInputStream(descriptorEntry)) {
            descriptor = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        requireJsonField(descriptor, "version", expectedVersion);
        requireJsonField(descriptor, "main", expectedPluginMain);
    }

    private static void requireJsonField(String json, String name, String expectedValue) {
        Pattern fieldPattern = Pattern.compile(
                "\\\"" + Pattern.quote(name) + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
        Matcher matcher = fieldPattern.matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("velocity-plugin.json has no string field named " + name);
        }
        if (!expectedValue.equals(matcher.group(1))) {
            throw new IllegalStateException("velocity-plugin.json " + name + " is " + matcher.group(1)
                    + ", expected " + expectedValue);
        }
        if (matcher.find()) {
            throw new IllegalStateException("velocity-plugin.json contains duplicate " + name + " fields");
        }
    }

    private static JarEntry requireEntry(JarFile jar, String name) {
        JarEntry entry = jar.getJarEntry(name);
        if (entry == null) {
            throw new IllegalStateException("Packaged plugin JAR is missing " + name);
        }
        return entry;
    }
}
