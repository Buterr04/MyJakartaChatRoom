package site.buterr.chatroom.servlet;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import site.buterr.chatroom.assistant.AiAssistantService;
import site.buterr.chatroom.assistant.AiServiceException;
import site.buterr.chatroom.model.AiSettings;
import site.buterr.chatroom.store.ChatRepository;
import site.buterr.chatroom.util.JsonUtil;

public class AiSettingsServlet extends HttpServlet {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SettingsBody(String baseUrl, String apiKey, Boolean clearApiKey, String model, Boolean enabled,
            String systemPrompt) {
    }

    private final ChatRepository repository;
    private final AiAssistantService assistant;

    public AiSettingsServlet(ChatRepository repository, AiAssistantService assistant) {
        this.repository = repository;
        this.assistant = assistant;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonUtil.write(resp, HttpServletResponse.SC_OK, settingsResponse(repository.loadAiSettings()));
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SettingsBody body = JsonUtil.read(req, SettingsBody.class);
        if (body == null) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "请求内容不合法"));
            return;
        }
        String baseUrl;
        try {
            baseUrl = AiAssistantService.normalizeBaseUrl(body.baseUrl());
        } catch (IllegalArgumentException e) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", e.getMessage()));
            return;
        }
        String model = body.model() == null ? "" : body.model().trim();
        String apiKey = body.apiKey() == null ? "" : body.apiKey().trim();
        String systemPrompt = body.systemPrompt() == null || body.systemPrompt().isBlank()
                ? AiSettings.DEFAULT_SYSTEM_PROMPT
                : body.systemPrompt().trim();
        if (model.isEmpty() || model.length() > 100) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST,
                    Map.of("error", "模型名称不能为空，且不能超过 100 个字符"));
            return;
        }
        if (baseUrl.length() > 500 || apiKey.length() > 1000 || systemPrompt.length() > 4000) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST,
                    Map.of("error", "接口地址、API Key 或系统提示词过长"));
            return;
        }
        AiSettings current = repository.loadAiSettings();
        if (Boolean.TRUE.equals(body.clearApiKey())) {
            apiKey = "";
        } else if (apiKey.isEmpty()) {
            apiKey = current.apiKey();
        }
        AiSettings saved = repository.saveAiSettings(baseUrl, apiKey, model,
                Boolean.TRUE.equals(body.enabled()), systemPrompt);
        JsonUtil.write(resp, HttpServletResponse.SC_OK, settingsResponse(saved));
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String reply = assistant.testConnection();
            JsonUtil.write(resp, HttpServletResponse.SC_OK, Map.of("message", "连接成功", "reply", reply));
        } catch (AiServiceException e) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_GATEWAY, Map.of("error", e.getMessage()));
        }
    }

    private static Map<String, Object> settingsResponse(AiSettings settings) {
        return Map.of(
                "baseUrl", settings.baseUrl(),
                "model", settings.model(),
                "enabled", settings.enabled(),
                "systemPrompt", settings.systemPrompt(),
                "updatedAt", settings.updatedAt(),
                "hasApiKey", settings.hasApiKey());
    }
}
