package com.tasknest.api;

import com.tasknest.api.dto.AcceptedResponse;
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

/**
 * 运行 REST 入口：查询内存快照、提交抉择、订阅运行 SSE。
 *
 * <p>运行由 {@link PlanSessionController} 的规划会话确认后创建，本控制器不提供创建入口。
 * 运行状态不持久化，进程重启后只能改用主任务档案查询。</p>
 */
@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunService runService;
    private final SseService sseService;

    public RunController(RunService runService, SseService sseService) {
        this.runService = runService;
        this.sseService = sseService;
    }

    /**
     * {@code GET /api/runs/{runId}}：读取运行快照（主任务 + 子任务 + 事件）。
     *
     * <p>运行只存在于内存，进程重启后返回 {@code RUN_NOT_FOUND (40401)}；
     * 已落库档案改用 {@code GET /api/main-tasks/{id}}。快照用于断线后重建前端状态，
     * 随后可用 {@code afterSeq} 续传 SSE。</p>
     */
    @GetMapping("/{runId}")
    public ApiResponse<RunSnapshot> getRun(@PathVariable String runId) {
        return ApiResponse.ok(runService.snapshot(runId));
    }

    /**
     * {@code POST /api/runs/{runId}/inputs}：向等待抉择的任务提交选项或自由输入。
     *
     * <p>运行已结束、任务不匹配或选项非法时由编排器分别返回
     * {@code RUN_ENDED} / {@code NOT_WAITING_INPUT} / {@code INVALID_OPTION} 等错误码；
     * 成功返回 202，仅表示输入已入队，任务会在后台继续。</p>
     */
    @PostMapping("/{runId}/inputs")
    public ResponseEntity<ApiResponse<AcceptedResponse>> submitInput(
            @PathVariable String runId,
            @Valid @RequestBody SubmitInputRequest request) {
        AcceptedResponse response = runService.submitInput(
                runId, request.taskId(), request.selectedOptionId(), request.customInput());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(response));
    }

    /**
     * {@code GET /api/runs/{runId}/events}：订阅运行事件流。
     *
     * <p>{@code afterSeq} 默认为 0，用于断线续传；越界时返回
     * {@code INVALID_AFTER_SEQ (40002)}。历史事件由 {@link SseService} 先补发，
     * 再进入实时推送，心跳也占用 seq。</p>
     */
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
