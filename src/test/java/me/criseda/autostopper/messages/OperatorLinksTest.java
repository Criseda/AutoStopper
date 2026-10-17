package me.criseda.autostopper.messages;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OperatorLinksTest {

    @Test
    void releaseVersionsPinTheDocumentationTag() {
        assertEquals("https://github.com/Criseda/AutoStopper/blob/2.1.1/docs/troubleshooting.md#start_failed",
                OperatorLinks.troubleshooting("2.1.1", "start_failed"));
    }

    @Test
    void developmentAndUnknownVersionsFallBackToMaster() {
        assertEquals("master", OperatorLinks.documentationRef("2.2.0-SNAPSHOT"));
        assertEquals("master", OperatorLinks.documentationRef("Unknown"));
        assertEquals("master", OperatorLinks.documentationRef(null));
        assertEquals("https://github.com/Criseda/AutoStopper/blob/master/docs/troubleshooting.md",
                OperatorLinks.troubleshooting("Unknown"));
    }

    @Test
    void getHelpOpensTheIssueChooser() {
        assertEquals("https://github.com/Criseda/AutoStopper/issues/new/choose", OperatorLinks.getHelp());
    }
}
