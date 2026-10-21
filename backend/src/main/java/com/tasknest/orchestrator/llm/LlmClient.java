package com.tasknest.orchestrator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasknest.service.LlmSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小 OpenAI 兼容 /chat/completions 客户端，支持 function tool calling。
 * 不依赖 Spring AI，避免其与当前 Spring Boot 版本不匹配；请求与响应格式保持兼容。
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(4);

    private final LlmSettingsService settings;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LlmClient(LlmSettingsService settings, ObjectMapper objectMapper) {
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    public String modelName() {
        return settings.current().modelName();
    }

    public boolean configured() {
        String key = settings.resolveApiKey();
        return key != null && !key.isBlank();
    }

    public LlmResponse chat(List<LlmMessage> messages, List<Map<String, Object>> tools) {
        LlmSettingsService.CurrentLlmSettings current = settings.current();
        String apiKey = settings.resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException("LLM_NOT_CONFIGURED",
                    "尚未配置可用的 LLM API Key，请在“LLM 设置”中填写");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", current.modelName());
        body.put("messages", messages.stream().map(LlmMessage::toWire).toList());
        body.put("temperature", 0.2);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }

        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(body);
        } catch (IOException exception) {
            throw new LlmException("LLM_REQUEST_FAILED", "无法序列化模型请求", exception);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint(current.baseUrl())))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(
                    StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmException("LLM_REQUEST_FAILED", "模型请求被中断", exception);
        } catch (IOException exception) {
            throw new LlmException("LLM_REQUEST_FAILED",
                    "无法连接模型服务: " + exception.getMessage(), exception);
        }
        if (response.statusCode() / 100 != 2) {
            String message = errorMessage(response.body(), response.statusCode());
            boolean toolIssue = message.toLowerCase().contains("tool")
                    || message.toLowerCase().contains("function");
            String code = (response.statusCode() == 400 || response.statusCode() == 422)
                    && toolIssue ? "LLM_TOOL_CALLING_UNSUPPORTED" : "LLM_REQUEST_FAILED";
            log.warn("LLM 请求失败 status={} model={} code={}",
                    response.statusCode(), current.modelName(), code);
            throw new LlmException(code, message);
        }
        return parseResponse(response.body(), current.modelName());
    }

    private LlmResponse parseResponse(String rawBody, String fallbackModel) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new LlmException("LLM_REQUEST_FAILED", "模型响应缺少 choices");
            }
            JsonNode choice = choices.get(0);
            JsonNode message = choice.path("message");
            String content = textContent(message.get("content"));
            List<LlmToolCall> toolCalls = new ArrayList<>();
            JsonNode calls = message.path("tool_calls");
            if (calls.isArray()) {
                for (JsonNode call : calls) {
                    String id = call.path("id").asText("");
                    JsonNode function = call.path("function");
                    String name = function.path("name").asText("");
                    String arguments = function.path("arguments").asText("{}");
                    if (!name.isBlank()) {
                        toolCalls.add(new LlmToolCall(id, name, arguments));
                    }
                }
            }
            String finishReason = choice.path("finish_reason").asText(null);
            String model = root.path("model").asText(fallbackModel);
            return new LlmResponse(content, toolCalls, finishReason, model);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof LlmException llmException) {
                throw llmException;
            }
            throw new LlmException("LLM_REQUEST_FAILED",
                    "无法解析模型响应: " + exception.getMessage(), exception);
        }
    }

    /** 某些兼容服务返回 content 数组。 */
    private String textContent(JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode part : content) {
                if (part.isTextual()) {
                    builder.append(part.asText());
                } else if (part.has("text")) {
                    builder.append(part.path("text").asText(""));
                }
            }
            return builder.toString();
        }
        return content.toString();
    }

    private String errorMessage(String body, int status) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode error = root.path("error");
            if (error.isObject()) {
                String message = error.path("message").asText("");
                if (!message.isBlank()) {
                    return message;
                }
            }
        } catch (IOException ignored) {
            // 非 JSON 响应，退回原始文本。
        }
        String value = body == null ? "" : body.trim();
        if (value.length() > 500) {
            value = value.substring(0, 500);
        }
        return "模型服务返回 HTTP " + status + (value.isEmpty() ? "" : ": " + value);
    }

    private String endpoint(String baseUrl) {
        String value = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (value.endsWith("/chat/completions")) {
            return value;
        }
        return value + "/chat/completions";
    }

    /** 一条 OpenAI 兼容消息。 */
    public record LlmMessage(
            String role,
            String content,
            String toolCallId,
            String name,
            List<LlmToolCall> toolCalls
    ) {
        public static LlmMessage system(String content) {
            return new LlmMessage("system", content, null, null, null);
        }

        public static LlmMessage user(String content) {
            return new LlmMessage("user", content, null, null, null);
        }

        public static LlmMessage assistant(String content, List<LlmToolCall> toolCalls) {
            return new LlmMessage("assistant", content, null, null, toolCalls);
        }

        public static LlmMessage tool(String toolCallId, String content) {
            return new LlmMessage("tool", content, toolCallId, null, null);
        }

        public Map<String, Object> toWire() {
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("role", role);
            if (content != null && !content.isEmpty()) {
                wire.put("content", content);
            } else if (!"assistant".equals(role)) {
                wire.put("content", "");
            }
            if (toolCallId != null) {
                wire.put("tool_call_id", toolCallId);
            }
            if (name != null) {
                wire.put("name", name);
            }
            if (toolCalls != null && !toolCalls.isEmpty()) {
                wire.put("tool_calls", toolCalls.stream().map(call -> Map.of(
                        "id", call.id(),
                        "type", "function",
                        "function", Map.of(
                                "name", call.name(),
                                "arguments", call.arguments()))).toList());
            }
            return wire;
        }
    }

    public record LlmToolCall(String id, String name, String arguments) {
    }

    public record LlmResponse(
            String content,
            List<LlmToolCall> toolCalls,
            String finishReason,
            String model
    ) {
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }
}
