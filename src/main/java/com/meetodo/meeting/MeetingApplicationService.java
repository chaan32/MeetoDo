package com.meetodo.meeting;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import com.meetodo.meeting.dto.MeetingRequest;
import com.meetodo.meeting.dto.MeetingHistoryResponse;
import com.meetodo.meeting.dto.MeetingResponse;
import com.meetodo.meeting.dto.ToolCallLogResponse;
import com.meetodo.tools.ToolExecutionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class MeetingApplicationService {

    private final ObjectProvider<MeetingAgent> agents;
    private final MeetingRepository meetings;
    private final ToolExecutionService toolExecution;
    private final Clock clock;
    private final MeetingResults results;
    private final MeetingCompletionService completion;

    public MeetingApplicationService(ObjectProvider<MeetingAgent> agents, MeetingRepository meetings,
                                     ToolExecutionService toolExecution, Clock clock, MeetingResults results,
                                     MeetingCompletionService completion) {
        this.agents = agents; this.meetings = meetings; this.toolExecution = toolExecution;
        this.clock = clock; this.results = results; this.completion = completion;
    }

    public MeetingResponse submit(MeetingRequest request) {
        MeetingAgent agent = agents.getIfAvailable();
        if (agent == null) throw new MeetingAgentDisabledException();

        Meeting candidate = Meeting.create(request.rawText(), MeetingSource.API_TEXT);
        Optional<Meeting> previous = meetings.findByContentHash(candidate.getContentHash());
        if (previous.isPresent()) return results.get(previous.get().getId());

        Meeting meeting;
        try {
            meeting = meetings.saveAndFlush(candidate);
        } catch (DataIntegrityViolationException duplicateSubmission) {
            // A concurrent request may win the unique content-hash insert after our initial lookup.
            return meetings.findByContentHash(candidate.getContentHash())
                    .map(existing -> results.get(existing.getId()))
                    .orElseThrow(() -> duplicateSubmission);
        }

        meeting.startProcessing(Instant.now(clock));
        meeting = meetings.save(meeting);
        AgentResult result;
        try {
            result = agent.process(meeting);
        } catch (MeetingAgentException exception) {
            meeting = currentProcessingMeeting(meeting);
            meeting.fail(Instant.now(clock), exception.getLlmCallCount(), exception.getToolCallCount(),
                    toolExecution.validationFailureCount(meeting.getId()));
            completion.finish(meeting);
            throw new MeetingProcessingException(meeting.getId());
        } catch (Exception exception) {
            meeting = currentProcessingMeeting(meeting);
            meeting.fail(Instant.now(clock), 0, 0, toolExecution.validationFailureCount(meeting.getId()));
            completion.finish(meeting);
            throw new MeetingProcessingException(meeting.getId());
        }

        Meeting previousWithSameTaskSet = results.existingTaskSourceMeeting(meeting.getId());
        if (previousWithSameTaskSet != null) meeting.markDuplicateOf(previousWithSameTaskSet);

        meeting.complete(result.summary(), Instant.now(clock), result.llmCallCount(),
                result.toolCallCount(), result.validationFailureCount());
        completion.finish(meeting);
        return results.get(meeting.getId());
    }

    public MeetingResponse get(Long id) { return results.get(id); }
    public List<MeetingHistoryResponse> recent() { return results.recent(); }
    public List<ToolCallLogResponse> logs(Long id) { return results.logs(id); }

    private Meeting currentProcessingMeeting(Meeting meeting) {
        Meeting current = meetings.findById(meeting.getId()).orElseThrow(MeetingNotFoundException::new);
        if (current.getStatus() != MeetingStatus.PROCESSING) {
            throw new IllegalStateException("Meeting processing state changed unexpectedly: " + current.getStatus());
        }
        return current;
    }
}
