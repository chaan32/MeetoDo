package com.meetodo.task;

import com.meetodo.task.exception.InvalidTaskTitleException;

public final class TaskTitles {
    private static final int MAX_LENGTH = 200;
    private static final String FORMAT_MESSAGE = "제목은 명사형 업무 이름으로 넣으세요. 예: '배포 로그를 정리한다.' → '배포 로그 정리'";

    private TaskTitles() { }

    public static String validateAndClean(String title) {
        String trimmed = title == null ? "" : title.strip();
        if (trimmed.isBlank()) throw new InvalidTaskTitleException("INVALID_TITLE", "제목을 입력해야 합니다.");
        if (trimmed.length() > MAX_LENGTH)
            throw new InvalidTaskTitleException("TITLE_TOO_LONG", "제목은 200자 이하여야 합니다.");
        String spaced = collapseWhitespace(trimmed);
        String ending = spaced.replaceFirst("[.!? ]+$", "");
        // shortcut: explicit endings can reject nouns such as 람다; use morphological analysis only if this limit needs changing.
        if (ending.endsWith("다") || spaced.matches(".*함[. ]*\\.[. ]*$")
                || ending.endsWith("할 것") || ending.endsWith("할것"))
            throw new InvalidTaskTitleException("INVALID_TITLE_FORMAT", FORMAT_MESSAGE);
        String cleaned = clean(spaced);
        if (cleaned.isBlank()) throw new InvalidTaskTitleException("INVALID_TITLE", "제목을 입력해야 합니다.");
        return cleaned;
    }

    public static String clean(String title) {
        return title == null ? "" : collapseWhitespace(title).replaceFirst("[. ]+$", "");
    }

    private static String collapseWhitespace(String title) {
        return title.replaceAll("(?U)\\s+", " ").strip();
    }
}
