package com.tasknest.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RevisePlanRequest(
        @NotBlank(message = "mockScript 不能为空") String mockScript,
        @NotBlank(message = "计划标题不能为空")
        @Size(max = 255, message = "计划标题最多 255 字")
        String title,
        @NotBlank(message = "目标不能为空")
        @Size(max = 2000, message = "goal 最多 2000 字")
        String goal,
        @NotEmpty(message = "计划至少需要一个步骤")
        @Size(max = 50, message = "计划最多 50 个步骤")
        @Valid
        List<PlanRowView> rows,
        @Size(max = 50, message = "修订记录最多 50 条")
        List<String> revisions,
        @NotBlank(message = "先输入一条修改提示")
        @Size(max = 500, message = "修改提示最多 500 字")
        String instruction
) {
}
