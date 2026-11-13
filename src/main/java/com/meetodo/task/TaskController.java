package com.meetodo.task;

import java.util.List;

import com.meetodo.task.dto.CreateTaskRequest;
import com.meetodo.task.dto.TaskResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskResponse create(@Valid @RequestBody CreateTaskRequest request) {
        return taskService.create(request);
    }

    @GetMapping
    public List<TaskResponse> findAll(@RequestParam(required = false) String assignee,
                                      @RequestParam(required = false) Long assigneeId) {
        return taskService.findAll(assignee, assigneeId);
    }

    @PatchMapping("/{id}/assignee")
    public TaskResponse assign(@PathVariable Long id,
                               @Valid @RequestBody com.meetodo.task.dto.AssignTaskRequest request,
                               @org.springframework.web.bind.annotation.RequestHeader(value = "X-Slack-User-Id", required = false) String actor) {
        return taskService.assign(id, request.assigneeId(), actor);
    }

    @PatchMapping("/{id}")
    public TaskResponse update(@PathVariable Long id,
                              @Valid @RequestBody com.meetodo.task.dto.UpdateTaskRequest request,
                              @org.springframework.web.bind.annotation.RequestHeader(value = "X-Slack-User-Id", required = false) String actor) {
        return taskService.update(id, request, actor);
    }

    @PostMapping("/{id}/reopen")
    public TaskResponse reopen(@PathVariable Long id,
                              @org.springframework.web.bind.annotation.RequestHeader(value = "X-Slack-User-Id", required = false) String actor) {
        return taskService.reopen(id, actor);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    public TaskResponse delete(@PathVariable Long id,
                              @org.springframework.web.bind.annotation.RequestHeader(value = "X-Slack-User-Id", required = false) String actor) {
        return taskService.delete(id, actor);
    }
}
