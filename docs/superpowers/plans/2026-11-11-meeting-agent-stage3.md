# Meeting Agent Stage 3 Implementation Plan

> **For agentic workers:** Implement this plan task-by-task in the current workspace. Steps use checkbox syntax.

**Goal:** Complete 3-3 through 3-5 with bounded Spring AI tool calling, idempotent meeting processing, mock-only automated coverage, and operator-ready manual run instructions.

**Architecture:** A profile-guarded Spring AI adapter owns the user-controlled ToolCallingManager loop. An application service persists state and measurements, while the controller owns HTTP validation and response mapping. ToolContext carries Meeting scope without exposing it to the model.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring AI 1.1.8, Spring Data JPA, H2, JUnit 5, MockMvc.

**Spec:** `docs/superpowers/specs/2026-11-11-meeting-agent-design.md`

## Global Constraints

- OpenAI model beans and actual calls only under `local & openai & !test`.
- Tests never make network calls to OpenAI and use `ChatModel` mocks.
- Use Asia/Seoul Clock for date interpretation and tests.
- Never persist API keys, Authorization values, full meeting text in logs, or provider exception messages in API errors.
- Keep `notifySlack` out of stage 3.

## Review Focus

- Same normalized meeting text submitted concurrently: one Meeting row and one execution.
- Multiple tool calls returned in one model response crossing the cap: none beyond the configured limit execute.
- Null/blank model output after tools: Meeting becomes FAILED and error stays generic.
- Validation retry for a past date: retry once, then register without a due date and retain a warning.
- Logs and API failures containing key-shaped strings or meeting text: redact before persistence and response.

---

### Task 1: Idempotent meeting and task persistence

**Files:** Modify `Meeting.java`, `MeetingRepository.java`, `TaskRepository.java`, `TaskService.java`; test `MeetingProcessingServiceTests` and repository tests.

**Interfaces:** Meeting exposes a normalized SHA-256 content hash. TaskService exposes idempotent create scoped to `(meetingId, normalized title, assignee)` and returns whether it created a row.

- [x] Add tests for repeated meeting hash and per-meeting task dedupe before implementation.
- [x] Verify RED, then add hash uniqueness and repository lookups.
- [x] Keep same title in distinct meetings as a distinct Task.
- [x] Verify targeted tests.

### Task 2: ToolContext and bounded Spring AI agent loop

**Files:** Modify the three tools and `ToolExecutionService`; create `MeetingAgent`, `SpringAiMeetingAgent`, `MeetingAgentException`; test `SpringAiMeetingAgentTests` with scripted `ChatModel`.

**Interfaces:** `MeetingAgent.process(Meeting) -> AgentResult`; tool callbacks receive Meeting only through `ToolContext`; agent counts LLM calls and executed tools and enforces 21/20 caps.

- [x] Write scripted model tests for a tool round-trip, three tasks, unassigned member, validation retry/fallback, empty output, provider error, and loop cap.
- [x] Verify RED; replace ThreadLocal context with `ToolContext`.
- [x] Use `ToolCallbacks.from(...)`, `ToolCallingChatOptions.internalToolExecutionEnabled(false)`, and `ToolCallingManager.executeToolCalls`.
- [x] Build system prompt with role, extraction rules, order, and current Seoul date.
- [x] Verify all agent tests without network access.

### Task 3: Meeting application service and HTTP API

**Files:** Create meeting request/response/log DTOs, controller, application service, exception types/handler; modify Meeting repository/entity as needed; add MockMvc tests.

**Interfaces:** `POST /api/meetings`, `GET /api/meetings/{id}`, `GET /api/meetings/{id}/logs`. Persist start/end status and measured counters; same content hash reuses prior record.

- [x] Add MockMvc tests for valid input, blank/oversized input, disabled profile, missing ID, ordered logs, generic failure, duplicate and concurrent request handling.
- [x] Verify RED, then implement controller/service and `local & openai & !test` agent bean.
- [x] Confirm `test` has no ChatModel/MeetingAgent; integration substitutes a mock ChatModel under local+openai and sends no network requests.
- [x] Verify targeted API and profile tests.

### Task 4: 3-4 regression and security coverage

**Files:** Extend tool, agent, API tests; create test log-capture utility or test as needed; update `testing-plan-3-4.md`.

- [x] Cover tool validation, agent flow, fixed Seoul clock/date prompt, duplicate and concurrent requests, API/profile regressions, and sensitive input/log/ToolCallLog sanitization.
- [x] Capture application logs and assert no key-like value, bearer token, or full transcript appears.
- [x] Run `./gradlew test bootJar --no-daemon`: 64 tests, 0 failures, 0 errors, 0 skipped.

### Task 5: 3-5 execution examples and closeout documents

**Files:** Create `thirdStepDone.md`; modify `midDone.md`, `DEVLOG.md`, `testing-plan-3-4.md`.

- [x] Add three meeting-note examples and Swagger run order.
- [x] Add a blank measurement baseline table; no measured values were filled in.
- [x] Record the observed lazy-loading bug and fix in DEVLOG.
- [x] Re-read the plan and confirm every item and the final full test report.
