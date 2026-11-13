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

    public MeetingResponse submitFromSlack(MeetingRequest request, java.util.function.Consumer<Meeting> beforeSave,
                                          java.util.function.Consumer<Meeting> discardProgress) {
        return submit(request, MeetingSource.SLACK_TEXT, java.util.Objects.requireNonNull(beforeSave),
                java.util.Objects.requireNonNull(discardProgress));
    }

    public MeetingResponse submit(MeetingRequest request) {
        return submit(request, MeetingSource.API_TEXT, meeting -> { }, meeting -> { });
    }

    public MeetingResponse submitAudio(MeetingRequest request, String model, long transcriptionTimeMs) {
        return submit(request, MeetingSource.AUDIO, meeting -> meeting.recordTranscription(model, transcriptionTimeMs), meeting -> { });
    }

    private MeetingResponse submit(MeetingRequest request, MeetingSource source,
                                   java.util.function.Consumer<Meeting> beforeSave,
                                   java.util.function.Consumer<Meeting> discardProgress) {
        MeetingAgent agent = agents.getIfAvailable();
        if (agent == null) throw new MeetingAgentDisabledException();

        Meeting candidate = Meeting.create(request.rawText(), source);
        Optional<Meeting> previous = meetings.findByContentHash(candidate.getContentHash());
        if (previous.isPresent()) return results.get(previous.get().getId());

        // No Meeting/hash is persisted until Slack successfully posts the progress message.
        beforeSave.accept(candidate);
        Meeting meeting;
        try {
            candidate.startProcessing(Instant.now(clock));
            meeting = meetings.saveAndFlush(candidate);
        } catch (DataIntegrityViolationException duplicateSubmission) {
            discardProgress(candidate, discardProgress);
            // The unique hash still selects one processor across application instances.
            return meetings.findByContentHash(candidate.getContentHash())
                    .map(existing -> results.get(existing.getId()))
                    .orElseThrow(() -> duplicateSubmission);
        } catch (RuntimeException persistenceFailure) {
            discardProgress(candidate, discardProgress);
            throw persistenceFailure;
        }

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

    private void discardProgress(Meeting candidate, java.util.function.Consumer<Meeting> discard) {
        // Best-effort remote cleanup must not hide the database result or trigger another LLM run.
        try { discard.accept(candidate); }
        catch (RuntimeException ignored) { }
    }

    private Meeting currentProcessingMeeting(Meeting meeting) {
        Meeting current = meetings.findById(meeting.getId()).orElseThrow(MeetingNotFoundException::new);
        if (current.getStatus() != MeetingStatus.PROCESSING) {
            throw new IllegalStateException("Meeting processing state changed unexpectedly: " + current.getStatus());
        }
        return current;
    }
}
