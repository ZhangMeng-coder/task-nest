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
 *
 * <p>由各 Agent 经 {@link com.tasknest.orchestrator.agent.AgentLoop} 调用。
 * Base URL 与模型名取自 {@link LlmSettingsService} 的运行时快照，API Key 每次调用时解析；
 * 连接超时 20 秒、请求超时 4 分钟。HTTP 非 2xx 统一映射为 {@link LlmException}，
 * 400 / 422 且错误信息涉及 tool / function 时标记为 LLM_TOOL_CALLING_UNSUPPORTED。
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

    /** 当前运行时快照中的模型名称，用于事件与审核结果标注。 */
    public String modelName() {
        return settings.current().modelName();
    }

    /** 当前设置能否解析出非空 API Key；ENV 模式会在此刻读取环境变量。 */
    public boolean configured() {
        String key = settings.resolveApiKey();
        return key != null && !key.isBlank();
    }

    /**
     * 发起一次 chat completion。
     *
     * <p>请求携带低温度与 messages；tools 非空时附加 {@code tool_choice=auto}。
     * 未配置 API Key 抛 LLM_NOT_CONFIGURED；网络、序列化或响应结构错误抛
     * LLM_REQUEST_FAILED。返回的 tool_calls 未执行，由 AgentLoop 归一化后调用工具。
     *
     * @param tools OpenAI function 定义；null 或空表示不启用工具
     */
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
        // 低温度减少工具调用参数漂移，保证计划与审核 JSON 输出稳定。
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
            // 部分兼容服务不支持 function calling，用 400/422 + 错误文本识别并单独标记。
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

    // 允许 Base URL 填到 /chat/completions，或只填到服务根 / /v1。
    private String endpoint(String baseUrl) {
        String value = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (value.endsWith("/chat/completions")) {
            return value;
        }
        return value + "/chat/completions";
    }

    /**
     * 一条 OpenAI 兼容消息。tool 消息必须携带 toolCallId，与 assistant 消息中的
     * tool_calls 一一配对；{@link #toWire()} 会把内部字段转换为 snake_case 的请求结构。
     */
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
            // assistant 可只带 tool_calls 而不带 content；其他角色始终保留 content 字段。
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

    /** 模型返回的一次 function 调用；arguments 是尚未解析的 JSON 字符串。 */
    public record LlmToolCall(String id, String name, String arguments) {
    }

    /** 一次 chat completion 的解析结果；toolCalls 可能为空，finishReason 可能为 null。 */
    public record LlmResponse(
            String content,
            List<LlmToolCall> toolCalls,
            String finishReason,
            String model
    ) {
        /** 本轮响应是否请求了至少一次工具调用。 */
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }
}
