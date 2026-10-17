package me.criseda.autostopper.messages;

import java.util.regex.Pattern;

/**
 * Repository links shown to operators. Documentation links are pinned to the running release tag so
 * the guide matches the installed plugin; development builds fall back to {@code master}. Players
 * never receive these links.
 */
public final class OperatorLinks {
    static final String REPOSITORY = "https://github.com/Criseda/AutoStopper";
    static final String TROUBLESHOOTING_PATH = "docs/troubleshooting.md";

    private static final Pattern RELEASE_VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");

    private OperatorLinks() {
    }

    public static String troubleshooting(String pluginVersion) {
        return REPOSITORY + "/blob/" + documentationRef(pluginVersion) + "/" + TROUBLESHOOTING_PATH;
    }

    public static String troubleshooting(String pluginVersion, String anchor) {
        return troubleshooting(pluginVersion) + "#" + anchor;
    }

    public static String getHelp() {
        return REPOSITORY + "/issues/new/choose";
    }

    static String documentationRef(String pluginVersion) {
        if (pluginVersion != null && RELEASE_VERSION.matcher(pluginVersion).matches()) {
            return pluginVersion;
        }
        return "master";
    }
}
