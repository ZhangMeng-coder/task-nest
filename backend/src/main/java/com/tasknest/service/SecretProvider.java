package com.tasknest.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 收集需要从工具输出与日志中脱敏的已知密钥值。
 *
 * <p>来源包括当前 LLM 密钥（ENV 模式在此刻解析）以及一组约定的环境变量：
 * 显式密码变量、以 {@code _SECRET} / {@code _TOKEN} / {@code _API_KEY} 结尾的变量。
 * 只缓存值本身（去重、忽略长度小于 4 的值），不记录也不对外返回变量名。</p>
 */
@Service
public class SecretProvider {

    private static final Set<String> EXPLICIT_NAMES = Set.of(
            "TASKNEST_MYSQL_PASSWORD", "MYSQL_PASSWORD", "TASKNEST_MYSQL_ROOT_PASSWORD",
            "DB_PASSWORD", "DATABASE_PASSWORD");

    private final LlmSettingsService llmSettings;

    public SecretProvider(LlmSettingsService llmSettings) {
        this.llmSettings = llmSettings;
    }

    /**
     * 返回当前可用的密钥值列表。每次调用重新解析环境变量与 LLM 设置，
     * 因此设置热更新后无需重建本对象。
     */
    public List<String> values() {
        Set<String> values = new LinkedHashSet<>();
        add(values, llmSettings.resolveApiKey());
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String name = entry.getKey().toUpperCase(Locale.ROOT);
            if (EXPLICIT_NAMES.contains(name)
                    || name.endsWith("_SECRET")
                    || name.endsWith("_TOKEN")
                    || name.endsWith("_API_KEY")) {
                add(values, entry.getValue());
            }
        }
        return new ArrayList<>(values);
    }

    private void add(Set<String> values, String value) {
        if (value != null && value.length() >= 4) {
            values.add(value);
        }
    }
}
