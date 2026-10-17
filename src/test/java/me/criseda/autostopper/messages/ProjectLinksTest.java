package me.criseda.autostopper.messages;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectLinksTest {

    @Test
    void releaseVersionsPinTheDocumentationTag() {
        assertEquals("https://github.com/Criseda/AutoStopper/blob/2.1.1/docs/troubleshooting.md#start_failed",
                ProjectLinks.troubleshooting("2.1.1", "start_failed"));
    }

    @Test
    void developmentAndUnknownVersionsFallBackToMaster() {
        assertEquals("master", ProjectLinks.documentationRef("2.2.0-SNAPSHOT"));
        assertEquals("master", ProjectLinks.documentationRef("Unknown"));
        assertEquals("master", ProjectLinks.documentationRef(null));
        assertEquals("https://github.com/Criseda/AutoStopper/blob/master/docs/troubleshooting.md",
                ProjectLinks.troubleshooting("Unknown"));
    }

    @Test
    void publicLinksArePinnedToTheRunningRelease() {
        assertEquals("https://github.com/Criseda/AutoStopper/blob/2.1.1/README.md", ProjectLinks.documentation("2.1.1"));
        assertEquals("https://github.com/Criseda/AutoStopper/blob/2.1.1/CHANGELOG.md", ProjectLinks.changelog("2.1.1"));
        assertEquals("https://github.com/Criseda/AutoStopper/releases/tag/2.1.1", ProjectLinks.releaseNotes("2.1.1"));
        assertEquals("https://github.com/Criseda/AutoStopper", ProjectLinks.repository());
        assertEquals("https://modrinth.com/plugin/autostopper", ProjectLinks.modrinth());
    }

    @Test
    void developmentBuildsLinkMasterAndTheReleaseList() {
        assertEquals("https://github.com/Criseda/AutoStopper/blob/master/README.md",
                ProjectLinks.documentation("2.2.0-SNAPSHOT"));
        assertEquals("https://github.com/Criseda/AutoStopper/blob/master/CHANGELOG.md",
                ProjectLinks.changelog("Unknown"));
        assertEquals("https://github.com/Criseda/AutoStopper/releases", ProjectLinks.releaseNotes("2.2.0-SNAPSHOT"));
        assertEquals("https://github.com/Criseda/AutoStopper/releases", ProjectLinks.releaseNotes(null));
    }

    @Test
    void linkedRepositoryFilesExist() {
        assertTrue(Files.isRegularFile(Path.of(ProjectLinks.README_PATH)));
        assertTrue(Files.isRegularFile(Path.of(ProjectLinks.CHANGELOG_PATH)));
        assertTrue(Files.isRegularFile(Path.of(ProjectLinks.TROUBLESHOOTING_PATH)));
    }

    @Test
    void getHelpOpensTheIssueChooser() {
        assertEquals("https://github.com/Criseda/AutoStopper/issues/new/choose", ProjectLinks.getHelp());
    }
}
