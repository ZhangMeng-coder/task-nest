-- 为既有 task_nest 库补齐表注释与字段注释。
-- 新建库由 schema.sql 中的 COMMENT 子句直接生成；本文件仅用于已存在的库，手工执行一次。
-- 使用 MODIFY COLUMN 时必须重复原列定义；这里保持与 schema.sql 的类型、可空性、
-- 默认值与 AUTO_INCREMENT 完全一致，避免修改列语义。

ALTER TABLE tn_main_task COMMENT = '主任务档案：状态、目标、摘要与等待抉择';
ALTER TABLE tn_main_task
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    MODIFY COLUMN main_task_id VARCHAR(64) NOT NULL COMMENT '主任务业务 ID（mt_ 前缀）',
    MODIFY COLUMN run_id VARCHAR(64) NOT NULL COMMENT '运行 ID；进程重启后用于识别运行已失效',
    MODIFY COLUMN root_task_id VARCHAR(64) NULL COMMENT '根任务 ID，对应主任务事件中的 taskId',
    MODIFY COLUMN title VARCHAR(255) NOT NULL COMMENT '主任务标题，确认计划后生成',
    MODIFY COLUMN goal TEXT NULL COMMENT '用户填写的任务目标',
    MODIFY COLUMN mock_script VARCHAR(64) NULL COMMENT '演练脚本名；NULL 表示使用真实 Agent',
    MODIFY COLUMN status VARCHAR(32) NOT NULL COMMENT '主任务状态：PENDING/RUNNING/WAITING_INPUT/COMPLETED/FAILED',
    MODIFY COLUMN summary TEXT NULL COMMENT '完成摘要',
    MODIFY COLUMN error TEXT NULL COMMENT '失败原因',
    MODIFY COLUMN waiting_question TEXT NULL COMMENT '等待用户抉择的问题',
    MODIFY COLUMN waiting_options TEXT NULL COMMENT '等待用户抉择的选项 JSON',
    MODIFY COLUMN selected_option_id VARCHAR(64) NULL COMMENT '用户已选择的选项 ID',
    MODIFY COLUMN started_at DATETIME(3) NULL COMMENT '开始执行时间',
    MODIFY COLUMN completed_at DATETIME(3) NULL COMMENT '结束时间',
    MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    MODIFY COLUMN updated_at DATETIME(3) NOT NULL COMMENT '更新时间';

ALTER TABLE tn_main_task_plan COMMENT = '主任务计划表：Markdown、行数据与修订记录（与主任务一对一）';
ALTER TABLE tn_main_task_plan
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    MODIFY COLUMN main_task_id VARCHAR(64) NOT NULL COMMENT '所属主任务业务 ID',
    MODIFY COLUMN plan_title VARCHAR(255) NOT NULL COMMENT '计划标题',
    MODIFY COLUMN plan_goal TEXT NULL COMMENT '计划对应的任务目标',
    MODIFY COLUMN plan_script VARCHAR(64) NULL COMMENT '生成计划时使用的演练脚本；NULL 表示真实 Agent',
    MODIFY COLUMN plan_version INT NOT NULL COMMENT '计划版本，动态重排时递增',
    MODIFY COLUMN plan_markdown LONGTEXT NOT NULL COMMENT '渲染后的 Markdown 计划全文',
    MODIFY COLUMN plan_rows LONGTEXT NOT NULL COMMENT '计划行数据 JSON',
    MODIFY COLUMN plan_revisions LONGTEXT NULL COMMENT '历次修订记录 JSON',
    MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    MODIFY COLUMN updated_at DATETIME(3) NOT NULL COMMENT '更新时间';

ALTER TABLE tn_main_task_workspace COMMENT = '主任务工作目录（与主任务一对一）';
ALTER TABLE tn_main_task_workspace
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    MODIFY COLUMN main_task_id VARCHAR(64) NOT NULL COMMENT '所属主任务业务 ID',
    MODIFY COLUMN working_dir VARCHAR(1024) NOT NULL COMMENT '工作目录绝对真实路径，必须是 Git 仓库根目录',
    MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    MODIFY COLUMN updated_at DATETIME(3) NOT NULL COMMENT '更新时间';

