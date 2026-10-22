package com.tasknest.service;

/**
 * API Key 的来源：{@link #LITERAL} 直接保存具体值并在读取时掩码；
 * {@link #ENV} 只保存环境变量名，在实际请求时解析，避免把密钥写入数据库。
 */
public enum ApiKeyType {
    LITERAL,
    ENV
}
