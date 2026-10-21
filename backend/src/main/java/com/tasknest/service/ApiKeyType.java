package com.tasknest.service;

/**
 * API Key 的来源：直接保存具体值，或保存环境变量名并在运行时解析。
 */
public enum ApiKeyType {
    LITERAL,
    ENV
}
