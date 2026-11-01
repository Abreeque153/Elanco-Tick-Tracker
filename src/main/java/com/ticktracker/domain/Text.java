package com.ticktracker.domain;

import java.util.Locale;

/** Small text-normalisation helpers shared by the cleaner and the query parser. */
public final class Text {

    private Text() {
    }

    /** Trims, collapses inner whitespace, and returns null for blank input. */
    public static String clean(String value) {
        if (value == null) {
            return null;
        }
        String collapsed = value.strip().replaceAll("\\s+", " ");
        return collapsed.isEmpty() ? null : collapsed;
    }

    /** Case- and whitespace-insensitive lookup key. */
    public static String key(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.toLowerCase(Locale.ROOT);
    }

    /** "  newCASTLE  upon tyne" becomes "Newcastle Upon Tyne". */
    public static String titleCase(String value) {
        String cleaned = clean(value);
        if (cleaned == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(cleaned.length());
        boolean startOfWord = true;
        for (char c : cleaned.toLowerCase(Locale.ROOT).toCharArray()) {
            out.append(startOfWord ? Character.toUpperCase(c) : c);
            startOfWord = c == ' ' || c == '-' || c == '/';
        }
        return out.toString();
    }
}
