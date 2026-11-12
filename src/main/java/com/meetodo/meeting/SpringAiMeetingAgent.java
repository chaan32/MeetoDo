package com.meetodo.meeting;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import com.meetodo.tools.CreateTaskTool;
import com.meetodo.tools.FindMemberTool;
import com.meetodo.tools.FindTasksTool;
import com.meetodo.tools.ToolExecutionService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Profile("(local | docker) & openai & !test")
public class SpringAiMeetingAgent implements MeetingAgent {

    public static final int MAX_TOOL_CALLS = 20;
    public static final int MAX_LLM_CALLS = MAX_TOOL_CALLS + 1;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final Object[] toolObjects;
    private final Clock clock;
    private final ToolExecutionService execution;

    public SpringAiMeetingAgent(ChatModel chatModel, ToolCallingManager toolCallingManager,
                                FindMemberTool findMember, FindTasksTool findTasks,
                                CreateTaskTool createTask, Clock clock,
                                ToolExecutionService execution) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.toolObjects = new Object[]{findMember, findTasks, createTask};
        this.clock = clock;
        this.execution = execution;
    }

    @Override
    public AgentResult process(Meeting meeting) {
        var options = ToolCallingChatOptions.builder()
                .toolCallbacks(ToolCallbacks.from(toolObjects))
                .toolContext(Map.of(ToolExecutionService.MEETING_CONTEXT_KEY, meeting))
                .internalToolExecutionEnabled(false)
                .build();
        String systemPrompt = systemPrompt(LocalDate.now(clock.withZone(SEOUL)));
        Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(meeting.getRawText())), options);
        int llmCalls = 0;
        int toolCalls = 0;

        while (llmCalls < MAX_LLM_CALLS) {
            ChatResponse response;
            llmCalls++;
            try {
                response = chatModel.call(prompt);
            } catch (Exception exception) {
                throw failure("LLM_ERROR", llmCalls, toolCalls);
            }

            AssistantMessage assistant = response == null || response.getResult() == null
                    ? null : response.getResult().getOutput();
            if (assistant == null) throw failure("EMPTY_LLM_RESPONSE", llmCalls, toolCalls);
            if (!assistant.hasToolCalls()) {
                if (!StringUtils.hasText(assistant.getText()))
                    throw failure("EMPTY_LLM_RESPONSE", llmCalls, toolCalls);
                return new AgentResult(assistant.getText().trim(), llmCalls, toolCalls,
                        execution.validationFailureCount(meeting.getId()));
            }

            int requestedTools = assistant.getToolCalls().size();
            if (toolCalls + requestedTools > MAX_TOOL_CALLS || llmCalls >= MAX_LLM_CALLS)
                throw failure("TOOL_CALL_LIMIT_EXCEEDED", llmCalls, toolCalls);

            try {
                ToolExecutionResult toolResult = toolCallingManager.executeToolCalls(prompt, response);
                toolCalls += requestedTools;
                prompt = new Prompt(toolResult.conversationHistory(), options);
            } catch (Exception exception) {
                throw failure("TOOL_EXECUTION_FAILED", llmCalls, toolCalls);
            }
        }
        throw failure("TOOL_CALL_LIMIT_EXCEEDED", llmCalls, toolCalls);
    }

    private MeetingAgentException failure(String code, int llmCalls, int toolCalls) {
        return new MeetingAgentException(code, llmCalls, toolCalls);
    }

    private String systemPrompt(LocalDate today) {
        return """
                너는 회의록에서 실행 가능한 할 일을 정리하는 업무 보조 에이전트다.
                오늘 날짜는 %s (Asia/Seoul)이다. 상대 날짜는 이 날짜를 기준으로 계산하고, 마감일은 yyyy-MM-dd로 전달한다.
                주간 표현은 월요일부터 일요일까지로 해석한다. "이번 주 안에"는 이번 주 일요일, "다음 주 금요일"은 다음 주 금요일로 계산한다.
                회의에서 명시적으로 결정되거나 요청된 작업만 추출한다. 담당자나 마감일을 추측하지 않는다.
                title은 1~200자의 짧은 명사형 업무 이름으로 만든다. 담당자 이름, 마감일, '~한다/~할 것' 같은 문장 어미와 끝 마침표를 제목에 넣지 않는다.
                좋은 예: 'API 명세 정리', '배포 로그 정리', '로그인 API 오류 수정'. 나쁜 예: '배포 로그를 정리한다.', '민수는 API 명세를 정리할 것'.
                예를 들어 '민수는 API 명세를 정리한다.'는 title='API 명세 정리'로, '해찬은 배포 로그를 정리한다.'는 title='배포 로그 정리'로 추출한다.
                findTasks와 createTask에는 같은 명사형 제목을 사용한다. INVALID_TITLE_FORMAT이면 거절된 문장을 그대로 반복하지 말고 업무 대상과 명사형 동작으로 제목을 고쳐 재시도한다.
                findMember의 name에는 호칭·직급·조사를 뺀 이름만 넣으세요. 예: '지영 대리는' → '지영', '민수 팀장님이' → '민수'.
                회의록에 있는 이름만 사용한다. 성이나 이름을 만들어 붙이지 않으며, 서버는 님·씨 한 개만 제거하고 직급·조사를 제거하지 않는다.
                각 할 일마다 먼저 findMember로 담당자를 확인하고, findTasks로 모든 회의의 같은 담당자·정규화 제목 TODO를 확인한 뒤,
                중복이 없을 때 createTask로 등록한다. EXISTING 결과가 오면 새 작업으로 세지 말고 기존 작업을 사용한다.
                회의록에 팀 이름이 실제로 언급되면 처음 findMember 호출부터 team을 함께 전달한다. 팀을 추측하지 않는다. 팀 조건이 없으면 team 필드를 생략한다. 빈 문자열·공백은 잘못된 입력이다.
                팀 이름은 대소문자·공백·팀 접미사만 정규화한다. 역할이나 업무로 팀 이름을 만들어 조회하지 않는다.
                TEAM_NOT_IN_TRANSCRIPT는 회의록에 없는 팀으로 조회했다는 뜻이다. 다중 후보의 ID는 정보일 뿐 배정 권한이 아니다. 단일 후보로 확인되지 않은 동명이인은 unassigned:AMBIGUOUS로 처리한다.
                팀 없이 조회해서 후보가 여러 명이면, 회의록에 팀이 있을 때 team을 넣어 재조회한다. 없으면 assignee=unassigned:AMBIGUOUS로 등록하고 '동명이인 확인 필요'를 알린다.
                후보가 한 명으로 확정되면 createTask의 assignee에는 member:<id> 하나만 전달한다. assigneeId, assigneeName, unassignedBecauseAmbiguous는 createTask 인자가 아니다.
                담당자 미정으로 findTasks를 조회할 때는 ID와 이름을 비우고 unassignedReason에 AMBIGUOUS / NOT_FOUND / TEAM_MISMATCH / NONE을 전달해 EXISTING 결과에도 사유와 경고를 유지한다.
                미등록 이름은 unassigned:NOT_FOUND ('등록되지 않은 담당자'), 담당자 언급이 없으면 unassigned:NONE을 사용한다.
                팀 불일치(MEMBER_TEAM_NOT_FOUND)는 다른 팀의 같은 이름 팀원과 동일인인지 알 수 없으므로 unassigned:TEAM_MISMATCH로 처리하고 팀 불일치 확인 필요 경고를 남긴다. team을 생략해 다시 조회하더라도 배정 근거로 사용하지 않는다.
                배정과 미정을 동시에 요청하지 않는다. 오류를 고칠 때 올바른 후보 ID를 무조건 버리지 말고 회의록의 팀을 다시 확인한다.
                미정 사유 AMBIGUOUS / NOT_FOUND / TEAM_MISMATCH로 createTask를 호출할 때는 해당 findMember 결과의 lookupCallOrder 값을 그대로 전달한다. 이름·팀·후보를 별도 인자로 만들지 않는다. NONE은 조회 참조가 필요 없다.
                createTask가 오류를 반환하면 설명을 반영해 제한된 범위에서 수정해 재시도한다. 경고가 있는 결과는 최종 답변에 그대로 설명한다.
                최종 답변에는 짧은 요약, 등록한 작업·담당자·마감일, 담당자 미정 항목, 마감일 경고를 포함한다.
                notifySlack은 사용할 수 없으며 호출하지 않는다.
                """.formatted(today);
    }
}
