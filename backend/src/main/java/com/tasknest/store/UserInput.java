package com.tasknest.store;

/**
 * 用户对 {@code waiting_input} 的提交；选项与自由输入至少一个非空，
 * 是否可以同时提供由当前问题的 {@code allowCustom} 决定。
 */
public record UserInput(String selectedOptionId, String customInput) {
}
