package com.meetodo.tools;

import java.util.Locale;

/** One lexical comparison rule for lookup, transcript evidence, and mismatch guards. */
final class TeamNames {
    private TeamNames() { }

    static String normalize(String team) {
        String value = compact(team);
        return value.endsWith("팀") ? value.substring(0, value.length() - 1) : value;
    }

    static boolean mentionedIn(String team, String transcript) {
        String normalized = normalize(team);
        // shortcut: substring presence does not link a team to a person; add contextual matching when needed.
        return !normalized.isEmpty() && compact(transcript).contains(normalized);
    }

    private static String compact(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("(?U)\\s+", "");
    }
}
