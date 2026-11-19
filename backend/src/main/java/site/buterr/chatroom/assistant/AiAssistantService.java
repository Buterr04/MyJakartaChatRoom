package site.buterr.chatroom.assistant;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;

import site.buterr.chatroom.model.AiSettings;
import site.buterr.chatroom.model.Message;
import site.buterr.chatroom.store.ChatRoom;
import site.buterr.chatroom.store.LocalConfigStore;
import site.buterr.chatroom.util.JsonUtil;

public final class AiAssistantService implements AutoCloseable {

    private static final Pattern AI_MENTION = Pattern.compile("^@AI(?:\\s+|$)(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final int CONTEXT_MESSAGES = 8;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_REPLY_LENGTH = 4000;

    private final LocalConfigStore configStore;
    private final ChatRoom chatRoom;
    private final HttpClient httpClient;
    private final ThreadPoolExecutor executor;

    public AiAssistantService(LocalConfigStore configStore, ChatRoom chatRoom) {
        this.configStore = configStore;
        this.chatRoom = chatRoom;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(16), runnable -> {
                    Thread thread = new Thread(runnable, "ai-assistant");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    public boolean isEnabled() {
        return configStore.loadAiSettings().enabled();
    }

    public void handleUserMessage(Message message) {
        if (message == null || !Message.TYPE_CHAT.equals(message.type()) || message.username() == null) {
            return;
        }
        Matcher mention = AI_MENTION.matcher(message.content());
        if (!mention.matches()) {
            return;
        }
        String question = mention.group(1).trim();
        if (question.isEmpty()) {
            chatRoom.sendAssistantMessage("请在 @AI 后输入你的问题。");
            return;
        }
        List<Map<String, String>> context = conversationContext(message.id());
        try {
            executor.execute(() -> respond(context));
        } catch (RejectedExecutionException e) {
            chatRoom.sendAssistantMessage("AI 助手当前请求较多，请稍后再试。");
        }
    }

    public String testConnection() throws AiServiceException {
        AiSettings settings = configStore.loadAiSettings();
        requireCompleteSettings(settings);
        List<Map<String, String>> messages = new ArrayList<>();
        addSystemPrompt(messages, settings.systemPrompt());
        messages.add(Map.of("role", "user", "content", "请简短回复：连接成功"));
        return requestCompletion(settings, messages);
    }

    public static String normalizeBaseUrl(String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("请填写 AI 接口地址");
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("AI 接口地址格式不正确");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("AI 接口地址必须是有效的 HTTP 或 HTTPS 地址，且不能包含查询参数");
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private void respond(List<Map<String, String>> context) {
        try {
            AiSettings settings = configStore.loadAiSettings();
            if (!settings.enabled()) {
                chatRoom.sendAssistantMessage("AI 助手尚未启用，请联系超级管理员 Admin。");
                return;
            }
            requireCompleteSettings(settings);
            List<Map<String, String>> requestMessages = new ArrayList<>();
            addSystemPrompt(requestMessages, settings.systemPrompt());
            requestMessages.addAll(context);
            chatRoom.sendAssistantMessage(requestCompletion(settings, requestMessages));
        } catch (AiServiceException e) {
            chatRoom.sendAssistantMessage(e.getMessage());
        } catch (RuntimeException e) {
            System.err.println("AI 助手处理失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            chatRoom.sendAssistantMessage("AI 助手暂时无法回复，请稍后再试。");
        }
    }

    private List<Map<String, String>> conversationContext(long triggerId) {
        List<Message> chatMessages = chatRoom.historyAfter(0).stream()
                .filter(message -> message.id() <= triggerId)
                .filter(message -> Message.TYPE_CHAT.equals(message.type()))
                .filter(message -> message.content() != null && !message.content().isBlank())
                .toList();
        int fromIndex = Math.max(0, chatMessages.size() - CONTEXT_MESSAGES);
        return chatMessages.subList(fromIndex, chatMessages.size()).stream()
                .map(message -> Map.of(
                        "role", message.username() == null ? "assistant" : "user",
                        "content", message.username() == null
                                ? message.content()
                                : message.displayName() + "：" + message.content()))
                .toList();
    }

    private String requestCompletion(AiSettings settings, List<Map<String, String>> messages)
            throws AiServiceException {
        URI endpoint = completionEndpoint(settings.baseUrl());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", settings.model());
        payload.put("stream", false);
        payload.put("messages", messages);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(payload)));
        if (settings.hasApiKey()) {
            builder.header("Authorization", "Bearer " + settings.apiKey());
        }

        try {
            HttpResponse<InputStream> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBytes;
            try (InputStream input = response.body()) {
                responseBytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (responseBytes.length > MAX_RESPONSE_BYTES) {
                throw new AiServiceException("AI 接口响应过大，已停止处理");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AiServiceException(friendlyHttpError(response.statusCode()));
            }
            JsonNode root = JsonUtil.readTree(new String(responseBytes, java.nio.charset.StandardCharsets.UTF_8));
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.textValue().isBlank()) {
                throw new AiServiceException("AI 接口返回格式不兼容，未找到回复内容");
            }
            String reply = content.textValue().trim();
            return reply.length() <= MAX_REPLY_LENGTH
                    ? reply
                    : reply.substring(0, MAX_REPLY_LENGTH) + "…";
        } catch (HttpTimeoutException e) {
            throw new AiServiceException("AI 接口响应超时，请稍后重试", e);
        } catch (JacksonException e) {
            throw new AiServiceException("AI 接口没有返回有效的 JSON", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiServiceException("AI 请求已中断", e);
        } catch (IOException e) {
            throw new AiServiceException("无法连接 AI 接口，请检查接口地址和网络", e);
        }
    }

    private static URI completionEndpoint(String baseUrl) {
        String normalized = normalizeBaseUrl(baseUrl);
        return URI.create(normalized.endsWith("/chat/completions")
                ? normalized
                : normalized + "/chat/completions");
    }

    private static void requireCompleteSettings(AiSettings settings) throws AiServiceException {
        if (settings.baseUrl().isBlank() || settings.model().isBlank()) {
            throw new AiServiceException("AI 助手尚未配置完整，请联系超级管理员 Admin。");
        }
    }

    private static void addSystemPrompt(List<Map<String, String>> messages, String systemPrompt) {
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
    }

    private static String friendlyHttpError(int statusCode) {
        if (statusCode == 400) {
            return "AI 接口拒绝了请求，请检查接口地址、模型和系统提示词";
        }
        if (statusCode == 401 || statusCode == 403) {
            return "AI 接口认证失败，请检查 API Key";
        }
        if (statusCode == 404) {
            return "未找到 AI 接口，请检查接口地址";
        }
        if (statusCode == 429) {
            return "AI 接口请求过于频繁或额度不足，请稍后重试";
        }
        if (statusCode >= 500) {
            return "AI 服务暂时不可用（HTTP " + statusCode + "）";
        }
        return "AI 接口请求失败（HTTP " + statusCode + "）";
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