ALTER TABLE tn_subtask COMMENT = '直属子任务档案：状态、依赖、摘要与等待抉择';
ALTER TABLE tn_subtask
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    MODIFY COLUMN main_task_id VARCHAR(64) NOT NULL COMMENT '所属主任务业务 ID',
    MODIFY COLUMN task_id VARCHAR(64) NOT NULL COMMENT '子任务业务 ID',
    MODIFY COLUMN title VARCHAR(255) NOT NULL COMMENT '子任务标题',
    MODIFY COLUMN agent_name VARCHAR(64) NOT NULL COMMENT '执行子任务的 Agent 名称',
    MODIFY COLUMN status VARCHAR(32) NOT NULL COMMENT '子任务状态：PENDING/RUNNING/WAITING_INPUT/RETRYING/REVIEWING/SUCCESS/FAILED/CANCELLED',
    MODIFY COLUMN sort_order INT NOT NULL COMMENT '计划内执行顺序',
    MODIFY COLUMN depends_on TEXT NULL COMMENT '依赖的子任务 ID JSON 数组',
    MODIFY COLUMN summary TEXT NULL COMMENT '完成摘要',
    MODIFY COLUMN error TEXT NULL COMMENT '失败原因',
    MODIFY COLUMN waiting_question TEXT NULL COMMENT '等待用户抉择的问题',
    MODIFY COLUMN waiting_options TEXT NULL COMMENT '等待用户抉择的选项 JSON',
    MODIFY COLUMN selected_option_id VARCHAR(64) NULL COMMENT '用户已选择的选项 ID',
    MODIFY COLUMN started_at DATETIME(3) NULL COMMENT '开始执行时间',
    MODIFY COLUMN completed_at DATETIME(3) NULL COMMENT '结束时间',
    MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    MODIFY COLUMN updated_at DATETIME(3) NOT NULL COMMENT '更新时间';

ALTER TABLE tn_prompt COMMENT = '提示词拼装记录（每次 prompt_assembled 一行）';
ALTER TABLE tn_prompt
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    MODIFY COLUMN main_task_id VARCHAR(64) NOT NULL COMMENT '所属主任务业务 ID',
    MODIFY COLUMN task_id VARCHAR(64) NOT NULL COMMENT '提示词所属任务 ID',
    MODIFY COLUMN seq BIGINT NOT NULL COMMENT '对应事件序号',
    MODIFY COLUMN system_prompt LONGTEXT NULL COMMENT '系统提示词',
    MODIFY COLUMN user_prompt LONGTEXT NULL COMMENT '用户提示词',
    MODIFY COLUMN context_snippets LONGTEXT NULL COMMENT '上下文片段 JSON',
    MODIFY COLUMN final_prompt LONGTEXT NULL COMMENT '最终拼装的完整提示词',
    MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '创建时间';

ALTER TABLE tn_task_artifact COMMENT = '任务中间结果：思考增量、工具调用与工具返回';
ALTER TABLE tn_task_artifact
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    MODIFY COLUMN main_task_id VARCHAR(64) NOT NULL COMMENT '所属主任务业务 ID',
    MODIFY COLUMN task_id VARCHAR(64) NOT NULL COMMENT '中间结果所属任务 ID',
    MODIFY COLUMN seq BIGINT NOT NULL COMMENT '对应事件序号',
    MODIFY COLUMN kind VARCHAR(32) NOT NULL COMMENT '中间结果类型：thought/tool_call/tool_result',
    MODIFY COLUMN call_id VARCHAR(64) NULL COMMENT '工具调用 ID，用于配对调用与结果',
    MODIFY COLUMN tool_name VARCHAR(128) NULL COMMENT '工具名称',
    MODIFY COLUMN status VARCHAR(32) NULL COMMENT '工具执行状态',
    MODIFY COLUMN duration_ms BIGINT NULL COMMENT '工具执行耗时（毫秒）',
    MODIFY COLUMN content LONGTEXT NULL COMMENT '思考或工具输出内容',
    MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '创建时间';

ALTER TABLE tn_event COMMENT = '运行事件原文，供前端补发与档案账本回放';
ALTER TABLE tn_event
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    MODIFY COLUMN main_task_id VARCHAR(64) NOT NULL COMMENT '所属主任务业务 ID',
    MODIFY COLUMN run_id VARCHAR(64) NOT NULL COMMENT '运行 ID',
    MODIFY COLUMN event_id VARCHAR(64) NOT NULL COMMENT '事件幂等 ID',
    MODIFY COLUMN seq BIGINT NOT NULL COMMENT '运行内严格递增的事件序号',
    MODIFY COLUMN type VARCHAR(32) NOT NULL COMMENT '事件类型，共 17 种',
    MODIFY COLUMN task_id VARCHAR(64) NULL COMMENT '事件所属任务 ID；运行级事件为 NULL',
    MODIFY COLUMN event_json LONGTEXT NOT NULL COMMENT '事件信封原文 JSON',
    MODIFY COLUMN event_time DATETIME(3) NOT NULL COMMENT '事件产生时间',
    MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '落库时间';

ALTER TABLE tn_llm_setting COMMENT = 'LLM 连接设置（单例）';
ALTER TABLE tn_llm_setting
    MODIFY COLUMN id BIGINT NOT NULL COMMENT '固定为 1 的单例主键',
    MODIFY COLUMN base_url VARCHAR(500) NOT NULL COMMENT 'OpenAI 兼容 Base URL',
    MODIFY COLUMN api_key_type VARCHAR(16) NOT NULL COMMENT '密钥来源：LITERAL 直接写入 / ENV 环境变量',
    MODIFY COLUMN api_key_value VARCHAR(2048) NULL COMMENT 'LITERAL 明文密钥或 ENV 环境变量名',
    MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    MODIFY COLUMN updated_at DATETIME(3) NOT NULL COMMENT '更新时间';

