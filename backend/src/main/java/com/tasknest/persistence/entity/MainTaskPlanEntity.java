package com.tasknest.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * {@code tn_main_task_plan} 主任务计划档案，与主任务一对一，不放进 {@code tn_main_task}。
 * <p>{@code PlanDraftService} 创建草案与运行期修订时写入；{@code plan_rows} 与
 * {@code plan_revisions} 以 LONGTEXT JSON 保存，{@code plan_version} 随重排递增。</p>
 */
@TableName("tn_main_task_plan")
public class MainTaskPlanEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String mainTaskId;
    private String planTitle;
    private String planGoal;
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
