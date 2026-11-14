package dev.underworld;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;

/** Small text helpers (legacy colour codes, wrapping). */
final class Txt {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private Txt() { }

    static Component c(String s) {
        return LEGACY.deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    static List<Component> lore(List<String> lines) {
        List<Component> out = new ArrayList<>();
        for (String l : lines) out.add(c(l));
        return out;
    }

    /** Wraps text to lines of at most width characters, each prefixed with the colour code. */
    static List<String> wrap(String text, int width, String prefix) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() + word.length() + 1 > width && line.length() > 0) {
                out.add(prefix + line);
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) out.add(prefix + line);
        return out;
    }
}
