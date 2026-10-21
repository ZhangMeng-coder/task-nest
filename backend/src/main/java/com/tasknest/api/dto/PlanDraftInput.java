package com.tasknest.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建主任务时提交的已确认计划。Markdown 由服务端按 rows 重新生成，避免前后端展示不一致。
 */
public record PlanDraftInput(
        @NotBlank(message = "计划标题不能为空")
        @Size(max = 255, message = "计划标题最多 255 字")
        String title,
        @NotEmpty(message = "计划至少需要一个步骤")
        @Size(max = 50, message = "计划最多 50 个步骤")
        @Valid
        List<PlanRowView> rows,
        @Size(max = 50, message = "修订记录最多 50 条")
        List<String> revisions
) {
}
