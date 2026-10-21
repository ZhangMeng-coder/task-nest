package com.tasknest.service;

import com.tasknest.common.BusinessException;
import com.tasknest.common.ErrorCode;
import com.tasknest.event.EventPayload;
import com.tasknest.store.UserInput;
import com.tasknest.store.WaitingInputState;

/** waiting_input 提交的统一校验：选项与自由输入至少一个，且必须被当前问题允许。 */
public final class InputValidation {

    public static final int MAX_CUSTOM_INPUT = 4000;

    private InputValidation() {
    }

    public static UserInput validate(WaitingInputState waiting,
                                     String selectedOptionId,
                                     String customInput) {
        String optionId = trimToNull(selectedOptionId);
        String custom = trimToNull(customInput);
        if (custom != null && custom.length() > MAX_CUSTOM_INPUT) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "自由输入最多 " + MAX_CUSTOM_INPUT + " 字");
        }
        boolean customAllowed = Boolean.TRUE.equals(waiting.allowCustom());
        if (custom != null && !customAllowed) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "当前问题不允许自由输入");
        }
        if (optionId != null) {
            boolean valid = waiting.options().stream()
                    .anyMatch(option -> option.id().equals(optionId));
            if (!valid) {
                throw new BusinessException(ErrorCode.INVALID_OPTION);
            }
        }
        if (optionId == null && custom == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "请选择选项或填写自由输入");
        }
        return new UserInput(optionId, custom);
    }

    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static String describeSelection(EventPayload.WaitingInput waiting, UserInput input) {
        StringBuilder builder = new StringBuilder();
        if (input.selectedOptionId() != null) {
            waiting.options().stream()
                    .filter(option -> option.id().equals(input.selectedOptionId()))
                    .findFirst()
                    .ifPresent(option -> builder.append(option.label()));
        }
        if (input.customInput() != null) {
            if (builder.length() > 0) {
                builder.append("；补充：");
            }
            builder.append(input.customInput());
        }
        return builder.toString();
    }
}
