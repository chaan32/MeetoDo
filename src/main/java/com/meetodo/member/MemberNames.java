package com.meetodo.member;

import java.util.List;

/** Shared exact identity lookup; team constraints are applied after identity resolution. */
public final class MemberNames {
    private MemberNames() {}

    public static List<Member> find(MemberRepository members, String input) {
        String name = input == null ? "" : input.trim();
        if (name.isEmpty()) return List.of();
        List<Member> found = exact(members, name);
        if (!found.isEmpty()) return found;
        String withoutHonorific = withoutHonorific(name);
        return withoutHonorific.isEmpty() || withoutHonorific.equals(name)
                ? List.of() : exact(members, withoutHonorific);
    }

    private static List<Member> exact(MemberRepository members, String name) {
        List<Member> full = members.findAllByNameOrderByIdAsc(name).stream().filter(Member::isActive).toList();
        return full.isEmpty() ? members.findAllByGivenNameOrderByIdAsc(name).stream().filter(Member::isActive).toList() : full;
    }

    public static boolean matches(Member member, String input) {
        String name = input == null ? "" : input.trim();
        return exactMatch(member, name) || exactMatch(member, withoutHonorific(name));
    }

    private static boolean exactMatch(Member member, String name) {
        return !name.isEmpty() && (member.getFullName().equals(name) || member.getGivenName().equals(name));
    }

    public static String withoutHonorific(String name) {
        return name.endsWith("님") || name.endsWith("씨") ? name.substring(0, name.length() - 1).trim() : name;
    }
}
