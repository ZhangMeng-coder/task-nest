package com.tasknest.api;

import com.tasknest.api.dto.CreateMainTaskRequest;
import com.tasknest.api.dto.CreateMainTaskResponse;
import com.tasknest.api.dto.GeneratePlanRequest;
import com.tasknest.api.dto.MainTaskDetail;
import com.tasknest.api.dto.MainTaskSummary;
import com.tasknest.api.dto.PlanDraftView;
import com.tasknest.api.dto.RevisePlanRequest;
import com.tasknest.api.dto.ReviseStoredPlanRequest;
import com.tasknest.common.ApiResponse;
import com.tasknest.persistence.MainTaskQueryService;
import com.tasknest.service.PlanDraftService;
import com.tasknest.service.RunService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/main-tasks")
public class MainTaskController {

    private final RunService runService;
    private final MainTaskQueryService queryService;
    private final PlanDraftService planDraftService;

    public MainTaskController(RunService runService,
                              MainTaskQueryService queryService,
                              PlanDraftService planDraftService) {
        this.runService = runService;
        this.queryService = queryService;
        this.planDraftService = planDraftService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CreateMainTaskResponse>> create(
            @Valid @RequestBody CreateMainTaskRequest request) {
        CreateMainTaskResponse response = runService.createMainTask(
                request.mockScript(), request.goal(), request.plan());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    /** 创建前强制生成计划表草案，不启动运行。 */
    @PostMapping("/plan-drafts")
    public ApiResponse<PlanDraftView> generatePlan(@Valid @RequestBody GeneratePlanRequest request) {
        return ApiResponse.ok(planDraftService.generate(request.mockScript(), request.goal()));
    }

    /** 用自然语言提示修改尚未确认的计划表草案。 */
    @PostMapping("/plan-drafts/revise")
    public ApiResponse<PlanDraftView> revisePlan(@Valid @RequestBody RevisePlanRequest request) {
        return ApiResponse.ok(planDraftService.revise(request));
    }

    /** 修订已保存到主任务档案中的计划表。 */
    @PostMapping("/{mainTaskId}/plan/revisions")
    public ApiResponse<PlanDraftView> reviseStoredPlan(
            @PathVariable String mainTaskId,
            @Valid @RequestBody ReviseStoredPlanRequest request) {
        return ApiResponse.ok(planDraftService.reviseStored(mainTaskId, request.instruction()));
    }

    @GetMapping
    public ApiResponse<List<MainTaskSummary>> list(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ApiResponse.ok(queryService.list(limit));
    }

    @GetMapping("/{mainTaskId}")
    public ApiResponse<MainTaskDetail> detail(@PathVariable String mainTaskId) {
        return ApiResponse.ok(queryService.detail(mainTaskId));
    }
}
