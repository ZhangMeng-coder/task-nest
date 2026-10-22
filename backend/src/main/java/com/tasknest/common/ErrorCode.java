package com.tasknest.common;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码与 HTTP 状态映射，是 REST 错误契约的唯一来源。
 *
 * <p>编码按区间划分：{@code 400xx} 为请求 / 参数 / Git 前置条件错误（HTTP 400）；
 * {@code 404xx} 为资源不存在（404）；{@code 409xx} 为状态冲突（409）；
 * {@code 500xx} 为服务内部错误（500）；{@code 502xx} 为上游模型错误（502）；
 * {@code 503xx} 为依赖不可用（503）。新增错误码时需保持区间语义，并同步
 * {@code docs/requirements.md} 与前端错误处理。</p>
 */
public enum ErrorCode {

    INVALID_PARAM(40001, HttpStatus.BAD_REQUEST, "参数校验失败"),
    INVALID_AFTER_SEQ(40002, HttpStatus.BAD_REQUEST, "afterSeq 非法"),
    INVALID_WORKING_DIR(40004, HttpStatus.BAD_REQUEST, "工作目录不存在或不可访问"),
    DIRECTORY_NOT_READABLE(40005, HttpStatus.BAD_REQUEST, "无法读取目录"),
    INVALID_INPUT(40006, HttpStatus.BAD_REQUEST, "抉择提交不合法"),
    GIT_UNAVAILABLE(40010, HttpStatus.BAD_REQUEST, "本机找不到可执行的 git"),
    GIT_NOT_REPOSITORY(40011, HttpStatus.BAD_REQUEST, "工作目录不是 Git 仓库"),
    GIT_NOT_REPO_ROOT(40012, HttpStatus.BAD_REQUEST, "工作目录不是 Git 仓库根目录"),
    GIT_DETACHED_HEAD(40013, HttpStatus.BAD_REQUEST, "Git 处于 detached HEAD"),
    GIT_DIRTY_WORKTREE(40014, HttpStatus.BAD_REQUEST, "Git 工作区存在未提交改动"),
    RUN_NOT_FOUND(40401, HttpStatus.NOT_FOUND, "运行不存在"),
    TASK_NOT_FOUND(40402, HttpStatus.NOT_FOUND, "任务不存在"),
    MAIN_TASK_NOT_FOUND(40403, HttpStatus.NOT_FOUND, "主任务不存在"),
    PLAN_NOT_FOUND(40404, HttpStatus.NOT_FOUND, "主任务没有保存计划表"),
    PLAN_SESSION_NOT_FOUND(40405, HttpStatus.NOT_FOUND, "规划会话不存在"),
    NOT_WAITING_INPUT(40901, HttpStatus.CONFLICT, "任务当前不处于等待输入状态"),
    INVALID_OPTION(40902, HttpStatus.CONFLICT, "抉择选项无效"),
    RUN_ENDED(40903, HttpStatus.CONFLICT, "运行已结束"),
    PLAN_READ_ONLY(40904, HttpStatus.CONFLICT, "运行已结束，计划表为只读"),
    PLAN_SESSION_BUSY(40905, HttpStatus.CONFLICT, "规划会话正在运行"),
    PLAN_SESSION_NOT_READY(40906, HttpStatus.CONFLICT, "规划会话尚未就绪"),
    PLAN_REVISION_INVALID(40907, HttpStatus.CONFLICT, "计划重排不合法"),
    WORKSPACE_BUSY(40910, HttpStatus.CONFLICT, "同一仓库已有活动运行"),
    GIT_BRANCH_DRIFT(40911, HttpStatus.CONFLICT, "运行期间 Git 分支被外部切换"),
    GIT_MERGE_CONFLICT(40912, HttpStatus.CONFLICT, "Git 合并冲突"),
    INTERNAL(50001, HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误"),
    GIT_BRANCH_CREATE_FAILED(50010, HttpStatus.INTERNAL_SERVER_ERROR, "Git 分支创建失败"),
    GIT_COMMIT_FAILED(50011, HttpStatus.INTERNAL_SERVER_ERROR, "Git 提交失败"),
    GIT_ROLLBACK_FAILED(50012, HttpStatus.INTERNAL_SERVER_ERROR, "Git 回滚失败"),
    PLAN_AGENT_FAILED(50020, HttpStatus.INTERNAL_SERVER_ERROR, "规划 Agent 执行失败"),
    LLM_REQUEST_FAILED(50201, HttpStatus.BAD_GATEWAY, "模型请求失败"),
    LLM_TOOL_CALLING_UNSUPPORTED(50202, HttpStatus.BAD_GATEWAY, "模型不支持工具调用"),
    PLAN_AGENT_UNAVAILABLE(50301, HttpStatus.SERVICE_UNAVAILABLE, "规划 Agent 不可用");

    private final int code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(int code, HttpStatus httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    /**
     * 返回放入 {@link ApiResponse#code()} 的数值业务码。
     */
    public int code() {
        return code;
    }

    /**
     * 返回该错误码对应的 HTTP 状态。
     */
    public HttpStatus httpStatus() {
        return httpStatus;
    }

    /**
     * 返回可直接展示的默认错误文案。
     */
    public String defaultMessage() {
        return defaultMessage;
    }
}
