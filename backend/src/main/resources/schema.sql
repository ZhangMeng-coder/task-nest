CREATE TABLE IF NOT EXISTS tn_main_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    main_task_id VARCHAR(64) NOT NULL COMMENT '主任务业务 ID（mt_ 前缀）',
    run_id VARCHAR(64) NOT NULL COMMENT '运行 ID；进程重启后用于识别运行已失效',
    root_task_id VARCHAR(64) NULL COMMENT '根任务 ID，对应主任务事件中的 taskId',
    title VARCHAR(255) NOT NULL COMMENT '主任务标题，确认计划后生成',
    goal TEXT NULL COMMENT '用户填写的任务目标',
    mock_script VARCHAR(64) NULL COMMENT '演练脚本名；NULL 表示使用真实 Agent',
    status VARCHAR(32) NOT NULL COMMENT '主任务状态：PENDING/RUNNING/WAITING_INPUT/COMPLETED/FAILED',
    summary TEXT NULL COMMENT '完成摘要',
    error TEXT NULL COMMENT '失败原因',
    waiting_question TEXT NULL COMMENT '等待用户抉择的问题',
    waiting_options TEXT NULL COMMENT '等待用户抉择的选项 JSON',
    selected_option_id VARCHAR(64) NULL COMMENT '用户已选择的选项 ID',
    started_at DATETIME(3) NULL COMMENT '开始执行时间',
    completed_at DATETIME(3) NULL COMMENT '结束时间',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    CONSTRAINT uk_tn_main_task_id UNIQUE (main_task_id),
    CONSTRAINT uk_tn_main_task_run UNIQUE (run_id)
) COMMENT='主任务档案：状态、目标、摘要与等待抉择';

CREATE TABLE IF NOT EXISTS tn_main_task_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    main_task_id VARCHAR(64) NOT NULL COMMENT '所属主任务业务 ID',
    plan_title VARCHAR(255) NOT NULL COMMENT '计划标题',
    plan_goal TEXT NULL COMMENT '计划对应的任务目标',
    plan_script VARCHAR(64) NULL COMMENT '生成计划时使用的演练脚本；NULL 表示真实 Agent',
    plan_version INT NOT NULL COMMENT '计划版本，动态重排时递增',
    plan_markdown LONGTEXT NOT NULL COMMENT '渲染后的 Markdown 计划全文',
    plan_rows LONGTEXT NOT NULL COMMENT '计划行数据 JSON',
    plan_revisions LONGTEXT NULL COMMENT '历次修订记录 JSON',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    CONSTRAINT uk_tn_main_task_plan_id UNIQUE (main_task_id)
) COMMENT='主任务计划表：Markdown、行数据与修订记录（与主任务一对一）';

CREATE TABLE IF NOT EXISTS tn_main_task_workspace (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    main_task_id VARCHAR(64) NOT NULL COMMENT '所属主任务业务 ID',
    working_dir VARCHAR(1024) NOT NULL COMMENT '工作目录绝对真实路径，必须是 Git 仓库根目录',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    CONSTRAINT uk_tn_main_task_workspace_id UNIQUE (main_task_id)
) COMMENT='主任务工作目录（与主任务一对一）';

CREATE TABLE IF NOT EXISTS tn_subtask (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    main_task_id VARCHAR(64) NOT NULL COMMENT '所属主任务业务 ID',
    task_id VARCHAR(64) NOT NULL COMMENT '子任务业务 ID',
    title VARCHAR(255) NOT NULL COMMENT '子任务标题',
    agent_name VARCHAR(64) NOT NULL COMMENT '执行子任务的 Agent 名称',
    status VARCHAR(32) NOT NULL COMMENT '子任务状态：PENDING/RUNNING/WAITING_INPUT/RETRYING/REVIEWING/SUCCESS/FAILED/CANCELLED',
    sort_order INT NOT NULL COMMENT '计划内执行顺序',
    depends_on TEXT NULL COMMENT '依赖的子任务 ID JSON 数组',
    summary TEXT NULL COMMENT '完成摘要',
    error TEXT NULL COMMENT '失败原因',
    waiting_question TEXT NULL COMMENT '等待用户抉择的问题',
    waiting_options TEXT NULL COMMENT '等待用户抉择的选项 JSON',
    selected_option_id VARCHAR(64) NULL COMMENT '用户已选择的选项 ID',
    started_at DATETIME(3) NULL COMMENT '开始执行时间',
    completed_at DATETIME(3) NULL COMMENT '结束时间',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    CONSTRAINT uk_tn_subtask_task UNIQUE (main_task_id, task_id)
) COMMENT='直属子任务档案：状态、依赖、摘要与等待抉择';

