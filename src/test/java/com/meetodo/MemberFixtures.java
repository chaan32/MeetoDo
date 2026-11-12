package com.meetodo;

/** Explicit sample data mapping for dynamic test fixtures; never infers a surname. */
public final class MemberFixtures {
    private MemberFixtures() {}
    public static String familyNameForSampleGivenName(String givenName) {
        return switch (givenName) {
            case "민수" -> "김";
            case "지영" -> "이";
            case "해찬" -> "전";
            default -> "";
        };
    }
}
