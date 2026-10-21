package com.tasknest.store;

/** 用户对 waiting_input 的提交；选项与自由输入至少一个非空。 */
public record UserInput(String selectedOptionId, String customInput) {
}
