package site.buterr.chatroom.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record AiSettings(String baseUrl, @JsonIgnore String apiKey, String model, boolean enabled,
        String systemPrompt, long updatedAt) {

    public static final String DEFAULT_SYSTEM_PROMPT = "你是聊天室里的 AI 助手，你的名字是大肥鱼，请使用简洁、友善的中文回答。";

    public static AiSettings empty() {
        return new AiSettings("", "", "", false, DEFAULT_SYSTEM_PROMPT, 0);
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
