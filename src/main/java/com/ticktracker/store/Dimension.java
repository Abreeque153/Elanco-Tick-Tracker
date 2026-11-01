package com.ticktracker.store;

import com.ticktracker.error.InvalidRequestException;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** A field sightings can be grouped by. The column names are constants, never user input. */
public enum Dimension {
    CITY("city"),
    REGION("region"),
    SPECIES("species");

    private final String column;

    Dimension(String column) {
        this.column = column;
    }

    public String column() {
        return column;
    }

    /** Parses "region", "City" and so on; null or blank returns null. */
    public static Dimension parse(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("none")) {
            return null;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            String allowed = Arrays.stream(values()).map(d -> d.column).collect(Collectors.joining(", "));
            throw new InvalidRequestException("Unknown grouping '" + value + "'. Use one of: " + allowed);
        }
    }
}
