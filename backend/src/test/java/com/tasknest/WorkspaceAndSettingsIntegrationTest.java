package com.tasknest;

import com.fasterxml.jackson.databind.JsonNode;
import com.tasknest.persistence.entity.LlmSettingEntity;
import com.tasknest.persistence.entity.LlmSettingModelEntity;
import com.tasknest.persistence.service.LlmSettingEntityService;
import com.tasknest.persistence.service.LlmSettingModelEntityService;
import com.tasknest.service.ApiKeyType;
import com.tasknest.service.LlmSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WorkspaceAndSettingsIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private LlmSettingsService llmSettingsService;

    @Autowired
    private LlmSettingEntityService llmSettingEntityService;

    @Autowired
    private LlmSettingModelEntityService llmSettingModelEntityService;

    @LocalServerPort
    private int port;

    @BeforeEach
    void resetLlmSettings() {
        llmSettingEntityService.removeById(1L);
        llmSettingModelEntityService.removeById(1L);
        llmSettingsService.load();
    }

    @Test
    @Timeout(20)
    void browsesDirectoriesWithoutExposingFiles(@TempDir Path temp) throws Exception {
        Path alpha = Files.createDirectories(temp.resolve("alpha"));
        Files.createDirectories(alpha.resolve("nested"));
        Files.createDirectories(temp.resolve("beta"));
        Files.writeString(temp.resolve("note.txt"), "not a directory");

        ResponseEntity<JsonNode> listing = directoryListing(temp);
        assertEquals(HttpStatus.OK, listing.getStatusCode(), String.valueOf(listing.getBody()));
        JsonNode data = listing.getBody().get("data");
        assertEquals(temp.toRealPath().toString(), data.get("path").asText());
        assertEquals(temp.toRealPath().getParent().toString(), data.get("parent").asText());
        assertFalse(data.get("roots").isEmpty());
        assertFalse(data.get("truncated").asBoolean());

        Set<String> names = new HashSet<>();
        for (JsonNode entry : data.get("directories")) {
            names.add(entry.get("name").asText());
        }
        assertTrue(names.contains("alpha"));
        assertTrue(names.contains("beta"));
        assertFalse(names.contains("note.txt"), "目录选择器不应返回普通文件");

        ResponseEntity<JsonNode> filePath = directoryListing(temp.resolve("note.txt"));
        assertEquals(HttpStatus.BAD_REQUEST, filePath.getStatusCode());
        assertEquals(40004, filePath.getBody().get("code").asInt());

        ResponseEntity<JsonNode> missing = directoryListing(temp.resolve("does-not-exist"));
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());
        assertEquals(40004, missing.getBody().get("code").asInt());
    }

    @Test
    @Timeout(20)
    void persistsLlmSettingsAndHotReloadsRuntimeSnapshot() {
        ResponseEntity<JsonNode> defaults = rest.getForEntity(
                url("/api/settings/llm"), JsonNode.class);
        assertEquals(HttpStatus.OK, defaults.getStatusCode(), String.valueOf(defaults.getBody()));
        assertFalse(defaults.getBody().get("data").get("baseUrl").asText().isBlank());
        assertEquals("gpt-4o", defaults.getBody().get("data").get("modelName").asText());

        JsonNode literal = updateSettings(
                "https://llm.example.test/v1", "gpt-4o-mini", "LITERAL", "sk-test-12345678");
        assertEquals("gpt-4o-mini", literal.get("modelName").asText());
        assertEquals("LITERAL", literal.get("apiKeyType").asText());
        assertTrue(literal.get("apiKeyConfigured").asBoolean());
        assertTrue(literal.get("apiKeyValue").isNull(), "literal 密钥不能通过读取接口返回");
        assertEquals("****5678", literal.get("apiKeyPreview").asText());
        assertEquals("https://llm.example.test/v1", llmSettingsService.current().baseUrl());
        assertEquals("gpt-4o-mini", llmSettingsService.current().modelName());
        assertEquals(ApiKeyType.LITERAL, llmSettingsService.current().apiKeyType());
        assertEquals("sk-test-12345678", llmSettingsService.resolveApiKey());

        LlmSettingEntity stored = llmSettingEntityService.getById(1L);
        assertNotNull(stored);
        assertEquals("https://llm.example.test/v1", stored.getBaseUrl());
        assertEquals("sk-test-12345678", stored.getApiKeyValue());
        LlmSettingModelEntity storedModel = llmSettingModelEntityService.getById(1L);
        assertNotNull(storedModel);
        assertEquals("gpt-4o-mini", storedModel.getModelName());

        // LITERAL 留空时沿用已保存的密钥。
        JsonNode retained = updateSettings("https://llm.example.test/v1/", "LITERAL", null);
        assertEquals("****5678", retained.get("apiKeyPreview").asText());
        assertEquals("sk-test-12345678", llmSettingsService.resolveApiKey());
        assertEquals("https://llm.example.test/v1", retained.get("baseUrl").asText(),
                "请求地址应去掉结尾斜杠");

        JsonNode env = updateSettings(
                "https://gateway.example.test/v1", "qwen-plus", "ENV", "PATH");
        assertEquals("ENV", env.get("apiKeyType").asText());
        assertEquals("qwen-plus", env.get("modelName").asText());
        assertEquals("PATH", env.get("apiKeyValue").asText());
        assertTrue(env.get("envResolved").asBoolean());
        assertTrue(env.get("apiKeyPreview").isNull());
        assertEquals(System.getenv("PATH"), llmSettingsService.resolveApiKey());

        String missingName = "TASKNEST_TEST_MISSING_ENV";
        JsonNode unresolved = updateSettings(
                "https://gateway.example.test/v1", "qwen-plus", "ENV", missingName);
        assertFalse(unresolved.get("envResolved").asBoolean());
        assertNull(llmSettingsService.resolveApiKey());

        // 模拟进程重启后的启动加载：直接改库，再调用启动时使用的 load()。
        LlmSettingEntity persisted = llmSettingEntityService.getById(1L);
        persisted.setBaseUrl("https://db-only.example.test/v1");
        llmSettingEntityService.updateById(persisted);
        llmSettingsService.load();
        assertEquals("https://db-only.example.test/v1", llmSettingsService.current().baseUrl());
        assertEquals("qwen-plus", llmSettingsService.current().modelName());
        assertEquals(missingName, llmSettingsService.current().apiKeyValue());

        ResponseEntity<JsonNode> badUrl = putSettings(
                Map.of("baseUrl", "ftp://invalid", "modelName", "gpt-4o",
                        "apiKeyType", "ENV", "apiKeyValue", "PATH"));
        assertEquals(HttpStatus.BAD_REQUEST, badUrl.getStatusCode());
        assertEquals(40001, badUrl.getBody().get("code").asInt());

        ResponseEntity<JsonNode> badEnv = putSettings(
                Map.of("baseUrl", "https://gateway.example.test/v1", "modelName", "gpt-4o",
                        "apiKeyType", "ENV", "apiKeyValue", "1-bad-name"));
        assertEquals(HttpStatus.BAD_REQUEST, badEnv.getStatusCode());
        assertEquals(40001, badEnv.getBody().get("code").asInt());

        ResponseEntity<JsonNode> blankModel = putSettings(
                Map.of("baseUrl", "https://gateway.example.test/v1", "modelName", "  ",
                        "apiKeyType", "ENV", "apiKeyValue", "PATH"));
        assertEquals(HttpStatus.BAD_REQUEST, blankModel.getStatusCode());
        assertEquals(40001, blankModel.getBody().get("code").asInt());

        ResponseEntity<JsonNode> longModel = putSettings(
                Map.of("baseUrl", "https://gateway.example.test/v1", "modelName", "m".repeat(129),
                        "apiKeyType", "ENV", "apiKeyValue", "PATH"));
        assertEquals(HttpStatus.BAD_REQUEST, longModel.getStatusCode());
        assertEquals(40001, longModel.getBody().get("code").asInt());
    }

    private ResponseEntity<JsonNode> directoryListing(Path path) {
        URI uri = UriComponentsBuilder.fromHttpUrl(url("/api/fs/directories"))
                .queryParam("path", path.toString())
                .build()
                .encode()
                .toUri();
        return rest.getForEntity(uri, JsonNode.class);
    }

    private JsonNode updateSettings(String baseUrl, String type, String value) {
        return updateSettings(baseUrl, "gpt-4o", type, value);
    }

    private JsonNode updateSettings(
            String baseUrl, String modelName, String type, String value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("baseUrl", baseUrl);
        body.put("modelName", modelName);
        body.put("apiKeyType", type);
        if (value != null) {
            body.put("apiKeyValue", value);
        }
        ResponseEntity<JsonNode> response = putSettings(body);
        assertEquals(HttpStatus.OK, response.getStatusCode(), String.valueOf(response.getBody()));
        return response.getBody().get("data");
    }

    private ResponseEntity<JsonNode> putSettings(Map<String, Object> body) {
        return rest.exchange(
                url("/api/settings/llm"),
                HttpMethod.PUT,
                new HttpEntity<>(body),
                JsonNode.class);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
