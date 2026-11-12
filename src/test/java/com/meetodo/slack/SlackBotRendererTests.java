package com.meetodo.slack;

import com.meetodo.member.Member;
import com.meetodo.notification.NotificationMessage;
import com.meetodo.task.Task;
import com.meetodo.task.TaskStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;

class SlackBotRendererTests {
    private final SlackBotRenderer renderer = new SlackBotRenderer();
    private final NotificationMessage message = new NotificationMessage(1L, "회의 완료", List.of("서버 집계"), List.of(), "회의 1");
    private Task task(String slackId) {
        Task task = Task.create("<@UATTACK> & *내용*", new Member("김", "민수", "개발", slackId), null);
        ReflectionTestUtils.setField(task, "id", 7L);
        return task;
    }
    @Test void mentionsOnlyValidatedRealSlackIds() {
        // 실제 Slack ID만 명시적인 멘션으로 렌더링한다.
        assertThat(renderer.render(message, List.of(task("U123ABC"))).blocksJson()).contains("<@U123ABC>").contains("meetodo_complete_task");
    }
    @Test void fakeIdsAndSourceMentionsAreEscaped() {
        // 샘플 ID와 업무 제목이 멘션이나 서식으로 실행되지 않는다.
        String json = renderer.render(message, List.of(task("U-DEV"))).blocksJson();
        assertThat(json).doesNotContain("<@U-DEV>", "<@UATTACK>", "*내용*").contains("&lt;@UATTACK&gt;", "&amp;");
    }
    @Test void completedTaskHasActorAndNoButton() {
        // 완료 업무는 원래 메시지에 남고 버튼만 사라진다.
        Task task = task("U123ABC");
        task.changeStatus(TaskStatus.DONE, "UACTOR", java.time.Instant.EPOCH);
        assertThat(renderer.render(message, List.of(task)).blocksJson()).contains("완료 (<@UACTOR>)").doesNotContain("meetodo_complete_task");
    }
    @Test void blockCountAndFallbackStayWithinSlackLimits() {
        // 큰 묶음은 분할 전송 없이 생략 수를 안내한다.
        var result = renderer.render(message, java.util.Collections.nCopies(100, task("U123ABC")));
        assertThat(result.blocks()).hasSizeLessThanOrEqualTo(50);
        assertThat(result.text()).hasSizeLessThanOrEqualTo(40000).contains("외 ");
    }
    @Test void oversizedTaskLineIsOmittedWhole() {
        // 합성 경계: 3000자를 넘는 업무는 중간을 자르지 않고 전체 생략한다.
        Task large = Task.create("가".repeat(3100), null, null);
        ReflectionTestUtils.setField(large, "id", 8L);
        var result = renderer.render(message, List.of(large));
        assertThat(result.text()).doesNotContain("가").contains("외 1건");
        assertThat(result.blocksJson()).doesNotContain(SlackBotRenderer.COMPLETE_ACTION);
    }

    @Test void allFittingFiftyBlocksDoNotReserveUnneededOmission() {
        // 마지막 항목까지 50블록 안에 들어가면 생략 문구 때문에 빼지 않는다.
        var tasks = java.util.stream.IntStream.range(0,47).mapToObj(i -> {
            Task task = Task.create("작업 " + i, null, null);
            ReflectionTestUtils.setField(task, "id", (long)i + 1); return task;
        }).toList();
        var result = renderer.render(message, tasks);
        assertThat(result.blocks()).hasSize(50);
        assertThat(result.text()).doesNotContain("외 ").contains("작업 46");
    }

    @Test void meetingWarningOverridesOriginalTaskSnapshotWithoutDuplication() {
        // 재사용 업무의 최초 미정 정보 대신 이번 회의의 조회 근거만 표시한다.
        var original = new com.meetodo.task.UnresolvedAssignee("NOT_FOUND", "준호", null, List.of(), 0);
        var current = new com.meetodo.task.UnresolvedAssignee("NOT_FOUND", "수진", null, List.of(), 0);
        Task task = Task.create("자료 준비", null, null, null, original);
        ReflectionTestUtils.setField(task, "id", 7L);
        var detail = com.meetodo.meeting.dto.MeetingTaskResponse.from(task,"EXISTING",1,current.warning(),current.reason(),current);
        var result = renderer.render(message, List.of(task), java.util.Map.of(7L, detail));
        assertThat(result.text()).contains("수진").doesNotContain("준호");
    }

}
