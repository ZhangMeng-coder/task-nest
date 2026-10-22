package com.tasknest.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建规划会话请求，对应 {@code POST /api/main-tasks/plan-sessions}（HTTP 201）。
 *
 * <p>{@code goal} 必填且最多 4000 字；{@code workingDir} 必填且必须是 Git 仓库根目录，
 * 由服务端校验存在性、分支与工作区状态。</p>
 */
public record CreatePlanSessionRequest(
        @NotBlank(message = "目标不能为空")
        @Size(max = 4000, message = "目标最多 4000 字")
        String goal,
        @NotBlank(message = "必须选择工作目录")
        @Size(max = 1024, message = "工作目录最多 1024 字")
        String workingDir
) {
}
