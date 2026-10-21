package com.tasknest.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 主任务计划表档案。一个主任务一行，修订时更新版本并保留修改记录。
 */
@TableName("tn_main_task_plan")
public class MainTaskPlanEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String mainTaskId;
    private String planTitle;
    private String planGoal;
    private String planScript;
    private Integer planVersion;
    private String planMarkdown;
    private String planRows;
    private String planRevisions;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

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

    public String getPlanTitle() {
        return planTitle;
    }

    public void setPlanTitle(String planTitle) {
        this.planTitle = planTitle;
    }

    public String getPlanGoal() {
        return planGoal;
    }

    public void setPlanGoal(String planGoal) {
        this.planGoal = planGoal;
    }

    public String getPlanScript() {
        return planScript;
    }

    public void setPlanScript(String planScript) {
        this.planScript = planScript;
    }

    public Integer getPlanVersion() {
        return planVersion;
    }

    public void setPlanVersion(Integer planVersion) {
        this.planVersion = planVersion;
    }

    public String getPlanMarkdown() {
        return planMarkdown;
    }

    public void setPlanMarkdown(String planMarkdown) {
        this.planMarkdown = planMarkdown;
    }

    public String getPlanRows() {
        return planRows;
    }

    public void setPlanRows(String planRows) {
        this.planRows = planRows;
    }

    public String getPlanRevisions() {
        return planRevisions;
    }

    public void setPlanRevisions(String planRevisions) {
        this.planRevisions = planRevisions;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
