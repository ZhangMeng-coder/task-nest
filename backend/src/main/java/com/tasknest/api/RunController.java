package com.tasknest.api;

import com.tasknest.api.dto.AcceptedResponse;
import com.tasknest.api.dto.CreateRunRequest;
import com.tasknest.api.dto.CreateRunResponse;
import com.tasknest.api.dto.SubmitInputRequest;
import com.tasknest.common.ApiResponse;
import com.tasknest.service.RunService;
import com.tasknest.sse.SseService;
import com.tasknest.store.RunSnapshot;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunService runService;
    private final SseService sseService;

    public RunController(RunService runService, SseService sseService) {
        this.runService = runService;
        this.sseService = sseService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CreateRunResponse>> createRun(@Valid @RequestBody CreateRunRequest request) {
        CreateRunResponse response = runService.createRun(request.mockScript());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @GetMapping("/{runId}")
    public ApiResponse<RunSnapshot> getRun(@PathVariable String runId) {
        return ApiResponse.ok(runService.snapshot(runId));
    }

    @PostMapping("/{runId}/inputs")
    public ResponseEntity<ApiResponse<AcceptedResponse>> submitInput(
            @PathVariable String runId,
            @Valid @RequestBody SubmitInputRequest request) {
        AcceptedResponse response = runService.submitInput(
                runId, request.taskId(), request.selectedOptionId(), request.customInput());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(response));
    }

    @GetMapping("/{runId}/events")
    public ResponseEntity<SseEmitter> streamEvents(
            @PathVariable String runId,
            @RequestParam(name = "afterSeq", defaultValue = "0") long afterSeq) {
        // No `produces` here: validation failures must still be able to return JSON.
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(sseService.connect(runId, afterSeq));
    }
}
