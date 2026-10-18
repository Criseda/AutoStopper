package me.criseda.autostopper.commands;

import java.util.Locale;
import java.util.Optional;

/**
 * The single definition of every {@code /autostopper} subcommand. Help, usage messages, tab completion,
 * permission checks, and fuzzy matching all read from here, so a description is written once.
 */
enum AutoStopperSubcommand {
    HELP("help", null, null, Argument.NONE, "Show this command list", null),
    STATUS("status", AutoStopperCommand.STATUS_PERMISSION, Group.GENERAL, Argument.OPTIONAL,
            "Show every mapped server, or one server in detail", "view AutoStopper status"),
    RELOAD("reload", AutoStopperCommand.RELOAD_PERMISSION, Group.GENERAL, Argument.NONE,
            "Reload and validate the configuration", "reload AutoStopper configuration"),
    START("start", AutoStopperCommand.START_PERMISSION, Group.LIFECYCLE, Argument.REQUIRED,
            "Start a mapped server and wait until it is ready", "start a server"),
    STOP("stop", AutoStopperCommand.STOP_PERMISSION, Group.LIFECYCLE, Argument.REQUIRED,
            "Stop a mapped server that has no players", "stop a server"),
    RESTART("restart", AutoStopperCommand.RESTART_PERMISSION, Group.LIFECYCLE, Argument.REQUIRED,
            "Restart a mapped server that has no players", "restart a server"),
    HOLD("hold", AutoStopperCommand.HOLD_PERMISSION, Group.HOLDS, Argument.REQUIRED,
            "Keep a server from stopping automatically", "hold a server"),
    RELEASE("release", AutoStopperCommand.RELEASE_PERMISSION, Group.HOLDS, Argument.REQUIRED,
            "Let a held server stop automatically again", "release a server hold");

    /** Whether a subcommand takes a mapped server name as its second argument. */
    enum Argument {
        NONE(""),
        REQUIRED(" <server>"),
        OPTIONAL(" [server]");

        private final String usageSuffix;

        Argument(String usageSuffix) {
            this.usageSuffix = usageSuffix;
        }
    }

    /** Help menu sections, in display order. */
    enum Group {
        GENERAL("General"),
        LIFECYCLE("Lifecycle"),
        HOLDS("Holds");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    private final String label;
    private final String permission;
    private final Group group;
    private final Argument argument;
    private final String description;
    private final String deniedAction;

    AutoStopperSubcommand(String label, String permission, Group group, Argument argument,
            String description, String deniedAction) {
        this.label = label;
        this.permission = permission;
        this.group = group;
        this.argument = argument;
        this.description = description;
        this.deniedAction = deniedAction;
    }

    static Optional<AutoStopperSubcommand> byLabel(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        for (AutoStopperSubcommand subcommand : values()) {
            if (subcommand.label.equals(lower)) {
                return Optional.of(subcommand);
            }
        }
        return Optional.empty();
    }

    String label() {
        return label;
    }

    /** The permission node, or {@code null} for public subcommands. */
    String permission() {
        return permission;
    }

    boolean isPublic() {
        return permission == null;
    }

    /** The help section, or {@code null} for subcommands not listed in help. */
    Group group() {
        return group;
    }

    Argument argument() {
        return argument;
    }

    /** Whether a server name may follow, so tab completion offers mapped servers. */
    boolean acceptsServer() {
        return argument != Argument.NONE;
    }

    String usage() {
        return "/autostopper " + label + argument.usageSuffix;
    }

    String description() {
        return description;
    }

    String deniedAction() {
        return deniedAction;
    }
}
