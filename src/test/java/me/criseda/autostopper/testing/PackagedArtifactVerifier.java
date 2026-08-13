package me.criseda.autostopper.testing;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Verifies contracts that only exist in the final shaded plugin JAR. */
public final class PackagedArtifactVerifier {
    private static final int JAVA_21_CLASS_MAJOR_VERSION = 65;
    private static final String RELOCATED_SNAKEYAML_PREFIX =
            "me/criseda/autostopper/internal/snakeyaml/";
    private static final String SNAKEYAML_METADATA = "META-INF/maven/org.yaml/snakeyaml/pom.properties";
    private static final String UNRELOCATED_SNAKEYAML_PREFIX = "org/yaml/snakeyaml/";
    private static final String[] VELOCITY_PROVIDED_PREFIXES = {
        "com/google/inject/",
        "com/velocitypowered/",
        "net/kyori/adventure/",
        "org/slf4j/"
    };

    private PackagedArtifactVerifier() {
    }

    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 4) {
            throw new IllegalArgumentException(
                    "Expected arguments: <plugin-jar> <project-version> <plugin-main-class> <snakeyaml-version>");
        }

        Path artifact = Path.of(arguments[0]);
        String expectedVersion = arguments[1];
        String expectedPluginMain = arguments[2];
        String expectedSnakeYamlVersion = arguments[3];

        try (JarFile jar = new JarFile(artifact.toFile())) {
            verifyUniqueEntries(jar);
            verifyManifest(jar.getManifest(), expectedVersion);
            verifyPluginDescriptor(jar, expectedVersion, expectedPluginMain);
            verifyClassFileTarget(jar, expectedPluginMain);
            verifySnakeYamlRelocation(jar, expectedSnakeYamlVersion);
            verifyVelocityProvidedLibrariesAreAbsent(jar);
            verifyNoUnexpectedClasses(jar);
        }
    }

    private static void verifyUniqueEntries(JarFile jar) {
        Set<String> entries = new HashSet<>();
        jar.stream()
                .map(JarEntry::getName)
                .filter(name -> !entries.add(name))
                .findFirst()
                .ifPresent(name -> {
                    throw new IllegalStateException("Packaged plugin JAR contains duplicate entry " + name);
                });
    }

    private static void verifyClassFileTarget(JarFile jar, String expectedPluginMain) throws IOException {
        String classEntry = expectedPluginMain.replace('.', '/') + ".class";
        try (DataInputStream input = new DataInputStream(jar.getInputStream(requireEntry(jar, classEntry)))) {
            if (input.readInt() != 0xCAFEBABE) {
                throw new IllegalStateException(classEntry + " is not a valid class file");
            }
            input.readUnsignedShort();
            int majorVersion = input.readUnsignedShort();
            if (majorVersion != JAVA_21_CLASS_MAJOR_VERSION) {
                throw new IllegalStateException(classEntry + " targets class-file major version " + majorVersion
                        + ", expected Java 21 major version " + JAVA_21_CLASS_MAJOR_VERSION);
            }
        }
    }

    private static void verifySnakeYamlRelocation(JarFile jar, String expectedVersion) throws IOException {
        requireEntry(jar, RELOCATED_SNAKEYAML_PREFIX + "Yaml.class");
        rejectEntryNamespace(jar, UNRELOCATED_SNAKEYAML_PREFIX);

        Properties metadata = new Properties();
        try (InputStream input = jar.getInputStream(requireEntry(jar, SNAKEYAML_METADATA))) {
            metadata.load(input);
        }
        String packagedVersion = metadata.getProperty("version");
        if (!expectedVersion.equals(packagedVersion)) {
            throw new IllegalStateException(
                    "Packaged SnakeYAML version is " + packagedVersion + ", expected " + expectedVersion);
        }
    }

    private static void verifyVelocityProvidedLibrariesAreAbsent(JarFile jar) {
        for (String prefix : VELOCITY_PROVIDED_PREFIXES) {
            rejectEntryPrefix(jar, prefix);
        }
    }

    private static void verifyNoUnexpectedClasses(JarFile jar) {
        jar.stream()
                .map(JarEntry::getName)
                .filter(name -> name.endsWith(".class"))
                .filter(name -> !name.startsWith("me/criseda/autostopper/"))
                .findFirst()
                .ifPresent(name -> {
                    throw new IllegalStateException("Packaged plugin JAR contains unexpected class " + name);
                });
    }

    private static void rejectEntryPrefix(JarFile jar, String prefix) {
        jar.stream()
                .map(JarEntry::getName)
                .filter(name -> name.startsWith(prefix))
                .findFirst()
                .ifPresent(name -> {
                    throw new IllegalStateException("Packaged plugin JAR must not contain " + name);
                });
    }

    private static void rejectEntryNamespace(JarFile jar, String namespace) {
        jar.stream()
                .map(JarEntry::getName)
                .filter(name -> name.startsWith(namespace) || name.contains("/" + namespace))
                .findFirst()
                .ifPresent(name -> {
                    throw new IllegalStateException("Packaged plugin JAR must not contain " + name);
                });
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
