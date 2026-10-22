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

/**
 * 创建主任务前的 SSE 规划会话 REST 入口。
 *
 * <p>会话经历 {@code RUNNING → WAITING_INPUT / READY → CONFIRMED}，或进入
 * {@code CANCELLED / FAILED}；确认前不创建主任务、不写主任务档案。规划期间持有
 * 仓库工作区锁，确认或取消后释放。规划事件以 {@code planSessionId} 作为 {@code runId}
 * 推送，字段契约见 {@code docs/events.md}。</p>
 */
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

    /**
     * {@code POST /api/main-tasks/plan-sessions}：创建并启动规划会话。
     *
     * <p>{@code goal} 与 {@code workingDir} 必填。创建工作目录必须是 Git 仓库根目录、
     * 非 detached HEAD 且工作区干净，否则返回 {@code GIT_*} 错误码；同一仓库已有活动会话时
     * 返回 {@code WORKSPACE_BUSY (40910)}。成功后返回 201 与创建时的会话状态
     * （通常为 {@code RUNNING}），调用方随后连接规划 SSE。</p>
     */
    @PostMapping
    public ResponseEntity<ApiResponse<CreatePlanSessionResponse>> create(
            @Valid @RequestBody CreatePlanSessionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.create(request)));
    }

    /**
     * {@code GET /api/main-tasks/plan-sessions/{planSessionId}}：读取会话当前状态。
     *
     * <p>返回状态、目标、工作目录、已生成计划（{@code READY} 前可能为 {@code null}）、
     * 最后事件序号与错误信息；会话不存在时返回
     * {@code PLAN_SESSION_NOT_FOUND (40405)}。</p>
     */
    @GetMapping("/{planSessionId}")
    public ApiResponse<PlanSessionView> view(@PathVariable String planSessionId) {
        return ApiResponse.ok(service.view(planSessionId));
    }

    /**
     * {@code GET /api/main-tasks/plan-sessions/{planSessionId}/events}：订阅规划事件流。
     *
     * <p>{@code afterSeq} 默认为 0，用于断线续传；服务端先补发历史事件再进入实时推送。
     * 越界序号返回 {@code INVALID_AFTER_SEQ (40002)}。会话结束或取消后连接关闭。</p>
     */
    @GetMapping("/{planSessionId}/events")
    public ResponseEntity<SseEmitter> streamEvents(
            @PathVariable String planSessionId,
            @RequestParam(name = "afterSeq", defaultValue = "0") long afterSeq) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(sseService.connect(planSessionId, afterSeq));
    }

    /**
     * {@code POST /api/main-tasks/plan-sessions/{planSessionId}/inputs}：回答规划中的抉择。
     *
     * <p>仅当会话处于 {@code WAITING_INPUT} 且 {@code taskId} 对应等待任务时可提交；
     * {@code selectedOptionId} 与 {@code customInput} 至少一个有效，具体校验在服务层
     * 按当前 pending 契约执行。成功返回 202，规划在后台继续。</p>
     */
    @PostMapping("/{planSessionId}/inputs")
    public ResponseEntity<ApiResponse<AcceptedResponse>> submitInput(
            @PathVariable String planSessionId,
            @Valid @RequestBody SubmitInputRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(service.submitInput(
                        planSessionId, request.taskId(),
                        request.selectedOptionId(), request.customInput())));
    }

    /**
     * {@code POST /api/main-tasks/plan-sessions/{planSessionId}/revisions}：按提示修订计划。
     *
     * <p>仅 {@code READY} 状态的会话可修订，否则返回
     * {@code PLAN_SESSION_BUSY (40905)}。修订后会话回到 {@code RUNNING}，
     * 进度通过规划 SSE 推送；本接口同步返回 {@code data=null}。</p>
     */
    @PostMapping("/{planSessionId}/revisions")
    public ApiResponse<Void> revise(@PathVariable String planSessionId,
                                    @Valid @RequestBody RevisePlanSessionRequest request) {
        service.revise(planSessionId, request.instruction());
        return ApiResponse.ok(null);
    }

    /**
     * {@code POST /api/main-tasks/plan-sessions/{planSessionId}/confirm}：确认计划并创建主任务。
     *
     * <p>仅 {@code READY} 且已生成计划的会话可确认，否则返回
     * {@code PLAN_SESSION_NOT_READY (40906)}。确认前重新校验 Git 仓库并把当前分支记为
     * 原始分支，随后释放工作区锁、创建主任务并启动执行；创建失败会重新获取锁并把
     * 异常抛给调用方。成功后会话置为 {@code CONFIRMED} 并关闭规划 SSE。</p>
     */
    @PostMapping("/{planSessionId}/confirm")
    public ApiResponse<ConfirmPlanSessionResponse> confirm(
            @PathVariable String planSessionId) {
        return ApiResponse.ok(service.confirm(planSessionId));
    }

    /**
     * {@code POST /api/main-tasks/plan-sessions/{planSessionId}/cancel}：取消规划会话。
     *
     * <p>幂等操作：会话已处于终态时直接返回。取消会置为 {@code CANCELLED}、释放仓库锁、
     * 推送终止事件并关闭 SSE；本接口同步返回 {@code data=null}。</p>
     */
    @PostMapping("/{planSessionId}/cancel")
    public ApiResponse<Void> cancel(@PathVariable String planSessionId) {
        service.cancel(planSessionId);
        return ApiResponse.ok(null);
    }
}
