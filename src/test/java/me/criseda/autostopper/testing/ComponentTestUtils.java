package me.criseda.autostopper.testing;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

public final class ComponentTestUtils {
    private ComponentTestUtils() {
    }

    public static String plainText(Component component) {
        StringBuilder result = new StringBuilder();
        appendPlainText(component, result);
        return result.toString();
    }

    private static void appendPlainText(Component component, StringBuilder result) {
        if (component instanceof TextComponent text) {
            result.append(text.content());
        }
        for (Component child : component.children()) {
            appendPlainText(child, result);
        }
    }
}
