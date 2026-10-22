package com.tasknest.api;

import com.tasknest.api.dto.MainTaskDetail;
import com.tasknest.api.dto.MainTaskSummary;
import com.tasknest.api.dto.PlanDraftView;
import com.tasknest.api.dto.ReviseStoredPlanRequest;
import com.tasknest.common.ApiResponse;
import com.tasknest.persistence.MainTaskQueryService;
import com.tasknest.service.PlanDraftService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 主任务 REST 入口：计划表修订、持久化档案列表与详情。
 *
 * <p>创建流程走 {@link PlanSessionController} 的规划会话。档案查询只读取 MySQL，
 * 不依赖内存运行状态，因此进程重启后仍可访问。请求体统一由 Bean Validation 校验，
 * 失败映射为 {@code INVALID_PARAM (40001)}。</p>
 */
@RestController
@RequestMapping("/api/main-tasks")
public class MainTaskController {

    private final MainTaskQueryService queryService;
    private final PlanDraftService planDraftService;

    public MainTaskController(MainTaskQueryService queryService,
                              PlanDraftService planDraftService) {
        this.queryService = queryService;
        this.planDraftService = planDraftService;
    }

    /**
     * {@code POST /api/main-tasks/{mainTaskId}/plan/revisions}：修订档案中已保存的计划表。
     *
     * <p>只更新计划表版本，不增删或回滚已运行的子任务。主任务不存在时返回
     * {@code MAIN_TASK_NOT_FOUND (40403)}；没有计划表返回 {@code PLAN_NOT_FOUND (40404)}；
     * 主任务已 {@code COMPLETED/FAILED} 时计划只读，返回 {@code PLAN_READ_ONLY (40904)}。</p>
     */
    @PostMapping("/{mainTaskId}/plan/revisions")
    public ApiResponse<PlanDraftView> reviseStoredPlan(
            @PathVariable String mainTaskId,
            @Valid @RequestBody ReviseStoredPlanRequest request) {
        return ApiResponse.ok(planDraftService.reviseStored(mainTaskId, request.instruction()));
    }

    /**
     * {@code GET /api/main-tasks}：按创建时间倒序返回主任务档案摘要。
     *
     * <p>{@code limit} 默认为 50，服务层会收敛到 1–200。结果来自 MySQL，
     * 供前端左侧目录与历史档案列表使用。</p>
     */
    @GetMapping
    public ApiResponse<List<MainTaskSummary>> list(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ApiResponse.ok(queryService.list(limit));
    }

    /**
     * {@code GET /api/main-tasks/{mainTaskId}}：读取持久化主任务详情。
     *
     * <p>返回摘要、计划表、子任务、提示词、产物与事件账本，用于只读档案回放；
     * 主任务不存在时返回 {@code MAIN_TASK_NOT_FOUND (40403)}。</p>
     */
    @GetMapping("/{mainTaskId}")
    public ApiResponse<MainTaskDetail> detail(@PathVariable String mainTaskId) {
        return ApiResponse.ok(queryService.detail(mainTaskId));
    }
}
