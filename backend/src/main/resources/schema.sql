CREATE TABLE IF NOT EXISTS tn_main_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    main_task_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    root_task_id VARCHAR(64) NULL,
    title VARCHAR(255) NOT NULL,
    goal TEXT NULL,
    mock_script VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    summary TEXT NULL,
    error TEXT NULL,
    waiting_question TEXT NULL,
    waiting_options TEXT NULL,
    selected_option_id VARCHAR(64) NULL,
    started_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_tn_main_task_id UNIQUE (main_task_id),
    CONSTRAINT uk_tn_main_task_run UNIQUE (run_id)
);

CREATE TABLE IF NOT EXISTS tn_main_task_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    main_task_id VARCHAR(64) NOT NULL,
    plan_title VARCHAR(255) NOT NULL,
    plan_goal TEXT NULL,
    plan_script VARCHAR(64) NULL,
    plan_version INT NOT NULL,
    plan_markdown LONGTEXT NOT NULL,
    plan_rows LONGTEXT NOT NULL,
    plan_revisions LONGTEXT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_tn_main_task_plan_id UNIQUE (main_task_id)
);

CREATE TABLE IF NOT EXISTS tn_subtask (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    main_task_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    agent_name VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    sort_order INT NOT NULL,
    depends_on TEXT NULL,
    summary TEXT NULL,
    error TEXT NULL,
    waiting_question TEXT NULL,
    waiting_options TEXT NULL,
    selected_option_id VARCHAR(64) NULL,
    started_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_tn_subtask_task UNIQUE (main_task_id, task_id)
);

CREATE TABLE IF NOT EXISTS tn_prompt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    main_task_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    seq BIGINT NOT NULL,
    system_prompt LONGTEXT NULL,
    user_prompt LONGTEXT NULL,
    context_snippets LONGTEXT NULL,
    final_prompt LONGTEXT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_tn_prompt_seq UNIQUE (main_task_id, seq)
);

CREATE TABLE IF NOT EXISTS tn_task_artifact (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    main_task_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    seq BIGINT NOT NULL,
    kind VARCHAR(32) NOT NULL,
    call_id VARCHAR(64) NULL,
    tool_name VARCHAR(128) NULL,
    status VARCHAR(32) NULL,
    duration_ms BIGINT NULL,
    content LONGTEXT NULL,
    created_at DATETIME(3) NOT NULL
);

CREATE TABLE IF NOT EXISTS tn_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    main_task_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    seq BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    task_id VARCHAR(64) NULL,
    event_json LONGTEXT NOT NULL,
    event_time DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_tn_event_seq UNIQUE (run_id, seq)
);
