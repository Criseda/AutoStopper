package me.criseda.autostopper.docker;

import java.time.Duration;
import java.util.List;

public interface CommandRunner {
    CommandOutput run(List<String> command, Duration timeout);
}