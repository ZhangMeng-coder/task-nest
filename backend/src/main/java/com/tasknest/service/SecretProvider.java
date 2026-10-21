package com.tasknest.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 收集需要从工具输出中脱敏的已知密钥值；不记录、不返回敏感变量名。 */
@Service
public class SecretProvider {

    private static final Set<String> EXPLICIT_NAMES = Set.of(
            "TASKNEST_MYSQL_PASSWORD", "MYSQL_PASSWORD", "TASKNEST_MYSQL_ROOT_PASSWORD",
            "DB_PASSWORD", "DATABASE_PASSWORD");

    private final LlmSettingsService llmSettings;

    public SecretProvider(LlmSettingsService llmSettings) {
        this.llmSettings = llmSettings;
    }

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