CREATE TABLE IF NOT EXISTS tn_prompt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    main_task_id VARCHAR(64) NOT NULL COMMENT '所属主任务业务 ID',
    task_id VARCHAR(64) NOT NULL COMMENT '提示词所属任务 ID',
    seq BIGINT NOT NULL COMMENT '对应事件序号',
    system_prompt LONGTEXT NULL COMMENT '系统提示词',
    user_prompt LONGTEXT NULL COMMENT '用户提示词',
    context_snippets LONGTEXT NULL COMMENT '上下文片段 JSON',
    final_prompt LONGTEXT NULL COMMENT '最终拼装的完整提示词',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    CONSTRAINT uk_tn_prompt_seq UNIQUE (main_task_id, seq)
) COMMENT='提示词拼装记录（每次 prompt_assembled 一行）';

CREATE TABLE IF NOT EXISTS tn_task_artifact (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    main_task_id VARCHAR(64) NOT NULL COMMENT '所属主任务业务 ID',
    task_id VARCHAR(64) NOT NULL COMMENT '中间结果所属任务 ID',
    seq BIGINT NOT NULL COMMENT '对应事件序号',
    kind VARCHAR(32) NOT NULL COMMENT '中间结果类型：thought/tool_call/tool_result',
    call_id VARCHAR(64) NULL COMMENT '工具调用 ID，用于配对调用与结果',
    tool_name VARCHAR(128) NULL COMMENT '工具名称',
    status VARCHAR(32) NULL COMMENT '工具执行状态',
    duration_ms BIGINT NULL COMMENT '工具执行耗时（毫秒）',
    content LONGTEXT NULL COMMENT '思考或工具输出内容',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间'
) COMMENT='任务中间结果：思考增量、工具调用与工具返回';

CREATE TABLE IF NOT EXISTS tn_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    main_task_id VARCHAR(64) NOT NULL COMMENT '所属主任务业务 ID',
    run_id VARCHAR(64) NOT NULL COMMENT '运行 ID',
    event_id VARCHAR(64) NOT NULL COMMENT '事件幂等 ID',
    seq BIGINT NOT NULL COMMENT '运行内严格递增的事件序号',
    type VARCHAR(32) NOT NULL COMMENT '事件类型，共 17 种',
    task_id VARCHAR(64) NULL COMMENT '事件所属任务 ID；运行级事件为 NULL',
    event_json LONGTEXT NOT NULL COMMENT '事件信封原文 JSON',
    event_time DATETIME(3) NOT NULL COMMENT '事件产生时间',
    created_at DATETIME(3) NOT NULL COMMENT '落库时间',
    CONSTRAINT uk_tn_event_seq UNIQUE (run_id, seq)
) COMMENT='运行事件原文，供前端补发与档案账本回放';

CREATE TABLE IF NOT EXISTS tn_llm_setting (
    id BIGINT PRIMARY KEY COMMENT '固定为 1 的单例主键',
    base_url VARCHAR(500) NOT NULL COMMENT 'OpenAI 兼容 Base URL',
    api_key_type VARCHAR(16) NOT NULL COMMENT '密钥来源：LITERAL 直接写入 / ENV 环境变量',
    api_key_value VARCHAR(2048) NULL COMMENT 'LITERAL 明文密钥或 ENV 环境变量名',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间'
) COMMENT='LLM 连接设置（单例）';

