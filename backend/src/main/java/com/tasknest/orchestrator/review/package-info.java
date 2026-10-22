/**
 * 独立审核：平台先执行测试并检查工作区副作用，再让模型输出严格 JSON；
 * 平台证据优先，测试失败或副作用强制 {@code REWORK}。
 */
package com.tasknest.orchestrator.review;
