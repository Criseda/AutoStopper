package me.criseda.autostopper.messages;

import java.util.regex.Pattern;

/**
 * Project links shown in chat. Documentation and changelog links are pinned to the running release tag
 * so they match the installed plugin; development builds fall back to {@code master}.
 *
 * <p>The public project links (docs, GitHub, Modrinth, changelog, release notes) may be shown to anyone.
 * Troubleshooting links are operator-only and must never reach player connection messages.
 */
public final class ProjectLinks {
    static final String REPOSITORY = "https://github.com/Criseda/AutoStopper";
    static final String MODRINTH = "https://modrinth.com/plugin/autostopper";
    static final String README_PATH = "README.md";
    static final String CHANGELOG_PATH = "CHANGELOG.md";
    static final String TROUBLESHOOTING_PATH = "docs/troubleshooting.md";

    private static final Pattern RELEASE_VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");

    private ProjectLinks() {
    }

    public static String repository() {
        return REPOSITORY;
    }

    public static String modrinth() {
        return MODRINTH;
    }

    public static String documentation(String pluginVersion) {
        return pinned(pluginVersion, README_PATH);
    }

    public static String changelog(String pluginVersion) {
        return pinned(pluginVersion, CHANGELOG_PATH);
    }

    /** Release notes for the running release, or the releases list for development builds. */
    public static String releaseNotes(String pluginVersion) {
        if (isRelease(pluginVersion)) {
            return REPOSITORY + "/releases/tag/" + pluginVersion;
        }
        return REPOSITORY + "/releases";
    }

    public static String troubleshooting(String pluginVersion) {
        return pinned(pluginVersion, TROUBLESHOOTING_PATH);
    }

    public static String troubleshooting(String pluginVersion, String anchor) {
        return troubleshooting(pluginVersion) + "#" + anchor;
    }

    public static String getHelp() {
        return REPOSITORY + "/issues/new/choose";
    }

    static String documentationRef(String pluginVersion) {
        return isRelease(pluginVersion) ? pluginVersion : "master";
    }

    private static String pinned(String pluginVersion, String path) {
        return REPOSITORY + "/blob/" + documentationRef(pluginVersion) + "/" + path;
    }

    private static boolean isRelease(String pluginVersion) {
        return pluginVersion != null && RELEASE_VERSION.matcher(pluginVersion).matches();
    }
}