CREATE TABLE IF NOT EXISTS tn_llm_setting_model (
    id BIGINT PRIMARY KEY COMMENT '固定为 1 的单例主键',
    model_name VARCHAR(128) NOT NULL COMMENT '模型名称，默认 gpt-4o',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间'
) COMMENT='LLM 模型名称设置（单例，与 tn_llm_setting 配套）';

-- P3: 创建主任务前的 SSE 规划会话。确认后才创建主任务。
CREATE TABLE IF NOT EXISTS tn_plan_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    plan_session_id VARCHAR(64) NOT NULL COMMENT '规划会话业务 ID，同时作为规划 SSE 的 runId',
    goal TEXT NOT NULL COMMENT '用户填写的任务目标',
    working_dir VARCHAR(1024) NOT NULL COMMENT 'Git 仓库根目录绝对路径',
    mock_script VARCHAR(64) NULL COMMENT '演练脚本名；NULL 表示使用真实 Agent',
    status VARCHAR(32) NOT NULL COMMENT '会话状态：RUNNING/WAITING_INPUT/READY/CONFIRMED/CANCELLED/FAILED',
    plan_json LONGTEXT NULL COMMENT '当前计划 JSON',
    last_seq BIGINT NOT NULL DEFAULT 0 COMMENT '已产生的最近事件序号',
    error TEXT NULL COMMENT '失败原因',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    CONSTRAINT uk_tn_plan_session_id UNIQUE (plan_session_id)
) COMMENT='创建主任务前的 SSE 规划会话';

CREATE TABLE IF NOT EXISTS tn_plan_session_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    plan_session_id VARCHAR(64) NOT NULL COMMENT '所属规划会话业务 ID',
    event_id VARCHAR(64) NOT NULL COMMENT '事件幂等 ID',
    seq BIGINT NOT NULL COMMENT '会话内严格递增的事件序号',
    type VARCHAR(32) NOT NULL COMMENT '事件类型',
    event_json LONGTEXT NOT NULL COMMENT '事件信封原文 JSON',
    event_time DATETIME(3) NOT NULL COMMENT '事件产生时间',
    created_at DATETIME(3) NOT NULL COMMENT '落库时间',
    CONSTRAINT uk_tn_plan_session_event_seq UNIQUE (plan_session_id, seq)
) COMMENT='规划会话事件原文，供刷新与重连补发';

CREATE TABLE IF NOT EXISTS tn_agent_setting (
    id BIGINT PRIMARY KEY COMMENT '固定为 1 的单例主键',
    review_enabled BOOLEAN NOT NULL COMMENT '是否启用独立审核 Agent',
    review_max_reworks INT NOT NULL COMMENT '审核退回后的最大返工次数，范围 0-5',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间'
) COMMENT='Agent 审核设置（单例）';

CREATE TABLE IF NOT EXISTS tn_task_review (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    main_task_id VARCHAR(64) NOT NULL COMMENT '所属主任务业务 ID',
    task_id VARCHAR(64) NOT NULL COMMENT '被审核的子任务 ID',
    attempt INT NOT NULL COMMENT '子任务尝试轮次',
    verdict VARCHAR(16) NOT NULL COMMENT '审核结论：PASS 通过 / REWORK 退回',
    summary TEXT NULL COMMENT '审核摘要',
    issues LONGTEXT NULL COMMENT '审核问题列表 JSON',
    test_status VARCHAR(16) NULL COMMENT '平台测试状态：PASSED/FAILED/UNAVAILABLE',
    test_evidence LONGTEXT NULL COMMENT '测试证据 JSON（命令、退出码、输出摘要）',
    model_name VARCHAR(128) NULL COMMENT '执行审核的模型名称',
    duration_ms BIGINT NULL COMMENT '审核耗时（毫秒）',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间'
) COMMENT='子任务审核结论与平台测试证据';
