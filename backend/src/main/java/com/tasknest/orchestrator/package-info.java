/**
 * 编排层：{@link com.tasknest.orchestrator.TaskOrchestrator} 抽象启动与抉择，
 * 唯一实现 {@link com.tasknest.orchestrator.CodingTaskOrchestrator} 承担真实执行；
 * Git 分支、提交、合并与回滚由 {@link com.tasknest.orchestrator.GitService} 封装。
 */
package com.tasknest.orchestrator;
