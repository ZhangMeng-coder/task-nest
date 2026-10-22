package com.tasknest.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * {@code tn_prompt} 提示词拼装记录，每次 {@code prompt_assembled} 事件一行。
 * <p>{@code RunPersistenceService} 写入系统/用户提示词、上下文片段 JSON 与最终提示词；
 * 提示词列均为 LONGTEXT，{@code (main_task_id, seq)} 唯一，重试时允许同一任务保留多轮记录。</p>
 */
@TableName("tn_prompt")
public class PromptEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String mainTaskId;
    private String taskId;
    private Long seq;
    private String systemPrompt;
    private String userPrompt;
    private String contextSnippets;
    private String finalPrompt;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMainTaskId() {
        return mainTaskId;
    }

    public void setMainTaskId(String mainTaskId) {
        this.mainTaskId = mainTaskId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public Long getSeq() {
        return seq;
    }

    public void setSeq(Long seq) {
        this.seq = seq;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getUserPrompt() {
        return userPrompt;
    }

    public void setUserPrompt(String userPrompt) {
        this.userPrompt = userPrompt;
    }

    public String getContextSnippets() {
        return contextSnippets;
    }

    public void setContextSnippets(String contextSnippets) {
        this.contextSnippets = contextSnippets;
    }

    public String getFinalPrompt() {
        return finalPrompt;
    }

    public void setFinalPrompt(String finalPrompt) {
        this.finalPrompt = finalPrompt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
