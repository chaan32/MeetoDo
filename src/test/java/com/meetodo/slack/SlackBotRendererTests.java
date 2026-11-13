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
            Task task = Task.create("작업 " + i, new Member("김", "민수", "개발", "U123"), null);
            ReflectionTestUtils.setField(task, "id", (long)i + 1); task.changeStatus(TaskStatus.DONE); return task;
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

    @Test void directMessageKeepsOriginalChannelLinkWithinBlockBudget() {
        // DM도 같은 길이 제한을 지키며 원래 채널 링크를 생략하지 않는다.
        var result = renderer.renderDirectMessage(message, java.util.Collections.nCopies(100, task("U123ABC")), java.util.Map.of(),
                "https://meetodo.slack.com/archives/C1/p123");
        assertThat(result.blocks()).hasSizeLessThanOrEqualTo(50);
        assertThat(result.text()).hasSizeLessThanOrEqualTo(40000).contains("새 할 일이 배정됐어요", "외 ", "<https://meetodo.slack.com/archives/C1/p123|원래 채널 메시지>");
    }
    @Test void directMessageKeepsDetailedWarningAndDueDate() {
        // DM 조립도 회의 결과의 상세 경고와 마감일 없음 표시를 유지한다.
        Task task = Task.create("자료 준비", null, null);
        ReflectionTestUtils.setField(task, "id", 7L);
        var detail = com.meetodo.meeting.dto.MeetingTaskResponse.from(task,"CREATED",1,"동명이인 확인 필요: 지영", "AMBIGUOUS");
        var result = renderer.renderDirectMessage(message,List.of(task),java.util.Map.of(7L,detail),
                "https://meetodo.slack.com/archives/C1/p123");
        assertThat(result.text()).contains("동명이인 확인 필요: 지영", "마감일 없음");
    }

    private Task unresolved(String reason, int count) {
        var candidates = java.util.stream.IntStream.range(0, count).mapToObj(i ->
                new com.meetodo.task.UnresolvedAssignee.Candidate((long)i + 10, "지영", "팀" + i)).toList();
        Task task = Task.create("담당 확인", null, null, null,
                new com.meetodo.task.UnresolvedAssignee(reason, "지영", null, candidates, 0));
        ReflectionTestUtils.setField(task, "id", 7L); return task;
    }
    @Test void threeCandidatesUseButtonsWithTaskAndMemberIds() {
        // 저장된 후보 세 명까지 업무와 회원 ID를 함께 넣은 선택 버튼을 표시한다.
        String json = renderer.render(message, List.of(unresolved("AMBIGUOUS", 3))).blocksJson();
        assertThat(json).contains("meetodo_assign_candidate", "7:10", "7:12").doesNotContain("static_select");
    }
    @Test void fourCandidatesUseStaticSelectAndTaskBlockId() {
        // 후보가 네 명이면 블록 ID에 업무를 담고 회원 ID를 옵션 값으로 사용한다.
        String json = renderer.render(message, List.of(unresolved("TEAM_MISMATCH", 4))).blocksJson();
        assertThat(json).contains("static_select", "meetodo_assign_select", "meetodo_assign_task:7", "\"value\":\"13\"");
    }
    @Test void missingMemberUsesUsersSelect() {
        // 등록된 후보가 없는 미정 업무는 Slack 사용자 선택으로 지정한다.
        assertThat(renderer.render(message, List.of(unresolved("NOT_FOUND", 0))).blocksJson())
                .contains("users_select", "meetodo_assign_user", "meetodo_assign_task:7");
    }
    @Test void completedUnassignedTaskHasNoAssignmentControls() {
        // 완료된 미정 업무는 담당 지정 UI를 다시 노출하지 않는다.
        Task task = unresolved("AMBIGUOUS", 3); task.changeStatus(TaskStatus.DONE);
        assertThat(renderer.render(message, List.of(task)).blocksJson()).doesNotContain("meetodo_assign_");
    }
    @Test void assignmentBlocksCountTowardFiftyBlockLimit() {
        // 업무 본문과 담당 지정 블록을 모두 세어 Slack 상한을 지킨다.
        var rendered = renderer.render(message, java.util.Collections.nCopies(100, unresolved("AMBIGUOUS", 3)));
        assertThat(rendered.blocks()).hasSizeLessThanOrEqualTo(50);
        assertThat(rendered.text()).contains("외 ").hasSizeLessThanOrEqualTo(40000);
    }

    @Test void taskMenuOffersTitleDueAndDeleteForTodoTask() {
        // 미완료 업무는 제목·마감일 수정과 삭제 메뉴를 업무 ID와 함께 제공한다.
        String json = renderer.render(message, List.of(task("U123ABC"))).blocksJson();
        assertThat(json).contains("overflow", "meetodo_task_menu", "title:7", "due:7", "delete:7").doesNotContain("reopen:7");
    }

    @Test void completedTaskMenuIncludesReopenWithoutCompletionOrAssignment() {
        // 완료 업무의 메뉴에는 재개가 있고 완료·담당 지정 버튼은 없다.
        Task task = unresolved("AMBIGUOUS", 3); task.changeStatus(TaskStatus.DONE);
        String json = renderer.render(message, List.of(task)).blocksJson();
        assertThat(json).contains("overflow", "title:7", "due:7", "delete:7", "reopen:7")
                .doesNotContain(SlackBotRenderer.COMPLETE_ACTION, "meetodo_assign_");
    }

    @Test void deletedTaskRendersTombstoneWithoutAnyActions() {
        // 삭제 이력은 본문에 남지만 수정·재개·완료·담당 지정 동작은 모두 사라진다.
        Task task = unresolved("AMBIGUOUS", 3); task.softDelete();
        var rendered = renderer.render(message, List.of(task));
        assertThat(rendered.text()).contains("🗑 삭제됨 · 담당 확인");
        assertThat(rendered.blocksJson()).doesNotContain("accessory", "overflow", "\"type\":\"actions\"",
                SlackBotRenderer.TASK_MENU_ACTION, SlackBotRenderer.COMPLETE_ACTION, "meetodo_assign_");
    }

    @Test void todoMenusAndCompletionActionsFitExactlyFiftyBlocks() {
        // 23개 미완료 업무는 메뉴가 있는 본문 23개와 액션 23개를 포함해 정확히 50블록이다.
        var boundaryMessage = new NotificationMessage(1L, "회의", List.of("요약 1", "요약 2"), List.of(), "회의 1");
        var rendered = renderer.render(boundaryMessage, java.util.Collections.nCopies(23, task("U123ABC")));
        assertThat(rendered.blocks()).hasSize(50);
        assertThat(rendered.blocks().stream().filter(block -> block.containsKey("accessory"))).hasSize(23);
        assertThat(rendered.text()).doesNotContain("외 ");
    }

    @Test void overflowingTodoMenusReserveOmissionBlock() {
        // 24번째 업무가 상한을 넘으면 생략 안내까지 포함해 50블록 아래로 줄인다.
        var boundaryMessage = new NotificationMessage(1L, "회의", List.of("요약 1", "요약 2"), List.of(), "회의 1");
        var rendered = renderer.render(boundaryMessage, java.util.Collections.nCopies(24, task("U123ABC")));
        assertThat(rendered.blocks()).hasSize(49);
        assertThat(rendered.blocks().stream().filter(block -> block.containsKey("accessory"))).hasSize(22);
        assertThat(rendered.text()).contains("외 2건");
    }

    // Home은 긴 미정 후보 경고가 있어도 section 한도를 넘기지 않는다.
    @Test void homeTaskBlocksBoundLongUnresolvedCandidateWarning() {
        var candidates=java.util.stream.IntStream.range(0,20)
                .mapToObj(i -> new com.meetodo.task.UnresolvedAssignee.Candidate((long)i,"이름"+i+"가".repeat(95),"팀"+i+"가".repeat(95))).toList();
        var detail=new com.meetodo.task.UnresolvedAssignee("TEAM_MISMATCH","해찬","프론트엔드팀",candidates,0);
        var task=com.meetodo.task.Task.create("로그 확인",null,null,null,detail);
        org.springframework.test.util.ReflectionTestUtils.setField(task,"id",7L);
        var blocks=new SlackBotRenderer().taskBlocks(task);
        var text=(java.util.Map<?,?>)blocks.getFirst().get("text");
        assertThat(text.get("text").toString()).hasSizeLessThanOrEqualTo(3000);
    }

}
