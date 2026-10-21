package com.tasknest.api;

import com.tasknest.api.dto.AcceptedResponse;
import com.tasknest.api.dto.ConfirmPlanSessionResponse;
import com.tasknest.api.dto.CreatePlanSessionRequest;
import com.tasknest.api.dto.CreatePlanSessionResponse;
import com.tasknest.api.dto.PlanSessionView;
import com.tasknest.api.dto.RevisePlanSessionRequest;
import com.tasknest.api.dto.SubmitInputRequest;
import com.tasknest.common.ApiResponse;
import com.tasknest.service.PlanningSessionService;
import com.tasknest.sse.PlanSessionSseService;
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

/** 创建主任务前的 SSE 规划会话。确认前不创建主任务。 */
@RestController
@RequestMapping("/api/main-tasks/plan-sessions")
public class PlanSessionController {

    private final PlanningSessionService service;
    private final PlanSessionSseService sseService;

    public PlanSessionController(PlanningSessionService service,
                                 PlanSessionSseService sseService) {
        this.service = service;
        this.sseService = sseService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CreatePlanSessionResponse>> create(
            @Valid @RequestBody CreatePlanSessionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.create(request)));
    }

    @GetMapping("/{planSessionId}")
    public ApiResponse<PlanSessionView> view(@PathVariable String planSessionId) {
        return ApiResponse.ok(service.view(planSessionId));
    }

    @GetMapping("/{planSessionId}/events")
    public ResponseEntity<SseEmitter> streamEvents(
            @PathVariable String planSessionId,
            @RequestParam(name = "afterSeq", defaultValue = "0") long afterSeq) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(sseService.connect(planSessionId, afterSeq));
    }

    @PostMapping("/{planSessionId}/inputs")
    public ResponseEntity<ApiResponse<AcceptedResponse>> submitInput(
            @PathVariable String planSessionId,
            @Valid @RequestBody SubmitInputRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(service.submitInput(
                        planSessionId, request.taskId(),
                        request.selectedOptionId(), request.customInput())));
    }

    @PostMapping("/{planSessionId}/revisions")
    public ApiResponse<Void> revise(@PathVariable String planSessionId,
                                    @Valid @RequestBody RevisePlanSessionRequest request) {
        service.revise(planSessionId, request.instruction());
        return ApiResponse.ok(null);
    }

    @PostMapping("/{planSessionId}/confirm")
    public ApiResponse<ConfirmPlanSessionResponse> confirm(
            @PathVariable String planSessionId) {
        return ApiResponse.ok(service.confirm(planSessionId));
    }

    @PostMapping("/{planSessionId}/cancel")
    public ApiResponse<Void> cancel(@PathVariable String planSessionId) {
        service.cancel(planSessionId);
        return ApiResponse.ok(null);
    }
}
