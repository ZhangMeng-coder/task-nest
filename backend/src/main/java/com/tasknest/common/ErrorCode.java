package com.tasknest.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    INVALID_PARAM(40001, HttpStatus.BAD_REQUEST, "参数校验失败"),
    INVALID_AFTER_SEQ(40002, HttpStatus.BAD_REQUEST, "afterSeq 非法"),
    INVALID_SCRIPT(40003, HttpStatus.BAD_REQUEST, "不支持的 Mock 脚本"),
    RUN_NOT_FOUND(40401, HttpStatus.NOT_FOUND, "运行不存在"),
    TASK_NOT_FOUND(40402, HttpStatus.NOT_FOUND, "任务不存在"),
    MAIN_TASK_NOT_FOUND(40403, HttpStatus.NOT_FOUND, "主任务不存在"),
    PLAN_NOT_FOUND(40404, HttpStatus.NOT_FOUND, "主任务没有保存计划表"),
    NOT_WAITING_INPUT(40901, HttpStatus.CONFLICT, "任务当前不处于等待输入状态"),
    INVALID_OPTION(40902, HttpStatus.CONFLICT, "抉择选项无效"),
    RUN_ENDED(40903, HttpStatus.CONFLICT, "运行已结束"),
    PLAN_READ_ONLY(40904, HttpStatus.CONFLICT, "运行已结束，计划表为只读"),
    INTERNAL(50001, HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误"),
    INVALID_MOCK_SCRIPT(50002, HttpStatus.INTERNAL_SERVER_ERROR, "内置 Mock 脚本无效或缺失");

    private final int code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(int code, HttpStatus httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