ALTER TABLE tn_llm_setting_model COMMENT = 'LLM 模型名称设置（单例，与 tn_llm_setting 配套）';
ALTER TABLE tn_llm_setting_model
    MODIFY COLUMN id BIGINT NOT NULL COMMENT '固定为 1 的单例主键',
    MODIFY COLUMN model_name VARCHAR(128) NOT NULL COMMENT '模型名称，默认 gpt-4o',
    MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    MODIFY COLUMN updated_at DATETIME(3) NOT NULL COMMENT '更新时间';

ALTER TABLE tn_plan_session COMMENT = '创建主任务前的 SSE 规划会话';
ALTER TABLE tn_plan_session
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    MODIFY COLUMN plan_session_id VARCHAR(64) NOT NULL COMMENT '规划会话业务 ID，同时作为规划 SSE 的 runId',
    MODIFY COLUMN goal TEXT NOT NULL COMMENT '用户填写的任务目标',
    MODIFY COLUMN working_dir VARCHAR(1024) NOT NULL COMMENT 'Git 仓库根目录绝对路径',
    MODIFY COLUMN mock_script VARCHAR(64) NULL COMMENT '演练脚本名；NULL 表示使用真实 Agent',
    MODIFY COLUMN status VARCHAR(32) NOT NULL COMMENT '会话状态：RUNNING/WAITING_INPUT/READY/CONFIRMED/CANCELLED/FAILED',
    MODIFY COLUMN plan_json LONGTEXT NULL COMMENT '当前计划 JSON',
    MODIFY COLUMN last_seq BIGINT NOT NULL DEFAULT 0 COMMENT '已产生的最近事件序号',
    MODIFY COLUMN error TEXT NULL COMMENT '失败原因',
    MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    MODIFY COLUMN updated_at DATETIME(3) NOT NULL COMMENT '更新时间';

ALTER TABLE tn_plan_session_event COMMENT = '规划会话事件原文，供刷新与重连补发';
ALTER TABLE tn_plan_session_event
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    MODIFY COLUMN plan_session_id VARCHAR(64) NOT NULL COMMENT '所属规划会话业务 ID',
    MODIFY COLUMN event_id VARCHAR(64) NOT NULL COMMENT '事件幂等 ID',
    MODIFY COLUMN seq BIGINT NOT NULL COMMENT '会话内严格递增的事件序号',
    MODIFY COLUMN type VARCHAR(32) NOT NULL COMMENT '事件类型',
    MODIFY COLUMN event_json LONGTEXT NOT NULL COMMENT '事件信封原文 JSON',
    MODIFY COLUMN event_time DATETIME(3) NOT NULL COMMENT '事件产生时间',
    MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '落库时间';

ALTER TABLE tn_agent_setting COMMENT = 'Agent 审核设置（单例）';
ALTER TABLE tn_agent_setting
    MODIFY COLUMN id BIGINT NOT NULL COMMENT '固定为 1 的单例主键',
    MODIFY COLUMN review_enabled BOOLEAN NOT NULL COMMENT '是否启用独立审核 Agent',
    MODIFY COLUMN review_max_reworks INT NOT NULL COMMENT '审核退回后的最大返工次数，范围 0-5',
    MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    MODIFY COLUMN updated_at DATETIME(3) NOT NULL COMMENT '更新时间';

ALTER TABLE tn_task_review COMMENT = '子任务审核结论与平台测试证据';
ALTER TABLE tn_task_review
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    MODIFY COLUMN main_task_id VARCHAR(64) NOT NULL COMMENT '所属主任务业务 ID',
    MODIFY COLUMN task_id VARCHAR(64) NOT NULL COMMENT '被审核的子任务 ID',
    MODIFY COLUMN attempt INT NOT NULL COMMENT '子任务尝试轮次',
    MODIFY COLUMN verdict VARCHAR(16) NOT NULL COMMENT '审核结论：PASS 通过 / REWORK 退回',
    MODIFY COLUMN summary TEXT NULL COMMENT '审核摘要',
    MODIFY COLUMN issues LONGTEXT NULL COMMENT '审核问题列表 JSON',
    MODIFY COLUMN test_status VARCHAR(16) NULL COMMENT '平台测试状态：PASSED/FAILED/UNAVAILABLE',
    MODIFY COLUMN test_evidence LONGTEXT NULL COMMENT '测试证据 JSON（命令、退出码、输出摘要）',
    MODIFY COLUMN model_name VARCHAR(128) NULL COMMENT '执行审核的模型名称',
    MODIFY COLUMN duration_ms BIGINT NULL COMMENT '审核耗时（毫秒）',
    MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '创建时间';
