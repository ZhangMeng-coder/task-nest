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

    /**
     * 校验一次抉择提交：选项与自由输入至少一个非空；自由输入受长度与
     * {@code allowCustom} 限制；选项 ID 必须属于当前问题。两侧空白会被裁剪。
     *
     * @throws BusinessException 自由输入超长 / 不被允许、选项不存在或两者皆空时抛出
     *                           {@link ErrorCode#INVALID_INPUT} 或
     *                           {@link ErrorCode#INVALID_OPTION}
     */
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

    /**
     * 裁剪两端空白；空白字符串折叠为 {@code null}，便于统一做“未提供”判断。
     */
    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 生成用于事件展示的选择描述：选项转为中文标签，自由输入以“补充：”拼接。
     * 未知选项 ID 不会报错，只输出自由输入部分。
     */
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
