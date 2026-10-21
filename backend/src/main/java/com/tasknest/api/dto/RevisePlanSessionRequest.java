package com.tasknest.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RevisePlanSessionRequest(
        @NotBlank(message = "修订提示不能为空")
        @Size(max = 4000, message = "修订提示最多 4000 字")
        String instruction
) {
}
